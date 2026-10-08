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

import scala.quoted.Quotes
import scala.tasty.inspector as sti

import soundness.*

// A script's `Runtime` is Pyrocosm's, not `java.lang`'s, which the root import would otherwise
// supply.
import pyrocosm.Runtime

// Running a compiled script: finding its entry point, and calling it with its `Runtime`.
object Launch:
  object Error:
    enum Reason:
      case NoMain
      case WrongSignature(found: Text)
      case Uninspectable(detail: Text)

    given communicable: Reason is Communicable =
      case Reason.NoMain =>
        m"the script defines no top-level `def main(using Runtime): Unit`"

      case Reason.WrongSignature(found) =>
        m"the script's `main` must be `def main(using Runtime): Unit`, not `$found`"

      case Reason.Uninspectable(detail) =>
        m"the compiled script could not be inspected: $detail"

  case class Error(reason: Error.Reason)(using Diagnostics)
  extends fulminate.Error(m"the script has no entry point: $reason")

  // The module class holding `main`, by JVM name. The check is made on the compiled TASTy
  // (fever.md §6a) rather than on the source text, so it is exact: a top-level `main` whose one
  // parameter clause is a `using` clause of a single `pyrocosm.Runtime`, returning `Unit`. Its
  // module class is what reflection then loads.
  def entryPoint(tastyFiles: List[Text], classpath: List[Text]): Text raises Launch.Error =
    var found: Optional[Text] = Unset
    var mismatch: Optional[Text] = Unset

    object inspector extends sti.Inspector:
      def inspect(using quotes: Quotes)(tastys: scala.List[sti.Tasty[quotes.type]]): Unit =
        import quotes.reflect.*
        val runtime = Symbol.requiredClass("pyrocosm.Runtime").typeRef

        // Whether `main`'s one parameter clause is `(using Runtime)` and its result `Unit`.
        def fits(clauses: scala.List[ParamClause], result: TypeTree): Boolean = clauses match
          case scala.List(clause: TermParamClause) =>
            clause.isGiven && clause.params.size == 1 &&
              clause.params.head.tpt.tpe <:< runtime && result.tpe =:= defn.UnitClass.typeRef

          case _ =>
            false

        def visit(tree: Tree): Unit = tree match
          case PackageClause(_, statements) =>
            statements.foreach(visit)

          case classDef @ ClassDef(_, _, _, _, body) if classDef.symbol.flags.is(Flags.Module) =>
            body.foreach:
              case defDef @ DefDef("main", clauses, result, _) =>
                if fits(clauses, result) then found = Text(classDef.symbol.fullName)
                else mismatch = Text(defDef.symbol.signature.toString)

              case _ =>
                ()

          case _ =>
            ()

        tastys.foreach: tasty => visit(tasty.ast)

    val ok =
      try
        sti.TastyInspector.inspectAllTastyFiles
          (tastyFiles.stdlib.map(_.s), scala.Nil, classpath.stdlib.map(_.s))(inspector)
      catch case error: Exception =>
        abort(Launch.Error(Error.Reason.Uninspectable(Text(error.getMessage.nn))))

    if !ok then abort(Launch.Error(Error.Reason.Uninspectable(t"the compiler reported errors")))

    found.or:
      mismatch.lay(abort(Launch.Error(Error.Reason.NoMain))): signature =>
        abort(Launch.Error(Error.Reason.WrongSignature(signature)))

  // Calls `main` in-process, as fume runs a suite: the compiled classes and the header's entries
  // in a classloader whose parent is Fever's own, so that a script shares Fever's Soundness and —
  // essentially — its `pyrocosm.Runtime` class; and with the JVM's streams diverted to the
  // client's for the duration, so that output which bypasses `Runtime` still reaches the user.
  def run(out: Path on Linux, entries: List[Text], entryPoint: Text, runtime: Runtime)
    ( using Stdio )
  :   Unit =

    val extra: List[Classpath.Entry.Directory | Classpath.Entry.Jar] = entries.map: entry =>
      if entry.ends(t".jar") then Classpath.Entry.Jar(entry) else Classpath.Entry.Directory(entry)

    val classpath = LocalClasspath((Classpath.Entry.Directory(out.encode) :: extra)*)
    val parent = Classloader[Launch.type]
    val loader = classpath.classloader(Classloader.Delegation.Deferential, parent)

    loader.on(entryPoint).let: moduleClass =>
      val instance: Any = moduleClass.getField("MODULE$").nn.get(null).nn
      val method = moduleClass.getMethod("main", classOf[Runtime]).nn

      Stdio.divert(summon[Stdio]):
        loader.use(method.invoke(instance, runtime))
