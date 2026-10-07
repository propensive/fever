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
import probably.TestEvent

// Fever's own suite, run WITHOUT fume: since Soundness 0.65.0 a `Suite` has no `main` (the host —
// normally fume — drives it through `invoke`), so this is the plain-`java` entry point `make
// test-plain` uses, printing one line per completed test and exiting with the suite's status
// (0 = passed, 1 = failures, 2 = the suite threw). `fume run -c <test jar>` remains the full
// experience, exactly as in flame.
@main
def runTests(): Unit =
  val tally = Tally()
  val out = java.lang.System.out.nn

  val status = Tests.invoke(t"", event => event match
    case TestEvent.TestCompleted(test, _, _, outcome, _, _) =>
      tally.record(outcome.outcome == t"pass" || outcome.outcome == t"aspire-pass")
      out.println(t"[${outcome.outcome}] ${test.path.join(t" / ")}".s)

    case TestEvent.DetailMessage(_, message) =>
      out.println(t"    $message".s)

    case TestEvent.DetailCompare(_, expected, found, _) =>
      out.println(t"    expected: $expected".s)
      out.println(t"    found:    $found".s)

    case TestEvent.RunTerminated(error, _, _) =>
      out.println(t"suite threw: ${error.components.map(_.message).join(t"; ")}".s)

    case _ => ())

  out.println(t"${tally.passed} passed, ${tally.failed} failed".s)
  java.lang.System.exit(status)

// Counts outcomes for the summary line. A class rather than local `var`s so the event sink,
// which must be a pure `TestEvent -> Unit`, captures nothing it may not; the counters hold pure
// data, so they are untracked, as exoskeleton's own `Invocation` holds its.
private final class Tally:
  @scala.caps.unsafe.untrackedCaptures private var passes: Int = 0
  @scala.caps.unsafe.untrackedCaptures private var failures: Int = 0
  def passed: Int = passes
  def failed: Int = failures
  def record(pass: Boolean): Unit = if pass then passes += 1 else failures += 1
