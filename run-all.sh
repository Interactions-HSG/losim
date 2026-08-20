#!/usr/bin/env bash
# Build the framework, build every lab, run every scenario, draw everything.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
mkdir -p build

echo "== framework =="
./build.sh

echo
echo "== labs =="
for lab in hello_ring mapreduce vector_clocks bigdata; do
  printf '%-16s ' "$lab"
  ./lab.sh "$lab" | tail -1
done

echo
echo "== runs =="
run() {                       # run <lab> <scenario> <name> [expect-fail]
  local lab="$1" scenario="$2" name="$3" expect="${4:-pass}"
  set +e
  out=$(java -cp build/losim.jar losim.cli.Main run "labs/$lab/$scenario" \
        --cp "labs/$lab/out" --out "build/$name.json" 2>&1)
  local code=$?
  set -e
  if [ "$expect" = "fail" ] && [ $code -eq 0 ]; then
    echo "$out"; echo "!! $name was expected to violate an invariant but did not"; exit 1
  fi
  if [ "$expect" = "pass" ] && [ $code -ne 0 ]; then
    echo "$out"; echo "!! $name failed"; exit 1
  fi
  echo "$out" | sed 's/^/  /'
  echo
}

run hello_ring    ring.yaml       ring
run mapreduce     wordcount.yaml  wordcount
run vector_clocks ring.yaml       clocks
run bigdata       streaming.yaml  bigdata-streaming
run bigdata       naive.yaml      bigdata-naive fail

echo "== views =="
for t in ring wordcount clocks bigdata-streaming; do
  ./view.sh view "build/$t.json" --out "build/$t.html" | sed 's/^/  /'
done
./view.sh svg build/wordcount.json --scene gantt --out build/wordcount-gantt.svg | sed 's/^/  /'
./view.sh bill build/wordcount.json --svg build/wordcount-bill.svg > build/wordcount-bill.txt
echo "  wrote build/wordcount-bill.txt and build/wordcount-bill.svg"

echo
echo "all labs ran; open build/*.html — or ./serve.sh to watch them in the studio"
