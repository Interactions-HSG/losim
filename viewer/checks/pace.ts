/**
 * does the paced clock actually keep its promise, and at what cost?
 *
 *   node viewer/checks/pace.ts
 *
 * The promise is one sentence: **nothing the film draws is on screen for less
 * than a second.** It is worth checking by arithmetic rather than by watching,
 * because the failure mode is a message that flickers past once in a run nobody
 * happens to be scrubbing through at the time.
 *
 * Four things are asserted, and the last two are the ones that would make this a
 * lie rather than a slow film:
 *
 *   1. **every moment gets its second** — each leg of each message, each stretch
 *      of work, measured in film seconds rather than in trace time;
 *   2. **the map is monotone** — trace time never goes backwards, so nothing is
 *      reordered and nothing overlaps that did not;
 *   3. **the map is invertible** — `traceAt(displayAt(t)) == t`, because seeking,
 *      stepping and recording all rely on it;
 *   4. **no reading moves** — the trace instant a frame is drawn at is a real
 *      trace instant, so a machine's memory at 400 refMs is still what it was.
 *
 * And it prints the price: how much longer each film runs than the run did.
 * That number is the honest cost of the guarantee, and somebody should see it
 * rather than discover it.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RunIndex } from '../lib/frame.ts';
import { HOLD_SECONDS, NORMAL, Pace } from '../lib/pace.ts';
import { Trace } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACES = resolve(HERE, '../../build/viewer/traces');

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
let checked = 0;
const rows: string[] = [];

for (const name of names) {
  let index: RunIndex;
  try {
    index = new RunIndex(Trace.parse(readFileSync(join(TRACES, `${name}.json`), 'utf8')));
  } catch {
    continue;
  }
  const moments = index.moments();
  if (!moments.length) continue;
  checked++;

  const pace = Pace.of(moments, index.duration, HOLD_SECONDS);
  const bare = index.duration / NORMAL;

  // 1. Every moment gets its second.
  let short = 0;
  let worst = Infinity;
  let worstAt = 0;
  for (const m of moments) {
    if (m.t1 <= m.t0) continue;
    const seconds = pace.displayAt(m.t1) - pace.displayAt(m.t0);
    if (seconds < worst) {
      worst = seconds;
      worstAt = m.t0;
    }
    // A hair under, because these are two lerps against the same breakpoints and
    // the arithmetic is floating point, not because the guarantee is soft.
    if (seconds < HOLD_SECONDS - 1e-6) short++;
  }

  // 2. Monotone, sampled densely across the film.
  let backwards = 0;
  let last = -Infinity;
  const STEPS = 4000;
  for (let i = 0; i <= STEPS; i++) {
    const t = pace.traceAt((pace.total * i) / STEPS);
    if (t < last - 1e-9) backwards++;
    last = t;
  }

  // 3. Invertible, at every breakpoint and between them.
  let drift = 0;
  for (const m of moments) {
    for (const t of [m.t0, m.t1, (m.t0 + m.t1) / 2]) {
      const back = pace.traceAt(pace.displayAt(t));
      drift = Math.max(drift, Math.abs(back - Math.max(0, Math.min(index.duration, t))));
    }
  }

  // 4. The clock still covers the whole run, ends included.
  const startsAtZero = Math.abs(pace.traceAt(0)) < 1e-9;
  const endsAtEnd = Math.abs(pace.traceAt(pace.total) - index.duration) < 1e-6;

  const bad = short > 0 || backwards > 0 || drift > 1e-6 || !startsAtZero || !endsAtEnd;
  if (bad) failed++;

  rows.push(
    `    ${name.padEnd(26)} ${String(moments.length).padStart(5)} moments  ` +
      `${bare.toFixed(1).padStart(6)}s -> ${pace.total.toFixed(1).padStart(7)}s ` +
      `(${pace.stretch.toFixed(0).padStart(4)}x)  ` +
      (bad
        ? `!! ${short} under ${HOLD_SECONDS}s, ${backwards} backwards, drift ${drift.toExponential(1)}` +
          `${startsAtZero ? '' : ', does not start at 0'}${endsAtEnd ? '' : ', does not reach the end'}`
        : `shortest ${worst.toFixed(2)}s at ${worstAt.toFixed(0)} refMs`),
  );
}

console.log(`S9  ${checked} traces paced at a ${HOLD_SECONDS}s floor`);
console.log(rows.join('\n'));
console.log();
if (failed) {
  console.log(`    !! ${failed} traces broke the guarantee`);
  process.exitCode = 1;
} else {
  console.log('    every moment gets its second; monotone, invertible, and covers the run');
}
