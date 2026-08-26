#!/usr/bin/env bash
# losim's own checks. Every phase's acceptance criteria live here and stay here:
# the ones that regress silently are the ones worth keeping.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  P=osx-aarch_64 ;;
  Linux-x86_64)  P=linux-x86_64 ;;
  *) echo "no vendored protoc for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

./build.sh > /dev/null

CP=$(ls vendor/jars/*.jar | tr '\n' ':')
rm -rf build/test-gen build/test-classes
mkdir -p build/test-gen build/test-classes

vendor/bin/protoc-$P --plugin=protoc-gen-grpc-java=vendor/bin/protoc-gen-grpc-java-$P \
  --java_out=build/test-gen --grpc-java_out=build/test-gen \
  -I losim/test/proto losim/test/proto/*.proto

javac -nowarn --release 21 -cp "${CP}build/losim.jar" -d build/test-classes \
      $(find build/test-gen losim/test/src -name '*.java')

suites=("$@"); [ ${#suites[@]} -eq 0 ] && suites=(Phase1 Debugger)
fail=0
for s in "${suites[@]}"; do
  java -Xmx3g -cp "${CP}build/losim.jar:build/test-classes" "$s" || fail=1
done
exit $fail
