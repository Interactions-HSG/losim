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
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

TARGET="${1:?usage: ./publish.sh <path to the assignment repository>}"
TARGET="$(cd "$TARGET" && pwd)"
[ "$TARGET" != "$ROOT" ] || { echo "that is this repository" >&2; exit 1; }

echo "Building the simulator…"
./build.sh > /dev/null

echo "Exporting the viewer…"
[ -d build/viewer/_next ] || ./viewer/export.sh > /dev/null

# The two architectures a container is. Not the mac binaries: students open this
# in a devcontainer or a Codespace, both of which are Linux, and 36 MB of
# binaries nothing in the container can execute is 36 MB in every fork.
BINS=(protoc-linux-x86_64 protoc-gen-grpc-java-linux-x86_64
      protoc-linux-aarch_64 protoc-gen-grpc-java-linux-aarch_64)

echo "Publishing into ${TARGET}…"
rm -rf "$TARGET/lib" "$TARGET/viewer" "$TARGET/docs"
mkdir -p "$TARGET/lib/jars" "$TARGET/lib/bin" "$TARGET/lib/prices" "$TARGET/lib/test-jars"

cp build/losim.jar          "$TARGET/lib/losim.jar"
cp vendor/jars/*.jar        "$TARGET/lib/jars/"
cp vendor/test-jars/*.jar   "$TARGET/lib/test-jars/"
cp prices/*.yaml            "$TARGET/lib/prices/"
for b in "${BINS[@]}"; do cp "vendor/bin/$b" "$TARGET/lib/bin/$b"; chmod +x "$TARGET/lib/bin/$b"; done
cp -r vendor/LICENSES       "$TARGET/lib/LICENSES"

# The application, and nothing that was ever run through it.
mkdir -p "$TARGET/viewer"
( cd build/viewer && tar -c --exclude traces . ) | ( cd "$TARGET/viewer" && tar -x )

cp -r docs "$TARGET/docs"

cat > "$TARGET/lib/README.md" <<'MSG'
# lib — the simulator

Nothing in here is yours to edit, and nothing in here is marked. It is losim,
published as a library: a jar, the gRPC jars it needs, and the protobuf compiler
for the two architectures this repository can be opened on.

It is committed rather than fetched so that your copy of this assignment works
on its own — the same code, the same versions, the same numbers, whether you
open it in a Codespace, in a container on your laptop, or in six months.

Refreshed by the course with `./publish.sh` in the losim repository.
MSG

printf 'published:\n'
du -sh "$TARGET/lib" "$TARGET/viewer" "$TARGET/docs" | sed 's/^/  /'
