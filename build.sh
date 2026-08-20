#!/usr/bin/env bash
# Builds the losim library into a jar. Labs compile against the jar only —
# students never see, and cannot edit, the simulator sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
rm -rf build/classes && mkdir -p build/classes
javac --release 21 -Xlint:-this-escape -d build/classes $(find losim/src -name '*.java')
jar --create --file build/losim.jar -C build/classes .
echo "built build/losim.jar"
