#!/usr/bin/env bash
# The reference suite: gRPC systems a course could ship.
#
# Different from ./check.sh on purpose. That one is losim's own acceptance
# criteria, calling into losim's classes. This one is the product surface: the
# systems compile against build/losim.jar and the vendored gRPC alone, they are
# run through the command line a student types, and every assertion is made
# against the trace on disk — because the trace is the interchange format, and a
# build whose trace was unreadable would pass every check in ./check.sh.
#
# One JVM per case. A suite whose cases share a JVM has an order, and an order is
# a thing that breaks when somebody adds a case in the middle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  P=osx-aarch_64 ;;
  Linux-x86_64)  P=linux-x86_64 ;;
  *) echo "no vendored protoc for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

./build.sh > /dev/null

OUT=build/tests
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/traces"

vendor/bin/protoc-$P --plugin=protoc-gen-grpc-java=vendor/bin/protoc-gen-grpc-java-$P \
  --java_out="$OUT/gen" --grpc-java_out="$OUT/gen" \
  -I tests/proto tests/proto/*.proto

# Against the jar and the vendored gRPC, never against losim/src. That is the rule
# a lab is under, and compiling the suite under it is how the rule stays true.
CP=$(ls vendor/jars/*.jar | tr '\n' ':')
javac -nowarn --release 21 -cp "${CP}build/losim.jar" -d "$OUT/classes" \
      $(find "$OUT/gen" tests/systems tests/expect -name '*.java')

LAB="${CP}build/losim.jar:$OUT/classes"
fail=0

# A case is one or more runs through the command line a student types, and then an
# assertion about what they wrote. Split in two because some cases are about the
# difference between two runs, and one of them is about a run that must not start.
run_scenario() {                                  # name yaml [extra cli args]
  local name=$1 scenario=$2; shift 2
  java -Xmx3g -cp "$LAB" losim.cli.Main run "tests/scenarios/$scenario" \
       --cp "$OUT/classes" --out "$OUT/traces/$name.json" "$@" \
       > "$OUT/traces/$name.out" 2>&1 || true      # a run that fails is a result
}

assert() {                                        # class [paths...]
  local klass=$1; shift
  java -Xmx3g -cp "$LAB" "$klass" "$@" || fail=1
}

# The common shape: one scenario, one assertion.
run_case() { run_scenario "$1" "$3"; assert "$2" "$OUT/traces/$1.json" "$OUT/traces/$1.out"; }

cases=("$@")
if [ ${#cases[@]} -eq 0 ]; then cases=(t1 t2 t3 t4 t5 t6 t7 t8 t9); fi

for c in "${cases[@]}"; do
  case "$c" in
    t1) assert T1 ;;
    t2) run_case t2 T2 t2.yaml ;;
    t3) run_case t3 T3 t3.yaml ;;
    t4) run_case t4 T4 t4.yaml ;;
    t5) run_case t5 T5 t5.yaml ;;
    t6) run_case t6 T6 t6.yaml ;;
    t8) run_scenario t8-tight t8-tight.yaml
        run_scenario t8-roomy t8-roomy.yaml
        assert T8 "$OUT/traces/t8-tight.json" "$OUT/traces/t8-roomy.json" ;;
    t9) run_case t9 T9 t9.yaml ;;
    t7) run_scenario t7 t7.yaml
        run_scenario t7-unsafe t7-unsafe.yaml
        assert T7 "$OUT/traces/t7.json" "$OUT/traces/t7-unsafe.out" ;;
    *)  echo "no such case: $c" >&2; exit 2 ;;
  esac
done

if [ $fail -eq 0 ]; then echo "reference suite: all cases passed"; else echo "reference suite: FAILED"; fi
exit $fail
