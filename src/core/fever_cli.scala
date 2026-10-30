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

// `pyrocosm.Tool` is imported explicitly, so that it outranks the `Tool` the `soundness.*`
// wildcard exports (anthology's).
import backstops.silentBackstop
import interpreters.posixInterpreter
import pyrocosm.Tool
import systems.javaBaseSystem
import threads.platformThreads

// Fever as a Pyrocosm tool (fever.md): the Scala compiler service — a resident daemon that
// performs compilations and other source-code operations, `scalac` but faster, and in time most
// of an LSP server's work. Everything that knows what a `.scala` file is lives here, never in
// Fury, which reaches Fever only through the `lira.tool` contract. One Fever is released per
// Scala version, against that compiler's API. Its first working feature is scripts (fever.md
// §6a): a single file whose TEL header precedes its Scala source, run directly through
// `#!/usr/bin/env fever`. A script whose header says `resident` stays loaded in the daemon
// between runs (Residents); `ps` and `stop` show and unload them.
val Fever: Tool =
  Tool
    ( t"fever",
      prose = t"Fever is the Scala compiler service for Fury and for editors: a resident daemon " +
        t"that compiles Scala through the lira.tool contract, one release per Scala " +
        t"version, and runs single-file Scala scripts." )

object CompileFailed extends Status(1, t"the script did not compile")
object UsageError extends Status(2, t"the command line was not understood")
object NoScript extends Status(3, t"the script could not be read")
object InvalidHeader extends Status(4, t"the script's header is not valid")
object NoEntryPoint extends Status(5, t"the script defines no `main(using Runtime): Unit`")
object ScriptFailed extends Status(6, t"the script threw an exception")
object Unimplemented extends Status(7, t"this subcommand is not yet implemented")
object NotResident extends Status(8, t"the script is not resident")

object ui:
  val Run = Subcommand("run", "compile a script, unless it is cached, and run it")
  val Compile = Subcommand("compile", "compile Scala sources once, as scalac would")
  val Lsp = Subcommand("lsp", "run the language server over stdio")
  val Ps = Subcommand("ps", "list the resident scripts the daemon has loaded")
  val Stop = Subcommand("stop", "unload a resident script, or all of them")
  val Force = Flag[Unit]("force", false, List('f'), "recompile the script even if it is cached")
  val All = Flag[Unit]("all", false, List('a'), "unload every resident script")

// The arguments which are not flags: the script, and after it, the script's own arguments.
private def operands(arguments: List[Argument]): List[Argument] =
  arguments.filter(!_().starts(t"-"))

// In an object, for its `uses` clause: `cli` supervises under the global `threads`, and watches
// its state files with the default `Watcher`, and both are capabilities a definition at the top
// level may not reach undeclared. The launcher's `fever.fever` calls `Client.run()`.
object Client uses parasite.threads, surveillance.Watcher:
  // The executive supervises under the global `threads`, which its type records, while `cli`
  // takes a pure one; `Client` declares that use itself, so the capture is vouched away here.
  given executive: exoskeleton.executives.CompletionsExecutive =
    scala.caps.unsafe.unsafeAssumePure(executives.completionsExecutive)

  def run(): Unit =
    cli:
      Fever.standard:
        // `Pathname` resolves a path argument against the INVOKING shell's directory, not the
        // daemon's, and registers filename completions for it.
        import workingDirectories.daemonClientWorkingDirectory

        arguments match
          case ui.Run() :: rest =>
            // Read outside `execute`, where completion mode registers it.
            val force = ui.Force().present

            operands(rest) match
              case Pathname(file) :: scriptArguments =>
                script(file, scriptArguments.map(_()), force)

              case _ =>
                usage(t"fever run [--force] <file> [arguments...]")

          case ui.Ps() :: _ => ps()

          // The script's classes and state are unloaded from the daemon; the next invocation
          // compiles nothing (the classes are cached) but loads afresh.
          case ui.Stop() :: rest =>
            val all = ui.All().present

            operands(rest) match
              case Pathname(file) :: _ => stop(file)
              case _ if all            => stopAll()
              case _                   => usage(t"fever stop <file> | fever stop --all")

          // The shebang form: `#!/usr/bin/env fever` runs `fever <path> [arguments...]`, and a path
          // as the shell resolves it — `./hello`, `scripts/hello`, `/usr/local/bin/hello` — always
          // contains a `/`. A bare word never does, so it is always a subcommand, and a script in
          // the working directory named like one is reached as `./name` or through `run`.
          case (argument @ Argument(head)) :: scriptArguments if head.contains(t"/") =>
            argument match
              case Pathname(file) => script(file, scriptArguments.map(_()), false)
              case _              => usage(t"fever <file> [arguments...]")

          case ui.Compile() :: _ => unimplemented(t"compile")
          case ui.Lsp() :: _     => unimplemented(t"lsp")
          case _                 => help()

// Runs a script with the invocation's streams, environment and directory — which the daemon's
// own are not — projected once, here, from the `Invocation`.
private def script(file: Path on Local, arguments: List[Text], force: Boolean)
  ( using Cli, Monitor )
:   Execution =

  execute:
    val invocation = summon[Invocation]
    given Stdio = invocation.stdio
    given Environment = invocation.environment
    given WorkingDirectory = invocation.workingDirectory

    // Every failure `Scripts.run` anticipates is a status; anything else is a bug in Fever, and
    // is reported with its trace rather than vanishing into the daemon.
    try Scripts.run(file.encode, arguments, force) catch case error: Throwable =>
      Err.println(t"fever: $file: ${Text(error.toString)}")
      error.printStackTrace(invocation.stdio.err)
      ScriptFailed

// The `execute` blocks live in methods of their own, as `usage` does, rather than inline in the
// dispatch: expanded inside `standard`, a block's `Invocation` is a fresh capability the pure
// `Stdio` that `Out.println` wants cannot be projected from under capture checking.
private def unimplemented(subcommand: Text)(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    Out.println(t"fever $subcommand is not yet implemented")
    Unimplemented

private def ps()(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    given Environment = summon[Invocation].environment
    Scripts.list()
    Exit.Ok

private def stop(file: Path on Local)(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    // `Exit.Ok` is named in full: in a type position the bare name resolves through soundness's
    // export, a different path from the term's, and the two singletons do not unify.
    val outcome: rudiments.Exit.Ok.type | NotResident.type =
      if Scripts.stop(file.encode) then rudiments.Exit.Ok else NotResident

    outcome

private def stopAll()(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    Scripts.stopAll()
    Exit.Ok

private def help()(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    Out.println(t"Usage: fever <subcommand>")
    Out.println(t"       fever <file> [arguments...]")
    Out.println(t"")
    Out.println(t"  run        compile a script, unless it is cached, and run it")
    Out.println(t"  compile    compile Scala sources once, as scalac would")
    Out.println(t"  lsp        run the language server over stdio")
    Out.println(t"  ps         list the resident scripts the daemon has loaded")
    Out.println(t"  stop       unload a resident script, or all of them")
    Out.println(t"  about      show this tool's name, version and daemon")
    Out.println(t"  install    install shell tab-completions and the manpage")
    Out.println(t"  quit       stop the background daemon")
    UsageError

private def usage(synopsis: Text)(using Cli): Execution =
  execute:
    given Stdio = summon[Invocation].stdio
    Err.println(t"fever: usage: $synopsis")
    UsageError
