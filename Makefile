# Publish the library to the local ~/.ivy2 (the launcher resolves `fever-core` from there; burdock
# will NOT externalize a locally-published copy unless its bytes match a release asset).
publishLocal:
	./mill fever.__.publishLocal

# Build the invocation-point `launcher` module as a plain (clean, no shell-preamble) assembly JAR.
# `launcher` depends on fever-core as a PUBLISHED coordinate resolved from ~/.ivy2/local, so the
# library is published there FIRST, and `clean fever.launcher` for the same reason: the coordinate
# is fixed, so Mill's cached resolution would not notice the fresh publish.
assembly: publishLocal
	./mill clean fever.launcher
	./mill fever.launcher.assembly

# Releases are cut by tagging, not by make. Tag a commit CI has passed, with `git tag -s
# X.Y.Z && git push --tags`: the tag fires .github/workflows/release.yml, which runs the shared
# release.sh in propensive/.github. What this repository needs beyond the common path is declared
# in etc/release. This target survives only to say so.
release:
	@echo "Releases are triggered by tags, not by make. Once CI has passed on the commit:" >&2
	@echo "" >&2
	@echo "    git tag -s X.Y.Z && git push --tags" >&2
	@echo "" >&2
	@echo "See propensive/.github." >&2
	@exit 1

# Repackage the launcher assembly into a self-fetching launcher with Burdock: published
# dependencies become on-demand `Burdock-Require` URLs, matched by SHA-256 against Maven Central
# and, via the `--github` hints, the release assets of these repositories; unpublished ones are
# inlined. fever-core externalizes only once released. Set GITHUB_TOKEN to lift the API rate limit.
fever.jar: assembly
	cp out/fever/launcher/assembly.dest/out.jar fever.jar
	java -cp fever.jar soundness.repackage --github propensive/fever,propensive/lira,propensive/pyrocosm,propensive/soundness,propensive/proscala

# Package the repackaged JAR as a native executable for this machine with the pinned `xek` builder
# (fetched into dist/xek and verified against etc/xek.tsv), requiring Java 25 as releases do.
fever: fever.jar xek-fetch
	dist/xek build --java-min 25 --java 25 fever.jar fever

# Fetch the pinned `xek` builder into dist/xek.
xek-fetch:
	./etc/shared xek-fetch.sh

# Remove-then-copy, NOT a bare `cp`: overwriting the existing file reuses its inode, and macOS
# caches code-signing state per vnode, so every exec of an overwritten launcher is killed until
# the file is replaced. The daemon is stopped first, so the next run starts the new build.
install: fever
	-./fever quit 2>/dev/null || true
	rm -f ${HOME}/.local/bin/fever
	cp fever ${HOME}/.local/bin/

# Install every library pinned in etc/refs — releases and snapshots alike, transitively — into the
# local ivy repository, as CI does, so the build resolves exactly the pinned jars rather than
# whatever a sibling checkout's `publishLocal` last installed under the same version.
sync-deps:
	./etc/shared sync-deps.sh

# Check every source against Consequent Style and the project's own rules with flair (the release
# pinned in etc/tools; `make tools` installs it), as configured in .pyrocosm/flair/config.tel.
# Findings are warnings, so CI does not run this; PATHS restricts the check to files beneath them.
check:
	flair check $(PATHS)

# Install the commands pinned in etc/tools (fume, flair) through their releases' installers.
tools:
	./etc/shared tools.sh

# Publish HEAD's library as a snapshot — a `snapshot-<hex>` pre-release named by the filtered tree
# of the commit, at version `<next version>-<hex>` — for a dependent repository to pin in its
# etc/refs before the next release. `LOCAL=1` stages and installs without publishing. The last line
# printed is the pin. See snapshot.sh in propensive/.github.
snapshot:
	./etc/shared snapshot.sh fever "$$(./mill show fever.core.publishVersion | tr -d '"')"

# Delete snapshot pre-releases older than DAYS (default 60) days.
snapshot-prune:
	./etc/shared snapshot-prune.sh fever $(DAYS)

# Run the suite with fume (the release pinned in etc/tools; `make tools` installs it), which
# discovers the suites from the test assembly named in .pyrocosm/fume/config.tel. TESTS restricts
# the run, as `make test TESTS='Script*'`.
test:
	./mill fever.test.assembly
	fume run -c out/fever/test/assembly.dest/out.jar $(TESTS)

# The same suite without fume, through the plain-`java` entry point.
test-plain:
	./mill fever.test.assembly
	java -cp out/fever/test/assembly.dest/out.jar fever.runTests

dev:
	./mill -w fever.core.compile

.PHONY: publishLocal assembly release xek-fetch install sync-deps check tools snapshot snapshot-prune test test-plain dev
