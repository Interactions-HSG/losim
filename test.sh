#!/usr/bin/env bash
# The framework's own tests: the simulator, then the viewer.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

./build.sh > /dev/null
rm -rf build/test-classes && mkdir -p build/test-classes
javac --release 21 -cp build/losim.jar -d build/test-classes $(find losim/test -name '*.java')
java -cp "build/losim.jar:build/test-classes" Tests
JAVA=$?

# the viewer needs traces to draw; make sure some exist
if [ -z "$(ls build/*.json 2>/dev/null || true)" ]; then
  ./run-all.sh > /dev/null
fi
python3 view/tests/test_viewer.py
PY=$?

exit $(( JAVA + PY ))
