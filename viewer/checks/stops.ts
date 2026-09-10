/**
 * can a reader reach every moment the trace says happened?
 *
 *   node viewer/checks/stops.ts
 *
 * The scrubber's `]` and `[` walk the set in `lib/frame.ts` called NOTABLE, and
 * the pinned node's history draws the set in `NodePanel.tsx` called TOLD.
 * A kind that DISSALy emits and neither set contains is a moment a reader cannot
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
import { gunzipSync } from 'node:zlib';

import { RunIndex, revealText } from '../lib/frame.ts';
import { Trace, asReveal } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACES = resolve(HERE, '../../build/served');
/**
 * The parity oracle's traces, borrowed read-only.
 *
 * `build/served` is not committed and holds whatever was last swept, so on a
 * fresh checkout it is empty or it is the gallery. These 22 are in the tree, and
 * they are the only ones carrying a node that reveals two keys or a node that
 * reveals none — which is exactly what the reveal proof below needs. Read only:
 * nothing here writes them, and `parity.ts --capture` is the only thing that may.
 */
const FROZEN = resolve(HERE, 'traces');

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
  'simulation',    // the run's own header, at t=0
  'boot',          // drawn as the node appearing
  'done',          // the end of the film is not a place to stop inside it
  'rpc_call',      // becomes a message: an envelope out, work, an envelope back
  'rpc_end',
  'handler_start', // becomes the node's work bar
  'handler_end',
  'queue_wait',    // drawn as the gap before the work bar
  // reveal(): drawn continuously on the node and its panel rather than stepped
  // to. Unlike the rest of this list that is a claim about another piece of
  // code, so it is proved below rather than asserted here — it was false for
  // long enough to ship, and it read exactly like the true ones.
  'state',
  'series',        // the dense samples behind every sparkline
  'trust',         // what the verifier made of the code, which is not an instant
  // A `failures:` entry announces itself and then does the thing.
  // `Simulate.java:576` writes the announcement and the switch beneath it
  // kills, freezes or degrades a fraction of a refMs later — and those are
  // stops already. The pairs land 0.1 to 0.5 refMs apart, so drawing both would
  // put two markers on one event and make `]` step onto the announcement of a
  // thing before the thing.
  //
  // One letter from `failed`, which is not this and is not furniture: `failure`
  // is one of the simulation's own `failures:` firing, and `failed` is the
  // simulation ending without an answer. Each is named after where it comes
  // from, which is the only reason to keep two words this close.
  'failure',
]);

let names: string[];
try {
  names = readdirSync(TRACES)
    .filter((f) => f.endsWith('.json') && !f.endsWith('.bill.json') && f !== 'index.json')
    .map((f) => f.replace(/\.json$/, ''))
    .sort();
} catch {
  console.error(`no traces in ${TRACES} — run \`dissaly dev viewer traces\` first`);
  process.exit(1);
}

let failed = 0;
const unreachable = new Map<string, string[]>();
const seen = new Set<string>();
let withLog = 0;
let withHeal = 0;
let withRpcFailure = 0;

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
  if (kinds.has('rpc_failure')) withRpcFailure++;

  for (const k of kinds) {
    if (FURNITURE.has(k) || stops.has(k)) continue;
    if (!unreachable.has(k)) unreachable.set(k, []);
    unreachable.get(k)!.push(name);
  }
}

console.log(`stops: ${names.length} traces, ${seen.size} event kinds between them`);
console.log(`  ${withLog} carry log(), ${withHeal} carry heal,`
  + ` ${withRpcFailure} carry rpc_failure`);

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
  console.log('        `dissaly dev viewer traces --gallery` brings in a run that does');
}
// The same treatment, and for the same reason: `rpc_failure` is in NOTABLE and
// in TOLD, and nothing here proves it, because no simulation in the reference
// suite writes a `failures:` under a `runs:` entry yet. Said out loud rather
// than read as a pass — a set that carries none of a kind would pass whatever
// the two lists said about it.
if (withRpcFailure === 0) {
  console.log('  note: no trace here fails an rpc on one node, so rpc_failure is unexercised —');
  console.log('        it is in NOTABLE and TOLD, and nothing here holds them to it');
}

if (unreachable.size === 0) console.log('  every kind a trace carries is reachable');

/* ------------------------------------------------------- the excuse, proved
 *
 * `state` is excused from being a stop because something else draws it. That
 * sentence sat in FURNITURE for a release while `revealedAt()` had no callers
 * at all, and nothing here could tell the difference: an excuse is prose, and
 * prose passes every check.
 *
 * So the excuse is held to `RunIndex`, which is what the film actually reads.
 * `Trace.revealedAt()` is kept deliberately naive — a scan of every event, no
 * index — precisely so that this comparison is between two different pieces of
 * code rather than one piece of code and a restatement of it.
 */
const at = (trace: Trace, t: number): Map<string, unknown> => trace.revealedAt(t);

let revealTraces = 0;
let revealValues = 0;
let multiKey = 0;
let uneven = 0;
const wrong: string[] = [];

for (const [where, label] of [[FROZEN, 'frozen'], [TRACES, 'served']] as const) {
  let files: string[];
  try {
    files = readdirSync(where).filter((f) => f.endsWith('.json.gz') || (f.endsWith('.json')
      && !f.endsWith('.bill.json') && f !== 'index.json'));
  } catch {
    continue;
  }
  for (const f of files) {
    let trace: Trace;
    let index: RunIndex;
    try {
      const raw = f.endsWith('.gz')
        ? gunzipSync(readFileSync(join(where, f))).toString('utf8')
        : readFileSync(join(where, f), 'utf8');
      trace = Trace.parse(raw);
      index = new RunIndex(trace);
    } catch {
      continue;
    }
    const keys = index.revealedKeys();
    if (!keys.length) continue;
    revealTraces++;
    if (keys.length > 1) multiKey++;

    // Which nodes report which keys, over the whole run — the fact the frame's
    // row set claims to be, and the only way to catch a row invented for a node
    // that never says anything of the kind.
    const ever = new Set<string>();
    for (const k of at(trace, Number.POSITIVE_INFINITY).keys()) ever.add(k);
    if (new Set([...ever].map((k) => k.split('\u0000')[0])).size < trace.nodes.length) uneven++;

    const say = (m: string) => wrong.push(`${label}/${f}: ${m}`);
    for (let i = 0; i <= 12; i++) {
      const t = (trace.duration * i) / 12;
      const frame = index.frameAt(t);
      const oracle = at(trace, t);

      // Everything the slow way found is on the picture, with the same value.
      for (const [k, v] of oracle) {
        const value = asReveal(v);
        if (value === null) continue;
        const [vm, key] = k.split('\u0000');
        const node = frame.nodes.find((n) => n.name === vm);
        if (!node) continue;
        const r = node.revealed.find((x) => x.key === key);
        revealValues++;
        if (!r) say(`${vm} revealed ${key} by t=${t.toFixed(1)} and the frame has no row for it`);
        else if (!Object.is(r.value, value)) {
          say(`${vm}.${key} is ${String(value)} at t=${t.toFixed(1)} and the frame says ${String(r.value)}`);
        }
      }

      for (const node of frame.nodes) {
        // Nothing on the picture that the slow way did not find. An index that
        // lags a sample, holds a value too long, or files it under the wrong
        // node all show up here and nowhere else.
        for (const r of node.revealed) {
          if (r.value !== null) continue;
          if (!ever.has(`${node.name}\u0000${r.key}`)) {
            say(`${node.name} has a row for ${r.key} and never reveals it`);
          }
        }
        for (const r of node.revealed) {
          if (r.value === null) continue;
          if (!Object.is(asReveal(oracle.get(`${node.name}\u0000${r.key}`)), r.value)) {
            say(`the frame has ${node.name}.${r.key} = ${String(r.value)} at t=${t.toFixed(1)}, unreported`);
          }
        }
        // One order for every node, so two panels can be read down a column.
        const order = node.revealed.map((r) => keys.indexOf(r.key));
        if (order.some((n, j) => j > 0 && n <= order[j - 1])) {
          say(`${node.name} lists its keys out of the run's order`);
        }
      }
    }
  }
}

/**
 * The types no captured run has.
 *
 * Every `reveal()` in every trace in this tree is an `int`, so the string and
 * boolean branches of the value formatter have no coverage from the fixtures
 * and would not get any from sweeping harder. Written out by hand instead —
 * a trace is JSON, and three events is a cheaper fixture than a simulation.
 */
const HAND = Trace.parse(JSON.stringify({
  meta: { name: 'hand' },
  nodes: [{ name: 'w0' }],
  events: [
    { t: 1, kind: 'state', vm: 'w0', detail: { key: 'forwardedTo', value: 'w3' } },
    { t: 2, kind: 'state', vm: 'w0', detail: { key: 'storeMissed', value: true } },
    { t: 3, kind: 'state', vm: 'w0', detail: { key: 'jpegKb', value: 1.5 } },
    { t: 4, kind: 'state', vm: 'w0', detail: { key: 'ignored', value: { a: 1 } } },
    { t: 9, kind: 'done' },
  ],
}));
const hand = new RunIndex(HAND).frameAt(9).nodes[0];
const drawn = hand.revealed.map((r) => `${r.key}=${r.value === null ? '-' : revealText(r.value)}`);
const WANT = 'forwardedTo=w3,storeMissed=yes,jpegKb=1.5';
if (drawn.join(',') !== WANT) {
  wrong.push(`a string, a boolean and a float read as ${drawn.join(',')}, wanted ${WANT}`);
}

console.log(`  ${revealTraces} traces reveal something, ${revealValues} values held to the picture`);
for (const w of wrong) console.error(`  ${w}`);
if (wrong.length) failed = 1;

// Backwards again, the same way the three above are. A trace set with no
// reveals in it would prove nothing here however broken the index was; and the
// two interesting shapes — a node with two keys, a node the others leave out —
// are the ones a single-key gallery would quietly stop covering.
if (revealTraces === 0) {
  console.error('  nothing here reveals anything, so this proves nothing about reveal()');
  failed = 1;
}
if (multiKey === 0) {
  console.log('  note: no trace here reveals two keys on one node, so the row order is unexercised —');
  console.log('        checks/traces/t12-spill and t13-chatty carry it');
}
if (uneven === 0) {
  console.log('  note: every node here reveals something, so the empty panel is unexercised —');
  console.log('        checks/traces/t6 has a master that never does');
}

process.exit(failed);
