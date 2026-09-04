#!/usr/bin/env bash
# Everything about the viewer that a machine can decide, in one command.
#
# Five of these are arithmetic and belong here — including the console's own,
# which renders every view against every trace at six points on the clock. The
# rest are about what a browser does — do the glyphs *look* right, does the MP4
# play in Keynote, is a 25-machine fleet legible, does the export serve with no
# npm — and a browser is where they are answered: `./viewer/serve.sh`.
#
#   ./viewer/check.sh
#
# Needs the traces to have been built (`./viewer/traces.sh`), because these check
# the port and the bill against runs that actually happened rather than against
# fixtures that agree with them by construction.
set -euo pipefail
cd "$(dirname "$0")/.."

# The index, not the directory: `traces.sh` writes an empty `{"runs": []}` index
# before it has anything to put in it, so a directory that exists proves nothing.
# Checked against the index's own contents, because an empty run set is what two
# of these checks below quietly pass on and two others fail on for reasons that
# read like a regression and are not one.
if ! grep -q '"runs":[[:space:]]*\[[[:space:]]*{' build/viewer/traces/index.json 2>/dev/null; then
  echo "no traces yet — run ./viewer/traces.sh first" >&2
  exit 1
fi

fail=0
run() {
  echo
  node "viewer/checks/$1.ts" || fail=1
}

run glyphs   # do the glyph paths match the Python, character for character
run parity   # does the layout port compute the same layout, on every trace
run ledger   # does the accruing ledger close on `losim bill`, to the rappen
run cost     # is a frame cheap enough to be a frame, at the largest fleet
run pace     # is nothing on screen for less than a second, on every trace
run console  # do the console's views render, and does any ruler move under the clock
run author   # does what the designer writes load, and is what it must refuse refused
run stops    # is every kind a trace carries reachable, or named as furniture

echo
if [ "$fail" -eq 0 ]; then
  echo "viewer: all checks pass"
else
  echo "viewer: something differs — see above" >&2
fi
exit "$fail"
