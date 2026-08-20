#!/usr/bin/env bash
# Runs once when the container is created. Leaves a Codespace ready to run labs.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "building the losim framework..."
./build.sh

echo "building the labs..."
for lab in hello_ring mapreduce vector_clocks bigdata; do
  ./lab.sh "$lab" >/dev/null && echo "  $lab ok"
done

echo
echo "ready. Try:"
echo "  ./run-all.sh                 build, run every lab, draw everything"
echo "  ./serve.sh                   browse the results on port 8000"
echo "  ./test.sh                    the framework's own tests"
