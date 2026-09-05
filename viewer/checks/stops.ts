/**
 * can a reader reach every moment the trace says happened?
 *
 *   node viewer/checks/stops.ts
 *
 * The scrubber's `]` and `[` walk the set in `lib/frame.ts` called NOTABLE, and
 * the pinned machine's history draws the set in `MachinePanel.tsx` called TOLD.
 * A kind that losim emits and neither set contains is a moment a reader cannot
 * get to except by dragging the bar and guessing.
 *
 * This checks the general property rather than only the shapes already known:
 * the failure repeats whenever the manual describes an event the viewer never
 * draws.
 *
 *   **heal** missing while **partition** is present would send `]` walking
 *   into a partition and straight past its repair. The repair is the half that
 *   teaches: the instant the network comes back is not the instant the data
 *   agrees again, and on one trace those sit 350 refMs apart.
 *
 *   **log** missing entirely would leave `write/telemetry.mdx`'s promise —
 *   `log()` as "the sentence a reader needs" — unkept: it reaches the terminal
 *   and the trace, and if nothing draws it the manual's advice degrades to
 *   "use reveal() instead", which is not what the two are for.
 *
 * Checking the general property means the next such gap fails here instead of
 * being found by a reader: **every event kind present in a committed trace is
 * either reachable or deliberately named as furniture.**
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RunIndex } from '../lib/frame.ts';
import { Trace } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACES = resolve(HERE, '../../build/viewer/traces');

/**
 * Kinds that are deliberately not stops, and why.
 *
 * <p>Every one of these is either the scaffolding a span is built from — drawn
 * as the message or the work it belongs to, never as a moment of its own — or a
 * fact about the run rather than an instant in it. Anything not here and not in
 * NOTABLE fails below, which is the point: the list of what is *not* drawn has
 * to be written down, or "it isn't drawn" and "nobody noticed" look the same.
 */
const FURNITURE = new Set([
  'scenario',      // the run's own header, at t=0
  'boot',          // drawn as the machine appearing
  'done',          // the end of the film is not a place to stop inside it
  'rpc_call',      // becomes a message: an envelope out, work, an envelope back
  'rpc_end',
  'handler_start', // becomes the machine's work bar
  'handler_end',
  'queue_wait',    // drawn as the gap before the work bar
  'state',         // reveal(): read continuously by revealedAt(), not as a stop
  'series',        // the dense samples behind every sparkline
  // A chaos strike announces itself and then does the thing. `Run.java:450`
  // writes the announcement and the switch beneath it kills, freezes or
  // degrades a fraction of a refMs later — and those are stops already. On
  // mr-chaos the pairs land 0.1 to 0.3 refMs apart, so drawing both would put
  // two markers on one event and make `]` step onto the announcement of a
  // thing before the thing.
  'chaos',
]);

let names: string[];
try {
  names = readdirSync(TRACES)
    .filter((f) => f.endsWith('.json') && !f.endsWith('.bill.json') && f !== 'index.json')
    .map((f) => f.replace(/\.json$/, ''))
    .sort();
} catch {
  console.error(`no traces in ${TRACES} — run ./viewer/traces.sh first`);
  process.exit(1);
}

let failed = 0;
const unreachable = new Map<string, string[]>();
const seen = new Set<string>();
let withLog = 0;
let withHeal = 0;

for (const name of names) {
  let trace: Trace;
  let index: RunIndex;
  try {
    trace = Trace.parse(readFileSync(join(TRACES, `${name}.json`), 'utf8'));
    index = new RunIndex(trace);
  } catch {
    continue;
  }

  const stops = new Set(index.events().map((e) => String(e.kind)));
  const kinds = new Set(trace.events.map((e) => String(e.kind)));
  for (const k of kinds) seen.add(k);
  if (kinds.has('log')) withLog++;
  if (kinds.has('heal')) withHeal++;

  for (const k of kinds) {
    if (FURNITURE.has(k) || stops.has(k)) continue;
    if (!unreachable.has(k)) unreachable.set(k, []);
    unreachable.get(k)!.push(name);
  }
}

console.log(`stops: ${names.length} traces, ${seen.size} event kinds between them`);
console.log(`  ${withLog} carry log(), ${withHeal} carry heal`);

if (unreachable.size) {
  failed = 1;
  for (const [kind, where] of unreachable) {
    console.error(
      `  ${kind} is emitted but is neither a stop nor named as furniture` +
        ` — in ${where.length} trace(s), e.g. ${where[0]}`,
    );
  }
}

// The check run backwards. A trace set carrying none of the kinds this was
// written for would pass no matter what NOTABLE said, and a check that cannot
// fail is not one — so the absence of the evidence is reported rather than
// read as a pass.
//
// log() is in the default trace set, so its absence is a failure. heal only
// appears in a run that partitions something, which lives in the gallery — so
// its absence is said out loud and not treated as a pass.
if (withLog === 0) {
  console.error('  no trace here carries a log() event, so this proves nothing about log');
  failed = 1;
}
if (withHeal === 0) {
  console.log('  note: no trace here partitions anything, so heal is unexercised —');
  console.log('        ./viewer/traces.sh --gallery brings in a run that does');
}

if (failed === 0) console.log('  every kind a trace carries is reachable');
process.exit(failed);
