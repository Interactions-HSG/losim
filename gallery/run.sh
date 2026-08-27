#!/usr/bin/env bash
# The gallery: one MapReduce, written over gRPC with all ten of its phases, run
# under twenty different kinds of bad afternoon.
#
# Different from tests/run.sh on purpose. That suite asserts; this one *shows*.
# Nothing here is a pass or a fail — every scenario is supposed to produce a
# trace worth looking at, and several of them are supposed to produce a job that
# does not finish. What it checks is only that each one ran and left a trace
# behind, because a scenario that crashed the simulator shows nothing at all.
#
# Every run is also billed. A design decision that costs nothing is not a design
# decision, and the difference between two of these scenarios is often not in
# whether the job finished but in what it cost to finish it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  P=osx-aarch_64 ;;
  Linux-x86_64)  P=linux-x86_64 ;;
  *) echo "no vendored protoc for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

./build.sh > /dev/null

OUT=build/gallery
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/traces"

vendor/bin/protoc-$P --plugin=protoc-gen-grpc-java=vendor/bin/protoc-gen-grpc-java-$P \
  --java_out="$OUT/gen" --grpc-java_out="$OUT/gen" \
  -I gallery/proto gallery/proto/*.proto

# Against the jar and the vendored gRPC, never against losim/src — the same rule
# a lab is under. The gallery is a lab; it just happens to be one we wrote.
CP=$(ls vendor/jars/*.jar | tr '\n' ':')
javac -nowarn --release 21 -cp "${CP}build/losim.jar" -d "$OUT/classes" \
      $(find "$OUT/gen" gallery/systems -name '*.java')

LAB="${CP}build/losim.jar:$OUT/classes"

scenarios=("$@")
if [ ${#scenarios[@]} -eq 0 ]; then
  scenarios=($(ls gallery/scenarios/*.yaml | xargs -n1 basename | sed 's/\.yaml$//'))
fi

pad() { printf '%-22s' "$1"; }
fail=0

for name in "${scenarios[@]}"; do
  pad "$name"
  java -Xmx3g -cp "$LAB" losim.cli.Main run --no-view "gallery/scenarios/$name.yaml" \
       --cp "$OUT/classes" --out "$OUT/traces/$name.json" \
       > "$OUT/traces/$name.out" 2>&1 || true

  if [ ! -s "$OUT/traces/$name.json" ]; then
    echo "NO TRACE — see $OUT/traces/$name.out"
    sed 's/^/    /' "$OUT/traces/$name.out" | head -12
    fail=1
    continue
  fi

  # Billed from the trace, so the cost of a bad afternoon is beside the trace of it.
  java -cp "$LAB" losim.cli.Main bill "$OUT/traces/$name.json" \
       > "$OUT/traces/$name.bill.txt" 2>&1 || true

  # One line each: what happened, and what it cost. A job that did not finish is
  # a result here rather than an error, so both are printed the same way.
  node --input-type=module -e '
import { readFileSync } from "node:fs";
const t = JSON.parse(readFileSync(process.argv[1], "utf8"));
const ev = t.events ?? [];
const kinds = {};
for (const e of ev) kinds[e.kind] = (kinds[e.kind] ?? 0) + 1;

// What went wrong, named. A gallery run that merely finished tells nobody what
// it was for; several of these are supposed to end badly.
const notes = [["oom","OOM"],["disk_full","disk"],["kill","killed"],["freeze","frozen"],
               ["degrade","slowed"],["restart","restarted"],["spot_notice","reclaimed"],
               ["partition","cut"],["retry","retried"],["over_horizon","OVER-HORIZON"]]
  .filter(([k]) => kinds[k]).map(([k, label]) => `${label} ${kinds[k]}`);

let cost = "", profit = "";
for (const line of readFileSync(process.argv[2], "utf8").split("\n")) {
  const words = line.trim().split(/\s+/);
  if (line.includes("TOTAL COST")) cost = words[words.length - 1];
  if (line.includes("PROFIT")) profit = words[words.length - 1];
}
const done = ev.some((e) => e.kind === "done") ? "ok " : "STOPPED";
const pad = (s, n) => String(s).padStart(n);
console.log(
  `${pad((t.machines ?? []).length, 3)}m ${pad(ev.length, 5)}ev ${pad((t.spans ?? []).length, 5)}sp ` +
  `${pad(Math.round(t.meta?.durationRefMs ?? 0), 7)}refMs ${pad(done, 7)}  ` +
  `cost ${pad(cost, 8)} profit ${pad(profit, 9)}   ${notes.join(", ")}`);
' "$OUT/traces/$name.json" "$OUT/traces/$name.bill.txt"
done

echo
echo "traces in $OUT/traces"
exit $fail
