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
import filesystemBackends.javaBaseFilesystem
import filesystemOptions.deleteRecursively
import filesystemOptions.dereferenceSymlinks
import filesystemTraversal.preOrderTraversal
import palettes.solarizedDarkGaugePalette
import pyrocosm.Runtime  // not `java.lang`'s, which the root import would otherwise supply
import pyrocosm.{Block, Inline, TelSyntax, TerminalRenderer, Tone}
import systems.javaBaseSystem
import tableStyles.thickTableStyle
import textMetrics.uniformMetric
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

    // Every invocation is the occasion to unload residents nobody has used for a while.
    Residents.daemon.sweep(Residents.now())

    recover:
      case error: Path.Error =>
        Err.println(t"fever: $path: ${error.message.text}")
        NoScript

      case error: Io.Error =>
        Err.println(t"fever: $path: ${error.message.text}")
        NoScript

      case error: Script.Error =>
        report(path, safely(path.as[Path on Linux].read[Text]), error)
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
        val key = Compile.key(text, entries)
        val out = Compile.cache(key)

        // The whole-file fast path (fury.md §12a): a script Fever has compiled before, against
        // the same classpath, runs from its cached classes with nothing compiled.
        def cached: Boolean = !force && Compile.marker(out).existent()

        if parsed.header.resident then
          val real = Residents.canonical(file.encode)
          val stamps = Residents.stamp(entries)

          // `--force` unloads as well as recompiles: the resident is the cached classes, loaded.
          if force then Residents.daemon.stop(real)

          // A resident which still matches the file runs at once; otherwise the file is compiled
          // unless cached, loaded, and kept.
          Residents.daemon.lookup(real, key, stamps).let(invoke(_, arguments)).or:
            if cached || compile(file, parsed, entries, out) then
              val resident =
                Residents.Resident(real, key, stamps, program(out, entries), Residents.now())

              invoke(Residents.daemon.admit(resident), arguments)
            else
              CompileFailed

        else if cached || compile(file, parsed, entries, out) then
          invoke(program(out, entries), arguments)
        else
          CompileFailed

  // The residents, as `fever ps` shows them: one row each, with how long it has been loaded,
  // how long since it last ran, and how many times it has.
  def list()(using Stdio, Environment): Unit =
    val now = Residents.now()
    Residents.daemon.sweep(now)
    val residents = Residents.daemon.all

    if residents.nil then Out.println(t"fever: no scripts are resident") else
      val columns: List[Block.Column] = List(
        Block.Column(Inline.text(t"Script"), sizing = Block.Sizing.Stretch),
        Block.Column(Inline.text(t"Loaded"), sizing = Block.Sizing.Rigid),
        Block.Column(Inline.text(t"Idle"), sizing = Block.Sizing.Rigid),
        Block.Column(Inline.text(t"Runs"), Block.Alignment.End, Block.Sizing.Rigid, numeric = true))

      val rows: List[Block.Row] = residents.map: resident =>
        Block.Row(List(
          Block.Cell(Inline.text(resident.path)),
          Block.Cell(Inline.text(elapsed(now - resident.since))),
          Block.Cell(Inline.text(elapsed(now - resident.last))),
          Block.Cell(Inline.text(resident.runs.show))))

      val table: Block = Block.Table(columns, rows)
      val rendered: List[Teletype] = TerminalRenderer().blocks(List(table), Compile.columns)
      rendered.each: (line: Teletype) => Out.println(line)

  // Unloads the resident for the script, reporting whether there was one.
  def stop(path: Text)(using Stdio): Boolean =
    val real = Residents.canonical(path)
    val stopped = Residents.daemon.stop(real)

    if stopped then Out.println(t"fever: unloaded $real")
    else Err.println(t"fever: $real is not resident")

    stopped

  def stopAll()(using Stdio): Unit =
    val count = Residents.daemon.stopAll()
    val noun = if count == 1 then t"script" else t"scripts"
    Out.println(t"fever: unloaded ${count.show} resident $noun")

  // A duration as `fever about` shows its uptime: `1h 2m 3s`.
  private def elapsed(millis: Long): Text =
    val seconds: Long = millis.max(0L)/1000
    val hours: Long = seconds/3600
    val minutes: Long = seconds%3600/60
    t"${hours.show}h ${minutes.show}m ${(seconds%60).show}s"

  // A header's problems, each a diagnostic: where in the script, what, and the lines of the
  // header around it, highlighted as TEL, with the place marked; the keyword path the
  // rejection names follows, since a validator's rejection says nothing else about its field.
  private def report(path: Text, source: Optional[Text], error: Script.Error)
    ( using Stdio, Environment )
  :   Unit =

    error.reason match
      case Script.Error.Reason.Invalid(rejections) =>
        val lines: List[Block.Line] = source.lay(Nil)(TelSyntax.lines(_))

        val diagnostics: List[Block] =
          rejections.map: rejection =>
            val span: Span = rejection.span
            val location: Block.Location = Block.Location.of(path, span)
            val message: List[Block] = List(Block.paragraph(rejection.message))
            val detail: List[Block] = List(Block.paragraph(t"at ${rejection.pointer}"))

            val excerpt: List[Block] =
              if lines.nil then Nil else
                Block.Note.of(span, Block.Note.Style.Erroneous).lay(Nil): (note: Block.Note) =>
                  List(Block.Code.excerpt(pyrocosm.Language.Tel, lines, List(note)))

            Block.Diagnostic(Tone.Failure, location, message, excerpt, detail)

        TerminalRenderer().blocks(diagnostics, Compile.columns).each: (line: Teletype) =>
          Err.println(line)

      case _ =>
        Err.println(t"fever: $path: ${error.message.text}")

  private def compile
    ( file: Path on Linux, parsed: Script.Parsed, entries: List[Text], out: Path on Linux )
    ( using Stdio, Monitor, Environment )
  :   Boolean raises Compiler.Error raises Async.Error raises Io.Error =

    if out.existent() then out.wipe()
    out.create[Directory](CreateFlag.Parents)

    val flags = parsed.header.language.flag
    val classpath = Compile.classpath(entries)
    val process = Compile.compile(file.encode, parsed.body, flags, classpath, out)
    val result = process.complete()

    // The excerpts' highlighting is a courtesy: a failure in it is reported, and the notices
    // are shown without their excerpts, rather than losing the run.
    val lines: List[Block.Line] =
      if process.notices.nil then Nil else
        try Compile.lines(parsed.body) catch case error: Throwable =>
          Err.println(t"fever: the script could not be highlighted: ${Text(error.toString)}")
          Nil

    process.notices.each: notice => Compile.report(notice, file.encode, lines, Compile.columns)

    result match
      case CompileResult.Success =>
        Compile.marker(out).create[File]()
        true

      case CompileResult.Failure =>
        false

      // The crash's own chain of causes, since a compiler which cannot start — a classpath
      // the daemon's host cannot see, say — says why only there.
      case CompileResult.Crash(error) =>
        def causes(trace: StackTrace): List[Text] =
          t"${trace.className}: ${trace.message.text}" :: trace.cause.lay(Nil)(causes(_))

        Err.println(t"fever: the compiler crashed: ${causes(error).join(t"; caused by ")}")
        false

  // The compiled script, loaded: its entry point found in its TASTy, and its classes and the
  // header's entries in a classloader of their own.
  private def program(out: Path on Linux, entries: List[Text])
    ( using Environment )
  :   Launch.Loaded raises Launch.Error raises Io.Error =

    val tastyFiles = out.descendants.to[List].map(_.encode).filter(_.ends(t".tasty"))

    val classpath: List[Text] = Compile.classpath(entries).entries.map:
      case Classpath.Entry.Directory(path) => path
      case Classpath.Entry.Jar(path)       => path
      case Classpath.Entry.JavaRuntime     => t""

    . filter(_ != t"")

    val entryPoint = Launch.entryPoint(tastyFiles, classpath)

    Launch.load(out, entries, entryPoint).lest:
      val detail = t"the class `$entryPoint` could not be loaded"
      Launch.Error(Launch.Error.Reason.Uninspectable(detail))

  // Runs `main` with a `Runtime` made from the invocation, once (`program`) or again
  // (`resident`).
  private def invoke(program: Launch.Program | Residents.Resident, arguments: List[Text])
    ( using Stdio, Environment, WorkingDirectory )
  :   Exit | ScriptFailed.type =

    val runtime = Runtime(arguments, summon[Environment], summon[WorkingDirectory], summon[Stdio])

    try
      program match
        case resident: Residents.Resident => resident.invoke(runtime, Residents.now())
        case program: Launch.Program      => program.invoke(runtime)

      Exit.Ok
    catch case error: java.lang.reflect.InvocationTargetException =>
      // The script's own exception, unwrapped from reflection's: its trace is what the author
      // needs, and it goes where the script's other stray output does.
      error.getCause.nn.printStackTrace(summon[Stdio].err)
      ScriptFailed
