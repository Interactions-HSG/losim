#!/usr/bin/env bash
# Builds the losim library into a jar. Labs compile against the jar and the
# vendored gRPC jars — students never see, and cannot edit, the simulator sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

CP=$(ls vendor/jars/*.jar | tr '\n' ':')
rm -rf build/classes && mkdir -p build/classes
javac --release 21 -Xlint:-this-escape -cp "$CP" -d build/classes \
      $(find losim/src -name '*.java')
jar --create --file build/losim.jar -C build/classes .

echo "built build/losim.jar"
