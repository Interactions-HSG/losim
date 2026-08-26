#!/usr/bin/env bash
# Runs once when the container is created. Leaves a Codespace ready to build and
# check losim — the same commands, producing the same numbers, as on a laptop.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "building losim..."
./build.sh

echo
echo "ready. Try:"
echo "  ./build.sh    the simulator, into build/losim.jar"
echo "  ./check.sh    losim's own checks — every phase's acceptance criteria"
