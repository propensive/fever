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

Fever is at its first step: the command line and the daemon exist, and `compile` and `lsp` are
not yet implemented.

## Usage

```sh
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
make check       # check the sources with flair
```

A release is cut by a signed tag, after bumping `feverVersion` in `build.mill` and merging it:

```sh
git tag -s X.Y.Z && git push --tags
```
