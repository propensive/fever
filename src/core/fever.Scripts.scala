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

import charsets.utf8Charset
import codepages.utf8Codepage
import filesystemBackends.javaBaseFilesystem
import filesystemOptions.deleteRecursively
import filesystemOptions.dereferenceSymlinks
import filesystemTraversal.preOrderTraversal
import pathInterfaces.pathOnLinux
import pyrocosm.Runtime  // not `java.lang`'s, which the root import would otherwise supply
import systems.javaBaseSystem
import textSanitizers.skipSanitizer

// A script, end to end: read, split, validated, compiled unless cached, checked for its entry
// point, and run with a `Runtime` made from the invocation. Every failure is a status of its own
// (the manpage's EXIT STATUS lists them), reported on the client's stderr.
object Scripts:
  type Outcome =
    Exit | NoScript.type | InvalidHeader.type | CompileFailed.type | NoEntryPoint.type |
      ScriptFailed.type

  def run(path: Text, arguments: List[Text], force: Boolean)
    ( using Stdio, Environment, WorkingDirectory, Monitor )
  :   Outcome =

    recover:
      case error: Path.Error =>
        Err.println(t"fever: $path: ${error.message.text}")
        NoScript

      case error: Io.Error =>
        Err.println(t"fever: $path: ${error.message.text}")
        NoScript

      case error: Script.Error =>
        report(path, error)
        InvalidHeader

      case error: Compiler.Error =>
        Err.println(t"fever: ${error.message.text}")
        CompileFailed

      case error: Async.Error =>
        Err.println(t"fever: ${error.message.text}")
        CompileFailed

      case error: Launch.Error =>
        Err.println(t"fever: $path: ${error.message.text}")
        NoEntryPoint

    . protect:
        val file = path.as[Path on Linux]
        val text = file.read[Text]
        val parsed = Script.parse(text)
        val entries = Compile.entries(file, parsed.header)
        val out = Compile.cache(Compile.key(text, entries))

        // The whole-file fast path (fury.md §12a): a script Fever has compiled before, against
        // the same classpath, runs from its cached classes with nothing compiled.
        val cached = !force && Compile.marker(out).existent()

        if cached || compile(file, parsed, entries, out) then launch(out, entries, arguments)
        else CompileFailed

  // A header's problems, each on its own line as a compiler would report them, so that an
  // editor can jump to each.
  private def report(path: Text, error: Script.Error)(using Stdio): Unit = error.reason match
    case Script.Error.Reason.Invalid(rejections) =>
      rejections.each: rejection =>
        val line: Int = rejection.span.startLine.let(_.n1).or(1)
        val message: Text = rejection.message
        val pointer: Text = rejection.pointer
        val report: Text = t"$path:$line: error: $message (at $pointer)"
        Err.println(report)

    case _ =>
      Err.println(t"fever: $path: ${error.message.text}")

  private def compile
    ( file: Path on Linux, parsed: Script.Parsed, entries: List[Text], out: Path on Linux )
    ( using Stdio, Monitor )
  :   Boolean raises Compiler.Error raises Async.Error raises Io.Error =

    if out.existent() then out.wipe()
    out.create[Directory](CreateFlag.Parents)

    val flags = parsed.header.language.flag
    val classpath = Compile.classpath(entries)
    val process = Compile.compile(file.encode, parsed.body, flags, classpath, out)
    val result = process.complete()

    process.notices.each: notice => Err.println(Compile.render(notice, file.encode))

    result match
      case CompileResult.Success =>
        Compile.marker(out).create[File]()
        true

      case CompileResult.Failure =>
        false

      case CompileResult.Crash(_) =>
        Err.println(t"fever: the compiler crashed")
        false

  private def launch(out: Path on Linux, entries: List[Text], arguments: List[Text])
    ( using Stdio, Environment, WorkingDirectory )
  :   Exit | ScriptFailed.type raises Launch.Error raises Io.Error =

    val tastyFiles = out.descendants.to[List].map(_.encode).filter(_.ends(t".tasty"))

    val classpath: List[Text] = Compile.classpath(entries).entries.map:
      case Classpath.Entry.Directory(path) => path
      case Classpath.Entry.Jar(path)       => path
      case Classpath.Entry.JavaRuntime     => t""

    . filter(_ != t"")

    val entryPoint = Launch.entryPoint(tastyFiles, classpath)
    val runtime = Runtime(arguments, summon[Environment], summon[WorkingDirectory], summon[Stdio])

    try
      Launch.run(out, entries, entryPoint, runtime)
      Exit.Ok
    catch case error: java.lang.reflect.InvocationTargetException =>
      // The script's own exception, unwrapped from reflection's: its trace is what the author
      // needs, and it goes where the script's other stray output does.
      error.getCause.nn.printStackTrace(summon[Stdio].err)
      ScriptFailed
