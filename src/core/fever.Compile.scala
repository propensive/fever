                                                                                                  /*
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                                                                                                  ┃
┃                                 ╭───╮╭───╮                                                       ┃
┃                                 │   ││   │                                                       ┃
┃                                 │   │╰───╯                                                       ┃
┃                                 │   │╭───╮╭───╮╌────╮╭─────────╮                                 ┃
┃                                 │   ││   ││   ╭──╮  ││   ╭─╮   │                                 ┃
┃                                 │   ││   ││   │  ╰──╯│   │ │   │                                 ┃
┃                                 │   ││   ││   │      │   │ │   │                                 ┃
┃                                 │   ││   ││   │      │   ╰─╯   │                                 ┃
┃                                 ╰───╯╰───╯╰───╯      ╰─────╌╰──╯                                 ┃
┃                                                                                                  ┃
┃    LIRA, version 0.1.0.                                                                          ┃
┃    © Copyright 2026 Jon Pretty, Propensive OÜ.                                                   ┃
┃                                                                                                  ┃
┃    The primary distribution site is:                                                             ┃
┃                                                                                                  ┃
┃        https://lira.nexus/                                                                       ┃
┃                                                                                                  ┃
┃    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file     ┃
┃    except in compliance with the License. You may obtain a copy of the License at                ┃
┃                                                                                                  ┃
┃        https://www.apache.org/licenses/LICENSE-2.0                                               ┃
┃                                                                                                  ┃
┃    Unless required by applicable law or agreed to in writing,  software distributed under the    ┃
┃    License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    ┃
┃    either express or implied. See the License for the specific language governing permissions    ┃
┃    and limitations under the License.                                                            ┃
┃                                                                                                  ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
                                                                                                  */
package fever

import soundness.*

import alphabets.hexLowerCase
import filesystemBackends.javaBaseFilesystem
import logging.silentLogging
import probates.cancelProbate
import providers.soundnessProvider
import systems.javaBaseSystem

// Compiling a script's body: a cold compile (fever.md §1) through anthology, against the
// classpath Fever itself runs with — the fork's standard library, Soundness, and a script's
// `Runtime` — plus the header's `classpath` entries, into a directory in the user's cache named
// by everything that determines its contents, so that a script Fever has seen is never compiled
// again (fury.md §12a's fast path).
object Compile:
  // The options every body compiles with, before the header's own `flag`s. These are Fever's own
  // build options (build.mill) less the warnings and the capture and separation checking a
  // one-file script should not have to satisfy: the fork's repairs, which reading Soundness's
  // TASTy needs; the language features Soundness idioms use; and the predef — proscenium's, with
  // Soundness and a script's `Runtime` already imported, which is what lets a body start at
  // `def main`. Soundness's inclusion is provisional (fever.md §6a).
  val baseline: List[Text] = List(
    t"-Zalias-captures",
    t"-Zdiagnostic-givens",
    t"-Zgiven-prefixes",
    t"-Zinline-source-maps",
    t"-Zopaque-mutability",
    t"-Zpure-iarrays",
    t"-Zretains-bounds",
    t"-Zretains-skolems",
    t"-Zspreadable-varargs",
    t"-Zunboxed-pure-types",
    t"-Zunion-captures",
    t"-experimental",
    t"-new-syntax",
    t"-feature",
    t"-preview",
    t"-deprecation",
    t"-Xmax-inlines", t"32",
    t"-Yno-flexible-types",
    t"-Yexplicit-nulls",
    t"-language:experimental.modularity",
    t"-language:experimental.dependent",
    t"-language:experimental.relaxedLambdaSyntax",
    t"-language:experimental.genericNumberLiterals",
    t"-language:experimental.into",
    t"-language:experimental.erasedDefinitions",
    t"-language:implicitConversions",
    t"-language:experimental.saferExceptions",
    t"-language:experimental.strictEqualityPatternMatching",
    t"-language:experimental.subCases",
    t"-language:experimental.multiSpreads",
    t"-Yno-predef",
    t"-Yimports:java.lang,proscenium,soundness,pyrocosm")

  // The header's `classpath` entries, absolute: relative to the script's directory, as the
  // header promises, never to the daemon's.
  def entries(script: Path on Linux, header: Script): List[Text] =
    val directory: Text = script.parent.let(_.encode).or(t"/")

    header.classpath.map: entry => if entry.starts(t"/") then entry else directory + t"/" + entry

  // The compile classpath: Fever's own, which under a host is the loader's and otherwise
  // `java.class.path`, with the header's entries after it.
  def classpath(entries: List[Text])(using System): LocalClasspath =
    val own = LocalClasspath.of(Classloader[Compile.type])

    val extra: List[Classpath.Entry.Directory | Classpath.Entry.Jar] = entries.map: entry =>
      if entry.ends(t".jar") then Classpath.Entry.Jar(entry) else Classpath.Entry.Directory(entry)

    LocalClasspath((own.entries + extra)*)

  // What determines a compilation's output, and so names its cache entry: Fever's version (one
  // Fever per Scala version, so the compiler's too), the whole file (header and body), and the
  // classpath entries by path.
  def key(text: Text, entries: List[Text]): Text =
    t"${Fever.version}\n$text\n${entries.join(t":")}".digest[Blake3].serialize[Hex]

  def cache(key: Text)(using Environment): Path on Linux =
    unsafely(Xdg.cacheHome[Path on Linux] / t"fever" / t"scripts" / key)

  // A completed compilation marks its directory, so that an interrupted one is not mistaken for
  // a cached result.
  def marker(out: Path on Linux): Path on Linux = unsafely(out / t".complete")

  def compile
    ( file: Text, body: Text, flags: List[Text], classpath: LocalClasspath, out: Path on Linux )
    ( using Monitor, System )
    ( using Tactic[Compiler.Error] )
  :   CompileProcess =

    val options = (baseline + flags).map(Scalac.Option[Scalac.Versions](_))
    Scalac[3.9](options)(classpath)(Map(file -> body), out)

  // A diagnostic as scalac prints one, `file:line:column: severity: message`, which the body's
  // preserved line numbering makes point into the script itself. The compiler names the virtual
  // source by its last segment alone, so the script's path is supplied.
  def render(notice: Notice, file: Text): Text =
    val position: Text = notice.span.lay(t""): span =>
      val line = span.startLine.let(_.n1).or(0)
      val column = span.startColumn.let(_.n1).or(0)
      t":$line:$column"

    val severity: Text = notice.importance match
      case Importance.Error   => t"error"
      case Importance.Warning => t"warning"
      case Importance.Info    => t"info"

    t"$file$position: $severity: ${notice.message}"
