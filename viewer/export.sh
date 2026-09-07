#!/usr/bin/env bash
# Builds the viewer and puts the static export where the repo serves it from.
#
# The export is committed, like the generated protobuf sources are, because a
# student must never need npm (D10). Whoever changes the viewer runs this; nobody
# else runs anything.
#
# **The export ships with no traces in it.** Runs are swept in by `serve.sh` at
# the moment somebody serves it, against whatever directory they point at — so
# what is built here is the application and nothing else. Baking a trace set into
# the artifact would mean shipping the gallery, or shipping whatever the person
# who last exported it happened to have lying around.
set -euo pipefail
cd "$(dirname "$0")"
npx next build

echo "exported -> viewer/out  (no traces in it; ./viewer/serve.sh adds them)"
