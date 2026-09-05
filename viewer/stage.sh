#!/usr/bin/env bash
# Puts the viewer where everything that serves or publishes it looks: build/viewer.
#
# The export in `viewer/out/` is committed, like the generated protobuf sources
# are, so that nobody downstream of a developer needs npm (D10). This script is
# the one place that knows how to go from that committed export to a servable
# directory — `export.sh` after it builds, `.devcontainer/start.sh` when a
# container comes up, `publish.sh` when a template is refreshed, and `dist.sh`
# through it when a release is cut. Four callers, one definition, so a student's
# viewer cannot differ from the maintainer's by a step somebody forgot.
#
# It never runs a build. `viewer/out/` missing is a developer's problem with a
# developer's fix, and guessing that npm is available is exactly the assumption
# this file exists to remove.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -d viewer/out/_next ]; then
  echo "viewer/out is not a built export — run ./viewer/export.sh (needs npm)." >&2
  exit 1
fi

rm -rf build/viewer
mkdir -p build
cp -r viewer/out build/viewer

# An empty index rather than none: the picker fetches it on load, and a 404 is a
# harder thing for it to say something useful about than an empty list. Traces
# are swept in later, by `viewer/traces.sh`, against whatever directory the
# person serving it points at.
mkdir -p build/viewer/traces
echo '{"runs": []}' > build/viewer/traces/index.json
