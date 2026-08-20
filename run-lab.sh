#!/usr/bin/env bash
# One lab, built and run — the same interface the assignment repositories give
# students through their ./losim script, so the studio drives this checkout the
# same way it drives theirs. (It cannot be called "losim" here: that name is
# already the simulator's source directory.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

case "${1:-}" in
  tasks)
    for d in labs/*/; do
      name=$(basename "$d")
      [ "$name" = prices ] && continue
      [ -d "$d/src" ] && echo "$name"
    done ;;
  run)
    lab="${2:?which lab?}"
    [ -d "labs/$lab" ] || { echo "no such lab: $lab" >&2; exit 1; }
    scenario="${3:-}"
    if [ -z "$scenario" ]; then
      scenario=$(cd "labs/$lab" && ls *.yaml 2>/dev/null | sort | head -1)
    fi
    [ -n "$scenario" ] || { echo "no scenario in labs/$lab" >&2; exit 1; }
    ./lab.sh "$lab" >/dev/null
    mkdir -p build
    java -cp build/losim.jar losim.cli.Main run "labs/$lab/$scenario" \
         --cp "labs/$lab/out" --out "build/$lab.json" ;;
  *) echo "usage: ./run-lab.sh <run|tasks> [lab] [scenario.yaml]"; exit 2 ;;
esac
