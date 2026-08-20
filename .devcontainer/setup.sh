#!/usr/bin/env bash
# Runs once when the container is created. Leaves a Codespace ready to run labs.
set -euo pipefail
cd "$(dirname "$0")/.."

# This image ships python3-minimal (no standard library — `import json` fails)
# and an unsigned yarn apt source that breaks apt-get update. Both are fixed
# here because a devcontainer feature runs too early to fix either.
if command -v sudo >/dev/null && ! python3 -c 'import json, ensurepip' >/dev/null 2>&1; then
  echo "completing the Python install..."
  sudo rm -f /etc/apt/sources.list.d/yarn.list
  sudo apt-get update -qq >/dev/null 2>&1 || true
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv >/dev/null 2>&1 || true
fi

echo "building the losim framework..."
./build.sh

echo "building the labs..."
for lab in hello_ring mapreduce vector_clocks bigdata; do
  ./lab.sh "$lab" >/dev/null && echo "  $lab ok"
done

echo
echo "ready. Try:"
echo "  ./run-all.sh                 build, run every lab, draw everything"
echo "  ./serve.sh                   the studio — watch your runs on port 8000"
echo "  ./view.sh doctor --install   install the manim sidecar, for video"
echo "  ./test.sh                    the framework's own tests"
