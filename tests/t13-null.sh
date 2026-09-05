#!/usr/bin/env bash
# The null distribution of t13's statistic: how far does that number move when
# telemetry is definitionally not what moved it?
#
# t13 asserts that the fitted allocation exponent shifts by less than 0.05 across
# four telemetry levels. That bound was set on a fast machine and is marginal on a
# slow one — six CI runs on two-core runners gave 0.021 to 0.062, so it fails about
# a third of the time there while running 0.006 to 0.028 locally.
#
# The obvious repair — compare the spread against the exponent's own seed wobble —
# does not hold: locally the spread already exceeds a single wobble one run in four,
# because the spread is a range over four independent runs and the wobble is one
# run's own. And CI wobbles measure 0.032 to 0.036, the same as local, so the wobble
# does not grow on slow hardware and cannot account for the difference.
#
# So this measures the thing that would settle it. Groups of four runs at **one
# fixed telemetry level**, so any spread within a group is by construction not
# caused by telemetry. The distribution of those spreads is what t13's statistic
# does when nothing is moving it, and a defensible bound sits above its upper tail.
#
#   tests/t13-null.sh [groups] [level]     default: 4 groups, FULL
#
# **The plan cache has to go between runs.** It is keyed on the telemetry level and
# the scenario, so four runs at one level would reuse the first fit and report a
# spread of exactly zero — a null distribution measuring nothing at all.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Not GROUPS: that is a bash special variable holding the current user's group
# ids, read-only, and an assignment to it is silently ignored. `$GROUPS` then
# expands to the primary gid — 20 on a Mac, 1001 on a GitHub runner — so this
# script quietly ran twenty groups locally and asked for a thousand on CI, and
# looked from the outside exactly like a hang.
ROUNDS="${1:-4}"
LEVEL="${2:-FULL}"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  P=osx-aarch_64 ;;
  Linux-x86_64)  P=linux-x86_64 ;;
  *) echo "no vendored protoc for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

./build.sh > /dev/null
OUT=build/t13-null
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/traces"
vendor/bin/protoc-$P --plugin=protoc-gen-grpc-java=vendor/bin/protoc-gen-grpc-java-$P \
  --java_out="$OUT/gen" --grpc-java_out="$OUT/gen" -I tests/proto tests/proto/*.proto
CP=$(ls vendor/jars/*.jar | tr '\n' ':')
javac -nowarn --release 21 -cp "${CP}build/losim.jar" -d "$OUT/classes" \
      $(find "$OUT/gen" tests/systems tests/expect -name '*.java')
LAB="${CP}build/losim.jar:$OUT/classes"

# Echoed as parsed, not as passed: a run whose group count is not the one asked
# for is a run whose numbers mean something other than the caller thinks.
echo "null distribution of the t13 statistic: $ROUNDS groups of 4, all at $LEVEL"
echo "cores: $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
echo "(a spread inside a group cannot be telemetry: every run in it is watched identically)"
echo

for g in $(seq 1 "$ROUNDS"); do
  betas=()
  for r in 1 2 3 4; do
    # Forced refit. Without this the key is identical and the fit is reused.
    rm -rf build/.losim-plans
    # Timed and announced as it goes. A long job whose only output arrives at the
    # end is a job nobody can tell apart from a hung one — which is exactly how the
    # first run of this was watched for half an hour before being abandoned blind.
    started=$(date +%s)
    java -Xmx3g -cp "$LAB" losim.cli.Main run --no-view tests/scenarios/t13.yaml \
         --cp "$OUT/classes" --out "$OUT/traces/g$g-r$r.json" --telemetry "$LEVEL" \
         > "$OUT/traces/g$g-r$r.out" 2>&1 || true
    printf '    g%s r%s  %ss\n' "$g" "$r" "$(( $(date +%s) - started ))"
    tail -2 "$OUT/traces/g$g-r$r.out" | sed 's/^/      /'
    b=$(python3 -c "
import json,sys
try:
    d=json.load(open('$OUT/traces/g$g-r$r.json'))
    print('%.4f' % d['meta']['scale']['laws']['allocMb']['beta'])
except Exception as e:
    print('nan')
")
    betas+=("$b")
  done
  python3 -c "
xs=[float(x) for x in '${betas[*]}'.split() if x==x and x!='nan']
if len(xs)<4: print('  group $g: incomplete', '${betas[*]}')
else: print('  group $g: %s  spread %.4f' % (' '.join('%.4f'%x for x in xs), max(xs)-min(xs)))
"
done

echo
python3 tests/t13-null-summary.py "$OUT/traces"

echo
echo "Every spread above is telemetry-free. t13 asserts the across-telemetry spread"
echo "is under 0.05; any of these at or above that says the bound is too tight for"
echo "this host regardless of what telemetry does."
