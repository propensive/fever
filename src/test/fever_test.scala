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
import strategies.throwUnsafely

object Tests extends Suite(m"Fever tests"):
  val hello: Text = t"""#!/usr/bin/env fever
tel 1.0

language scala
  flag -deprecation

classpath lib/extras.jar

##

def main(using Runtime): Unit =
  Out.println(t"Hello world")
"""

  def run(): Unit =
    suite(m"Script headers"):
      test(m"a script's header decodes to its form, flags and classpath"):
        Script.parse(hello).header

      . assert(_ == Script(Language(t"scala", List(t"-deprecation")), List(t"lib/extras.jar")))

      test(m"the body keeps the file's line numbering"):
        val blanks = t"\n\n\n\n\n\n\n\n\n\n"
        val source = t"def main(using Runtime): Unit =\n  Out.println(t\"Hello world\")\n"
        Script.parse(hello).body == blanks + source

      . assert(_ == true)

      test(m"a header without a pragma or an interpreter directive is still a header"):
        Script.parse(t"language scala\n##\ndef main(using Runtime): Unit = ()\n").header

      . assert(_ == Script(Language(t"scala")))

      test(m"a file with no `##` line is rejected"):
        capture[Script.Error](Script.parse(t"language scala\n")).reason

      . assert(_ == Script.Error.Reason.NoSeparator)

      test(m"a `##` that is not alone on its line is not the separator"):
        capture[Script.Error](Script.parse(t"language scala\n## not yet\n")).reason

      . assert(_ == Script.Error.Reason.NoSeparator)

      test(m"every problem with a header is reported at once"):
        capture[Script.Error](Script.parse(t"language rust\nflags -x\ncolour blue\n##\n")).reason
        match
          case Script.Error.Reason.Invalid(rejections) => rejections.size
          case _                                       => 0

      . assert(_ == 3)

      test(m"a header without `language` is rejected"):
        capture[Script.Error](Script.parse(t"classpath a.jar\n##\n")).reason match
          case Script.Error.Reason.Invalid(rejections) => rejections.map(_.pointer)
          case _                                       => Nil

      . assert(_ == List(t"/language"))

      test(m"a rejection names its line"):
        capture[Script.Error](Script.parse(t"language scala\n\ncolour blue\n##\n")).reason match
          case Script.Error.Reason.Invalid(rejections) => rejections.map(_.span.startLine.let(_.n1))
          case _                                       => Nil

      . assert(_ == List(3))

    suite(m"Diagnostics"):
      test(m"a notice renders as scalac prints one"):
        val line = Ordinal.zerary(11)
        val span = Span.area(line, Ordinal.zerary(2), line, Ordinal.zerary(3))
        Compile.render(Notice(Importance.Error, t"hello", t"Not found: x", span), t"/tmp/hello")

      . assert(_ == t"/tmp/hello:12:3: error: Not found: x")
