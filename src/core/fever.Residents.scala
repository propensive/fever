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

import pyrocosm.Runtime  // not `java.lang`'s, which the root import would otherwise supply

// The scripts the daemon keeps loaded between invocations: those whose header says `resident`
// (script.schema.tel). Fever's own JVM is resident already; what an ordinary script loses between
// runs is its classloader — its classes, their JIT state and every top-level `val` and `object`
// — and that is what a resident keeps. The programming model is unchanged: `main` runs once per
// invocation with that invocation's `Runtime`, over state which persists.
//
// A resident is identified by its script's real path, and holds the compilation key (which hashes
// the whole file) and the modification times of its classpath entries: a resident which no longer
// matches what the file now says is unloaded on the next invocation, which is how a change to
// the script shuts it down. `fever ps` lists them, `fever stop` unloads them, and one idle for
// longer than `idle` is unloaded by the next sweep.
object Residents:
  // An hour's idleness, after which a forgotten resident is unloaded rather than pinning the
  // daemon's heap indefinitely.
  val daemon: Residents = Residents(60L*60*1000)

  def now(): Long = java.lang.System.currentTimeMillis()

  // The classpath entries with their modification times, which is how a rebuilt jar is noticed
  // (the compilation key hashes the entries' paths, not their contents). A directory's time
  // changes when its immediate contents do, which is an approximation; an entry which cannot be
  // stated reads as zero, and is noticed when it appears.
  def stamp(entries: List[Text]): List[(Text, Long)] =
    entries.map: entry =>
      val time: Long =
        try java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(entry.s).nn).nn.toMillis
        catch case error: Exception => 0L

      (entry, time)

  // A script's real path — symlinks and `/tmp` vs `/private/tmp` resolved — so that a script
  // reached by two names is one resident, and `fever stop` finds it by either.
  def canonical(path: Text): Text =
    try Text(java.nio.file.Path.of(path.s).nn.toRealPath().nn.toString)
    catch case error: Exception => path

  // One resident script: where it is, what was loaded, and its history. Invocations are
  // serialised through the lock: `main` runs over shared top-level state, and two at once would
  // need the script to be written for it. `last` and `runs` are read without it by `fever ps`.
  class Resident
    ( val path:    Text,
      val key:     Text,
      val stamps:  List[(Text, Long)],
      program:     Launch.Program,
      val since:   Long ):

    private val lock: Mutex = Mutex()
    @volatile private var last0: Long = since
    @volatile private var runs0: Int = 0

    def last: Long = last0
    def runs: Int = runs0

    def invoke(runtime: Runtime, now: Long)(using Stdio): Unit = lock:
      last0 = now
      runs0 += 1
      program.invoke(runtime)

    def unload(): Unit = program.unload()

class Residents(idle: Long):
  import Residents.Resident

  // A stdlib map under a mutex: the registry is tiny, and read and written from the daemon's
  // invocation threads.
  private val lock: Mutex = Mutex()

  private val residents: scala.collection.mutable.HashMap[Text, Resident] =
    scala.collection.mutable.HashMap()

  // The resident for the path, if it is still what the file (`key`) and its classpath (`stamps`)
  // now say; one which is not is unloaded first, so that a miss is also an eviction.
  def lookup(path: Text, key: Text, stamps: List[(Text, Long)]): Optional[Resident] = lock:
    Optional(residents.get(path).orNull).let: resident =>
      if resident.key == key && resident.stamps == stamps then resident
      else
        resident.unload()
        residents.remove(path)
        Unset

  // Registers a freshly-loaded resident, unloading any the path had.
  def admit(resident: Resident): Resident = lock:
    residents.remove(resident.path).foreach(_.unload())
    residents.update(resident.path, resident)
    resident

  def all: List[Resident] = lock(residents.values.toList.sortBy(_.path.s).to(List))

  // Unloads the resident for the path, if there is one.
  def stop(path: Text): Boolean = lock:
    residents.remove(path) match
      case Some(resident) => resident.unload() yet true
      case None           => false

  def stopAll(): Int = lock:
    val count = residents.size
    residents.values.foreach(_.unload())
    residents.clear()
    count

  // Unloads every resident idle for longer than the limit, returning how many.
  def sweep(now: Long): Int = lock:
    val stale = residents.values.filter(now - _.last > idle).toList

    stale.foreach: resident =>
      resident.unload()
      residents.remove(resident.path)

    stale.size
