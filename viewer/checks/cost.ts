/**
 * S3 and S8 — is the frame cheap enough, on the two traces that decide it?
 *
 *   node viewer/checks/cost.ts
 *
 * **S3** asks one architectural question and nothing else: *can React drive 30
 * fps at 25 machines, or does the packet layer have to go imperative?* React's
 * own cost is not measurable from node, but the thing React is asked to do is —
 * and the fork only becomes necessary if **deriving** the frame is already
 * eating the budget. At 30 fps a frame is 33.3 ms and the derivation should be a
 * small fraction of it, because everything after it (reconciliation, layout,
 * paint) has to fit in what is left.
 *
 * **S8** asks whether the span waterfall stays usable on the biggest trace:
 * building the tree once, and then the per-scroll work, which is a `slice` and
 * has to stay one.
 *
 * Measured on the traces that actually exist rather than on a synthetic worst
 * case, because the question is whether *this* viewer holds up on *these* runs.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RunIndex } from '../lib/frame.ts';
import { SpanTree } from '../lib/spans.ts';
import { Trace } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACES = resolve(HERE, '../../build/viewer/traces');

const BUDGET = 1000 / 30; // one frame at 30 fps

function open(name: string): Trace | null {
  try {
    return Trace.parse(readFileSync(join(TRACES, `${name}.json`), 'utf8'));
  } catch {
    return null;
  }
}

/** The median, because one slow sample is the machine and not the code. */
function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
}

function p95(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * 0.95))];
}

/** The biggest thing on disk, by whichever measure is being asked about. */
function biggest(by: (t: Trace) => number): { name: string; trace: Trace } | null {
  let best: { name: string; trace: Trace; score: number } | null = null;
  for (const f of readdirSync(TRACES)) {
    if (!f.endsWith('.json') || f.endsWith('.bill.json') || f === 'index.json') continue;
    const name = f.replace(/\.json$/, '');
    const trace = open(name);
    if (!trace) continue;
    const score = by(trace);
    if (!best || score > best.score) best = { name, trace, score };
  }
  return best;
}

// ------------------------------------------------------------------------ S3

const wide = biggest((t) => t.machines.length);
if (!wide) {
  console.error(`no traces in ${TRACES}`);
  process.exit(1);
}

const index = new RunIndex(wide.trace);
const dwell = wide.trace.duration / 40;

// Warm, then measure. The first frame pays for every lazily-decoded channel and
// is not the frame anybody watches.
for (let i = 0; i < 40; i++) index.frameAt((wide.trace.duration * i) / 40, { dwellRefMs: dwell });

const frames: number[] = [];
let mostFlights = 0;
let mostWork = 0;
const N = 600;
for (let i = 0; i < N; i++) {
  const t = (wide.trace.duration * i) / N;
  const a = process.hrtime.bigint();
  const f = index.frameAt(t, { dwellRefMs: dwell });
  frames.push(Number(process.hrtime.bigint() - a) / 1e6);
  mostFlights = Math.max(mostFlights, f.flights.length);
  mostWork = Math.max(mostWork, f.machines.reduce((n, m) => n + m.work.length, 0));
}

console.log('S3  can the frame be derived inside a frame?');
console.log(`    ${wide.name} — ${wide.trace.machines.length} machines, ${wide.trace.spans.length} spans`);
console.log(
  `    median ${median(frames).toFixed(2)}ms, p95 ${p95(frames).toFixed(2)}ms, worst ${Math.max(...frames).toFixed(2)}ms` +
    ` of a ${BUDGET.toFixed(1)}ms budget`,
);
console.log(`    busiest frame: ${mostFlights} messages in flight, ${mostWork} handlers running`);
const share = p95(frames) / BUDGET;
console.log(
  share < 0.15
    ? `    -> ${(share * 100).toFixed(1)}% of the budget. React keeps the packet layer; no fork needed.`
    : share < 0.5
      ? `    -> ${(share * 100).toFixed(1)}% of the budget. Tight but React's, provided machines stay memoised.`
      : `    -> ${(share * 100).toFixed(1)}% of the budget. The packet layer has to go imperative (Step 5's fork).`,
);

// ------------------------------------------------------------------------ S8

// The biggest by spans, and `pr-chaos` by name — ten PageRank iterations under
// standing chaos is the deep, wide, failure-riddled case the plan names, and it
// is not always the one with the most spans.
const named = open('pr-chaos');
const cases = [biggest((t) => t.spans.length), named ? { name: 'pr-chaos', trace: named } : null]
  .filter((c): c is { name: string; trace: Trace } => !!c)
  .filter((c, i, all) => all.findIndex((o) => o.name === c.name) === i);

console.log();
console.log('S8  does the waterfall stay usable on the biggest traces?');
for (const big of cases) {
  const a = process.hrtime.bigint();
  const tree = new SpanTree(big.trace);
  const built = Number(process.hrtime.bigint() - a) / 1e6;

  const collapsed = new Set<number>();
  const rowTimes: number[] = [];
  for (let i = 0; i < 200; i++) {
    const b = process.hrtime.bigint();
    tree.rows(collapsed);
    rowTimes.push(Number(process.hrtime.bigint() - b) / 1e6);
  }

  const filtered: number[] = [];
  for (let i = 0; i < 200; i++) {
    const b = process.hrtime.bigint();
    tree.rows(collapsed, (n) => !n.ok);
    filtered.push(Number(process.hrtime.bigint() - b) / 1e6);
  }

  const deepest = Math.max(...tree.flat.map((n) => n.depth));
  console.log(
    `    ${big.name} — ${big.trace.spans.length} spans, ${big.trace.events.length} events, ${deepest + 1} deep`,
  );
  console.log(`    tree built once in ${built.toFixed(1)}ms`);
  console.log(`    rows(): median ${median(rowTimes).toFixed(2)}ms, p95 ${p95(rowTimes).toFixed(2)}ms`);
  console.log(`    rows() filtered: median ${median(filtered).toFixed(2)}ms, p95 ${p95(filtered).toFixed(2)}ms`);
  console.log(`    critical path: ${tree.critical.size} spans, computed while building`);
  const worst = Math.max(p95(rowTimes), p95(filtered));
  console.log(
    worst < BUDGET / 4
      ? `    -> ${worst.toFixed(2)}ms per scroll, well inside a frame. No virtualisation library needed.`
      : `    -> ${worst.toFixed(2)}ms per scroll. Memoise rows() on (collapsed, filter).`,
  );

  // The self-time arithmetic is the part that could quietly be wrong, so it is
  // asserted rather than eyeballed: nothing may claim more of its own work than
  // it had wall clock to do it in.
  let bad = 0;
  for (const n of tree.flat) if (n.selfMs > n.t1 - n.t0 + 1e-6) bad++;
  console.log(bad ? `    !! ${bad} spans claim more self time than they lasted` : '    self time: sound on every span');
}
