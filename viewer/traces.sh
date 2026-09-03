#!/usr/bin/env bash
# Puts traces beside the exported app, with an index the picker can read and a
# bill beside each one.
#
# A static server cannot list a directory, so the index has to be written down.
# The bills are `losim bill --json`, and they are what the Ledger view accrues —
# so the money in the viewer and the money on the command line come from one
# calculation rather than two that will drift.
#
#   ./viewer/traces.sh                 your runs, from build/traces
#   ./viewer/traces.sh build/mine      one directory, or one file, and it is yours
#   ./viewer/traces.sh ~/work/run.json anywhere on disk, not only in this tree
#   ./viewer/traces.sh --gallery       ...and the gallery, for whoever writes losim
#   ./viewer/traces.sh --suite --all   ...and the reference suite, or everything
#
# ## The gallery does not ship
#
# **Only your own runs are swept by default.** The gallery is a hundred worked
# examples written to develop and teach losim; it is not part of losim, and a
# student who installs this should not receive thirty-six megabytes of somebody
# else's afternoons. It is one flag away for whoever is working on the simulator
# itself, and absent for everyone else.
#
# **build/traces is where your own runs go**, and it is swept first. Point
# `losim run --out` at it and the viewer picks the run up with no arguments and
# no configuration:
#
#   java -cp ... losim.cli.Main run my.yaml --out build/traces/my.json
#
# ## Whose run is whose
#
# Every run is tagged with where it came from — **yours**, the reference suite's,
# or the gallery's — and the picker groups them under those headings with yours
# at the top. Without that a student's first run appears as one line among a
# hundred and five, alphabetically, between `mr-cascade` and `mr-chaos`. The
# gallery is a hundred worked examples; it must not be what the viewer looks like
# to somebody who has just written their own system.
#
# Name collisions go to whoever is more yours: an `mr-classic.json` you ran
# shadows the gallery's, and the shadowing is said out loud rather than silent.
set -euo pipefail
cd "$(dirname "$0")/.."

INTO=build/viewer/traces
mkdir -p "$INTO" build/traces

# Each source, with the heading its runs appear under. A bare path is yours —
# nobody names a directory that is not theirs — and the two collections have to
# be asked for.
SOURCES=(yours:build/traces)
want_gallery=0
want_suite=0
named=0
for a in "$@"; do
  case "$a" in
    --gallery) want_gallery=1 ;;
    --suite)   want_suite=1 ;;
    --all)     want_gallery=1; want_suite=1 ;;
    --*)       echo "unknown option: $a" >&2; exit 2 ;;
    *)         [ "$named" -eq 0 ] && SOURCES=(); named=1; SOURCES+=("yours:$a") ;;
  esac
done
[ "$want_suite" -eq 1 ]   && SOURCES+=(suite:build/tests/traces)
[ "$want_gallery" -eq 1 ] && SOURCES+=(gallery:build/gallery/traces)

CP="$(ls vendor/jars/*.jar 2>/dev/null | tr '\n' ':')build/losim.jar"
ORIGINS="$INTO/.origins"
: > "$ORIGINS"
mine=0

for entry in "${SOURCES[@]}"; do
  from="${entry%%:*}"
  src="${entry#*:}"
  [ -e "$src" ] || continue

  # A directory that exists and holds no traces is worth a word. This loop once
  # pointed a level too high and simply found nothing, so twenty-two runs from
  # the reference suite were quietly absent from the picker for as long as
  # nobody counted them.
  if [ -d "$src" ] && ! ls "$src"/*.json > /dev/null 2>&1; then
    [ "$from" = "yours" ] || echo "  $src: no traces here" >&2
    continue
  fi

  for f in $( [ -d "$src" ] && ls "$src"/*.json 2>/dev/null || echo "$src" ); do
    [ -f "$f" ] || continue
    case "$(basename "$f")" in index.json|*.bill.json) continue ;; esac
    name="$(basename "${f%.json}")"

    # First writer wins, and the order is yours first. A run you made under a
    # name the gallery also uses is the one you meant.
    if grep -q "^$name	" "$ORIGINS" 2>/dev/null; then
      echo "  $name: yours, so the $from copy is not used" >&2
      continue
    fi
    printf '%s\t%s\n' "$name" "$from" >> "$ORIGINS"
    [ "$from" = "yours" ] && mine=$((mine + 1))

    into="$INTO/$(basename "$f")"
    [ "$f" -nt "$into" ] && cp "$f" "$into"

    # The bill, if losim is built, and only when it is missing or out of date —
    # billing a hundred traces takes long enough that doing it on every serve
    # would make `serve.sh` feel broken. Missing, the film still plays and the
    # money is simply absent, which is the right failure: a viewer that invented
    # its own prices would be a second accountant.
    out="$INTO/$name.bill.json"
    if [ -f build/losim.jar ] && [ ! -s "$out" -o "$f" -nt "$out" ]; then
      java -cp "$CP" losim.cli.Main bill "$f" --json > "$out" 2>/dev/null || rm -f "$out"
    fi
  done
done

# The index the picker reads. In node, which this repository already needs for
# the viewer itself — and which is now the only language here besides Java and
# TypeScript.
node --input-type=module -e '
import { readdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const into = process.argv[1];
const origins = new Map();
const marks = join(into, ".origins");
if (existsSync(marks)) {
  for (const line of readFileSync(marks, "utf8").split("\n")) {
    const tab = line.indexOf("\t");
    if (tab > 0) origins.set(line.slice(0, tab), line.slice(tab + 1));
  }
}

const runs = [];
for (const file of readdirSync(into).sort()) {
  if (!file.endsWith(".json") || file.endsWith(".bill.json") || file === "index.json") continue;
  const name = file.replace(/\.json$/, "");
  const row = { name, href: `traces/${file}`, from: origins.get(name) ?? "gallery" };
  try {
    const t = JSON.parse(readFileSync(join(into, file), "utf8"));
    if (Array.isArray(t.machines)) {
      row.machines = t.machines.length;
      // Enough for the gallery to draw a run before opening it: how many
      // machines, and how far apart they are. A card that cannot say whether a
      // design crosses an ocean is a card nobody can choose from.
      row.zones = [...new Set(t.machines.map((m) => String(m.zone ?? "")))].filter(Boolean).sort();
    }
    const meta = t.meta ?? {};
    if (typeof meta.durationRefMs === "number") row.durationRefMs = meta.durationRefMs;
    if (meta.job) row.job = String(meta.job);
    if (meta.scenario) row.scenario = String(meta.scenario);
    if (meta.completed === false) row.completed = false;
    // What it cost, from the bill beside it — never computed here. The viewer
    // must not become a second accountant, and neither must this script.
    const billed = join(into, `${name}.bill.json`);
    if (existsSync(billed)) {
      const b = JSON.parse(readFileSync(billed, "utf8"));
      if (typeof b?.observed?.cost === "number") row.cost = b.observed.cost;
      if (b?.observed?.currency) row.currency = String(b.observed.currency);
      if (b?.observed?.buckets) row.buckets = b.observed.buckets;
    }
  } catch {
    // A half-written trace is what a run in progress looks like. It stays in the
    // list under its name and gains its numbers next time.
  }
  runs.push(row);
}

// Yours first, then the suite, then the gallery: somebody looking for their own
// run should not have to scroll past a hundred worked examples to find it.
const rank = { yours: 0, suite: 1, gallery: 2 };
runs.sort((a, b) => (rank[a.from] ?? 3) - (rank[b.from] ?? 3) || a.name.localeCompare(b.name));

writeFileSync(join(into, "index.json"), JSON.stringify({ runs }));
const counts = runs.reduce((n, r) => (n[r.from] = (n[r.from] ?? 0) + 1, n), {});
const said = Object.entries(counts).map(([k, v]) => `${v} ${k}`).join(", ");
console.log(`${runs.length} traces (${said}) -> ${join(into, "index.json")}`);
' "$INTO"

if [ "$mine" -eq 0 ]; then
  cat >&2 <<'TXT'

  Nothing of yours in build/traces yet. To make one:
    losim run your.yaml --cp <your classes> --out build/traces/mine.json
  Then ./viewer/serve.sh, and it is the run the viewer opens.
  (Working on losim itself? --gallery or --suite bring those in too.)
TXT
fi
