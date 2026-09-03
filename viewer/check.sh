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

if [ ! -d build/viewer/traces ]; then
  echo "no traces yet — run ./viewer/traces.sh first" >&2
  exit 1
fi

fail=0
run() {
  echo
  node "viewer/spikes/$1.ts" || fail=1
}

run s1-glyphs    # do the glyph paths match the Python, character for character
run s2-parity    # does the layout port compute the same layout, on every trace
run s7-ledger    # does the accruing ledger close on `losim bill`, to the rappen
run s3-s8-cost   # is a frame cheap enough to be a frame, at the largest fleet
run s9-pace      # is nothing on screen for less than a second, on every trace
run s10-console  # do the console's views render, and does any ruler move under the clock
run s11-author   # does what the designer writes load, and is what it must refuse refused

echo
if [ "$fail" -eq 0 ]; then
  echo "viewer: all checks pass"
else
  echo "viewer: something differs — see above" >&2
fi
exit "$fail"
