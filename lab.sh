#!/usr/bin/env bash
# Build one lab against the losim jar. Labs never see the simulator sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
LAB="${1:?usage: ./lab.sh <lab-dir> [command...]}"
cd "$ROOT"
DIR="labs/$LAB"
[ -d "$DIR" ] || { echo "no such lab: $DIR" >&2; exit 1; }

# 1. schema -> Java (only if the lab has one)
for proto in "$DIR"/*.proto; do
  [ -e "$proto" ] || continue
  java -cp build/losim.jar losim.cli.Main gen "$proto" --out "$DIR/gen" > /dev/null
done

# 2. compile student code against the jar alone
rm -rf "$DIR/out" && mkdir -p "$DIR/out"
javac --release 21 -cp build/losim.jar -d "$DIR/out" $(find "$DIR/src" "$DIR/gen" -name '*.java' 2>/dev/null)

# 3. every lab is checked for determinism before it may run
java -cp build/losim.jar losim.cli.Main verify --cp "$DIR/out"
