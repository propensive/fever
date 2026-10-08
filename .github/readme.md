# Fever

__Fever__ is the Scala compiler service for [Fury](https://github.com/propensive/fury) and for
editors: the compiler wrapper that Fury delegates Scala edges to, and a long-running service for
one-off compilations and other source-code operations. It implements the `lira.tool` contract of
[LIRA](https://github.com/propensive/lira), one release per Scala version. Its design is
[`design/fever.md`](https://github.com/propensive/lira/blob/main/design/fever.md) in the lira
repository.

Fever runs as an [Ethereal](https://soundness.dev/ethereal/) daemon and is a
[Pyrocosm](https://github.com/propensive/pyrocosm) tool, so the subcommands and configuration
files every Pyrocosm tool shares are fever's too.

Fever's first working feature is **scripts**: a single file whose TEL header precedes its Scala
source, executable directly through an interpreter directive. Its design is `fever.md` §6a and
`fury.md` §12a in lira; the header's schema is
[`script.schema.tel`](../src/core/resources/fever/script.schema.tel).

```text
#!/usr/bin/env fever
tel 1.0

language scala
  flag -deprecation

##

def main(using Runtime): Unit =
  Out.println(t"Hello world")
```

The header is in Fury's vocabulary, so the same file will run under `fury` unchanged. `language`
names the body's form (`scala`), with the compiler's flags beneath it; `classpath` entries,
relative to the script's directory, join the compile and run classpath. Every problem with a
header is reported at once, as `file:line: error: ...`. The body is compiled against Soundness
and Pyrocosm's `Runtime`, both already imported; it must define a top-level
`def main(using Runtime): Unit`, which is checked on the compiled TASTy. `Runtime` is the
script's arguments, environment, working directory and standard streams, and is itself the
`Stdio`, `Environment` and `WorkingDirectory` a body needs, so `Out.println` works with nothing
imported. A script Fever has seen before — same file, same classpath — runs from its cached
classes with nothing compiled.

A script is run by its shebang (`./hello`), as `fever ./hello`, or as `fever run hello`: a bare
operand is a script only when it contains a `/`, so a file named like a subcommand is reached
through `run` or as `./name`. Arguments after the file are the script's.

`compile` and `lsp` are not yet implemented.

## Usage

```sh
./hello arg...     # run a script through its #!/usr/bin/env fever line
fever ./hello      # the same, by hand; the operand must contain a /
fever run hello    # the same, unambiguously
fever run -f hello # recompile even if the script is cached
fever compile      # compile Scala sources once, as scalac would (not yet implemented)
fever lsp          # run the language server over stdio (not yet implemented)
fever install      # install tab-completions and the manpage
fever about        # fever's version, and the daemon serving it
fever --version    # the version alone
fever quit         # stop the daemon
```

## Installing

```sh
curl -fsSL https://propensive.dev/fever | sh
```

## Building

The libraries fever builds against (Soundness, Pyrocosm and lira) are pinned in
[`etc/refs`](../etc/refs), and the tools it runs in [`etc/tools`](../etc/tools).

```sh
make sync-deps   # install the pinned releases into ~/.ivy2/local
make fever       # build the native executable for this machine
make install     # copy it to ~/.local/bin
make test        # run the suite with fume
make check       # check the sources with flair
```

A release is cut by a signed tag on a commit CI has passed; the tag is the only place the
version is declared:

```sh
git tag -s X.Y.Z && git push --tags
```
