#!/usr/bin/env bash
# Puts the viewer on a port. Open it in your own browser.
#
#   ./viewer/serve.sh                    your runs, from build/traces
#   ./viewer/serve.sh ~/work/runs        any directory of traces, anywhere
#   ./viewer/serve.sh --gallery          ...and the gallery, for whoever writes losim
#   ./viewer/serve.sh --dev              the Next dev server, for whoever changes it
#
# **losim is a library.** Your own project builds however it builds, puts
# `losim.jar` and the vendored gRPC jars on its classpath, and writes a trace
# wherever it likes. Nothing here needs to know how you compiled it — the only
# thing shared between your system and this viewer is *a folder with traces in
# it*, and every argument above is a way of naming that folder.
#
# The first form is the one that matters: it is a plain static server against
# the committed static export, with no node involved at all. That is D10 — the
# same command works in the devcontainer, on a laptop and in a Codespace, with no
# setup step and no first-run download.
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-8000}"

if [ "${1:-}" = "--dev" ]; then
  exec npx next dev --port "$PORT"
fi

if [ ! -d ../build/viewer ]; then
  echo "no export yet — run ./viewer/export.sh (needs npm, once)" >&2
  exit 1
fi

# Swept now rather than at export time, so the same built application serves
# whatever run you point it at.
./traces.sh "$@" || true
echo
echo "viewer on http://localhost:$PORT/"
# Served by losim itself, not by `python3 -m http.server`: that would be one more
# language in a repository that needs none, and losim already has to be able to
# serve this, because that is what a student's lab does.
exec java -cp ../build/losim.jar losim.cli.Main serve \
     --root .. --site build/viewer --runs build/viewer/traces --port "$PORT" --no-open
