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

import dysasymptotics.linearSize
import errorDiagnostics.stackTracesDiagnostics
import parsing.trackPositions

object Script:
  // The schema, read once from this jar; in time a published `tels/2` module. Our own resource
  // cannot be absent or invalid except through a broken build, hence `panic` and `unsafely`.
  lazy val schema: Tels = unsafely:
    val data = Classloader[Script].resource(t"fever/script.schema.tel").or:
      panic(m"the script schema resource is missing from the jar")

    Tels.Validation.validate(data.read[Tel].as[Tels])

  // One problem with the header: where (a TEL pointer, and a span in the file) and what.
  case class Rejection(pointer: Text, span: Span, message: Text)

  // The accrual every problem joins, so that a header is reported whole, never one error at a
  // time: the pattern of stratiform's own accrual tests.
  case class Rejections(items: List[Rejection] = Nil)(using Diagnostics)
  extends fulminate.Error(m"${items.size} rejections"):
    def add(rejection: Rejection): Rejections = Rejections(items + List(rejection))

  object Error:
    enum Reason:
      case Unreadable(detail: Text)
      case NoSeparator
      case Invalid(rejections: List[Rejection])

    given communicable: Reason is Communicable =
      case Reason.Unreadable(detail) =>
        m"the header is not a TEL document: $detail"

      case Reason.NoSeparator =>
        m"the file has no `##` line separating the header from the source"

      case Reason.Invalid(items) =>
        m"the header has ${items.size} problems"

  case class Error(reason: Error.Reason)(using Diagnostics)
  extends fulminate.Error(m"the script header is invalid: $reason")

  // A parsed script: its header, and the source body. The body keeps the file's line numbering:
  // the header's lines are replaced by blank ones, so that every position the compiler reports
  // is already a position in the script, with no remapping.
  case class Parsed(header: Script, body: Text)

  def parse(text: Text): Parsed raises Script.Error =
    val document =
      mitigate:
        case error: Tel.Error => Script.Error(Error.Reason.Unreadable(error.message.text))

      . protect(text.load[Tel])

    // The `##` line is TEL's own document separator, and the parser stops at it (TEL §6.1): the
    // continuation — the line after it — is where the Scala source begins.
    val line = document.metadata.continuation.lest(Script.Error(Error.Reason.NoSeparator))

    val body =
      text.cut(t"\n").indexed.map: (text, index) => if index.n0 < line - 1 then t"" else text
      . join(t"\n")

    // Validation first, accruing every violation; decoding second, which cannot then fail
    // structurally.
    val rejections = validate[Tel.Focus](Rejections()):
      case error: Tel.Error =>
        val pointer = prior.let(_.pointer.encode).or(t"/")
        accrual.add(Rejection(pointer, prior.lay(Span.empty)(_.span), error.message.text))

    . protect(Tels.Decoder.validate(document.root)(using schema, Tel.Validator.Registry.builtins))

    if !rejections.items.nil then abort(Script.Error(Error.Reason.Invalid(rejections.items)))

    Parsed(decode(document.root), body)

  // The model, read from a document the schema has already accepted — so `language` is present
  // with its form as the primary atom, and the repeated fields are what they claim to be. By
  // hand, as flame and flair read their configuration, rather than by stratiform's derivation,
  // which capture checking does not yet admit; three fields hardly warrant more.
  private def decode(tel: Tel): Script =
    def atoms(node: Tel, keyword: Text): List[Text] =
      node.fields(keyword).to[List].map(_.primaryAtom).filter(_ != t"")

    val language = tel.field(t"language").or(panic(m"the validated header has no language"))
    Script(Language(language.primaryAtom, atoms(language, t"flag")), atoms(tel, t"classpath"))

// The header of a script: the TEL document before the `##` line, in Fury's vocabulary (lira's
// fury.md §12a), conforming to `script.schema.tel`. `language scala` is positional — the atom
// is the form — with the compiler flags beneath it; `classpath` entries resolve against the
// script's own directory. The schema is deliberately this small (fever.md §6a); what is absent
// arrives later as layers, so that each step is a TEL subtype and never a revision.
case class Script(language: Language, classpath: List[Text] = Nil)
case class Language(form: Text, flag: List[Text] = Nil)
