#!/usr/bin/env bash
# Publishes losim into an assignment repository, as a library.
#
# **Not a student command.** Students never run this and never see it; it is how
# a course maintainer refreshes the template between terms. It is written in
# shell for the same reason `build.sh` is — it copies files and does nothing a
# person has to reason about.
#
#   ./publish.sh ../BCS-DS-Assignment-1
#
# What it copies, and why each is copied rather than fetched:
#
#   lib/     the simulator as a jar, the gRPC jars it needs, and protoc for the
#            two architectures a container can be. An assignment is a **GitHub
#            template**: a student presses "Use this template" and gets a repo
#            that has to work on its own, with no clone step, no network at
#            setup and no version of losim to be behind.
#   viewer/  the exported application. Committed for the same reason the
#            generated protobuf sources are: npm is a developer's dependency and
#            never a student's (D10).
#   docs/    the manual, so it is beside the work rather than on a website that
#            may not be reachable from a locked-down machine.
#
# What it deliberately does not copy: the gallery. Those traces exist to test
# losim and shipping them would put a hundred worked examples in front of a
# student whose own first run is one line among them.
#
# ## More than one lab in an assignment
#
#   ./publish.sh --lib-only <path>
#
# Skips viewer/ and docs/ — writes lib/ alone, into whatever you point it at.
# For when a course wants two labs that cannot take each other down by failing
# to compile: `Experiments.show()` always resolves its own viewer by looking
# for `viewer/` beside its lab's own root (`Serve.siteIn`, and `show()` never
# overrides it), so each lab needs *something* called `viewer/` next to it —
# but nothing says that has to be a second copy. Publish once in full, into
# wherever the shared viewer and docs live, then `--lib-only` into each lab and
# symlink the rest:
#
#   ./publish.sh ../assignment/shared
#   ./publish.sh --lib-only ../assignment/1-independent
#   ./publish.sh --lib-only ../assignment/2-distributed
#   ln -s ../shared/viewer ../assignment/1-independent/viewer
#   ln -s ../shared/docs   ../assignment/1-independent/docs
#   ln -s ../shared/viewer ../assignment/2-distributed/viewer
#   ln -s ../shared/docs   ../assignment/2-distributed/docs
#
# The symlinks are the assignment's own to make and commit — this script only
# ever writes real files, into whichever one directory you point it at.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

LIB_ONLY=0
if [ "${1:-}" = "--lib-only" ]; then LIB_ONLY=1; shift; fi

TARGET="${1:?usage: ./publish.sh [--lib-only] <path to the assignment repository>}"
TARGET="$(cd "$TARGET" && pwd)"
[ "$TARGET" != "$ROOT" ] || { echo "that is this repository" >&2; exit 1; }

echo "Building the simulator…"
./build.sh > /dev/null

if [ "$LIB_ONLY" -eq 0 ]; then
  # From the committed export, always — not from whatever build/viewer happens to
  # hold, and never by running npm. Publishing a template is a thing a maintainer
  # does on their own machine and a thing CI does on a tag, and only one of those
  # has node installed.
  echo "Staging the viewer…"
  ./viewer/stage.sh
fi

# The two architectures a container is. Not the mac binaries: students open this
# in a devcontainer or a Codespace, both of which are Linux, and 36 MB of
# binaries nothing in the container can execute is 36 MB in every fork.
BINS=(protoc-linux-x86_64 protoc-gen-grpc-java-linux-x86_64
      protoc-linux-aarch_64 protoc-gen-grpc-java-linux-aarch_64)

echo "Publishing into ${TARGET}…"
if [ "$LIB_ONLY" -eq 0 ]; then
  rm -rf "$TARGET/lib" "$TARGET/viewer" "$TARGET/docs"
else
  rm -rf "$TARGET/lib"
fi
mkdir -p "$TARGET/lib/jars" "$TARGET/lib/bin" "$TARGET/lib/prices" "$TARGET/lib/test-jars"

cp build/losim.jar          "$TARGET/lib/losim.jar"
cp vendor/jars/*.jar        "$TARGET/lib/jars/"
cp vendor/test-jars/*.jar   "$TARGET/lib/test-jars/"
cp prices/*.yaml            "$TARGET/lib/prices/"
for b in "${BINS[@]}"; do cp "vendor/bin/$b" "$TARGET/lib/bin/$b"; chmod +x "$TARGET/lib/bin/$b"; done
cp -r vendor/LICENSES       "$TARGET/lib/LICENSES"

# Which losim this is, where a person can read it without unpacking a jar.
# `losim update` compares against the one stamped inside losim.jar; this is
# the same number, for eyes rather than for code.
tr -d "[:space:]" < VERSION > "$TARGET/lib/version"

if [ "$LIB_ONLY" -eq 0 ]; then
  # The application, and nothing that was ever run through it.
  mkdir -p "$TARGET/viewer"
  ( cd build/viewer && tar -c --exclude traces . ) | ( cd "$TARGET/viewer" && tar -x )

  cp -r docs "$TARGET/docs"
fi

cat > "$TARGET/lib/README.md" <<'MSG'
# lib — the simulator

Nothing in here is yours to edit, and nothing in here is marked. It is losim,
published as a library: a jar, the gRPC jars it needs, and the protobuf compiler
for the two architectures this repository can be opened on.

It is committed rather than fetched so that your copy of this assignment works
on its own — the same code, the same versions, the same numbers, whether you
open it in a Codespace, in a container on your laptop, or in six months.

Refreshed by the course with `./publish.sh` in the losim repository, which
**deletes this whole directory and writes it again**. So a file you add here
survives until the next refresh and then is gone, without a warning and without
a conflict — which is the one way this folder can cost you an afternoon.

Your own dependencies therefore go somewhere you own. A folder of your own beside
this one, listed in `.vscode/settings.json`:

    "java.project.referencedLibraries": [
      "lib/jars/*.jar", "lib/losim.jar",
      "deps/*.jar"
    ]

Java tooling reads that, so a jar in `deps/` is on the classpath for anything you
compile, run or debug from the editor.

Note what it is *not* on: the classpath losim builds for a simulated run, which
is `lib/` and nothing else. That is deliberate. Inside a run the network is
simulated — the latency, the zone crossings, the partitions and what the egress
costs — and a handler that opened a real socket would go around all of it and be
billed for none of it. Ordinary Java on your own classpath is one thing; a
machine in a fleet is another.
MSG

printf 'published:\n'
if [ "$LIB_ONLY" -eq 0 ]; then
  du -sh "$TARGET/lib" "$TARGET/viewer" "$TARGET/docs" | sed 's/^/  /'
else
  du -sh "$TARGET/lib" | sed 's/^/  /'
  [ -e "$TARGET/viewer" ] || echo "  no viewer/ here yet — this lab needs one, real or a symlink to a shared one"
  [ -e "$TARGET/docs" ]   || echo "  no docs/ here yet — same"
fi
