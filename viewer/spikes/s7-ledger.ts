/**
 * S7 — does the live ledger agree with the CLI, and is the attribution honest?
 *
 *   node viewer/spikes/s7-ledger.ts [substring]
 *
 * Two questions, and the second one only exists because of the first.
 *
 * **Does it close on the bill?** The strip accrues, which means it invents a
 * *shape* for every line. A shape that does not reach exactly 1 at the end is a
 * second accountant, and the whole design of `lib/ledger.ts` is that there is
 * only one. So: accrue to `t = duration`, and compare against what
 * `losim bill --json` said, to the rappen.
 *
 * **Is the attribution a partition?** Pointing at a machine shows its share.
 * Shares that sum past 1 charge the fleet more than it was billed; shares that
 * silently sum to less lose money down a crack. Neither is visible by looking at
 * a picture — both are one line of arithmetic here. A line attributed to nobody
 * is fine and expected (revenue, the late-finish penalty), so what is checked is
 * that every line is *either* fully attributed or attributed not at all.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { LedgerModel, type BillJson } from '../lib/ledger.ts';
import { Trace } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACES = resolve(HERE, '../../build/viewer/traces');

/** The rappen, which is what "to the rappen" has to mean to be checkable. */
const RAPPEN = 0.005;

const filter = process.argv[2];
const names = readdirSync(TRACES)
  .filter((f) => f.endsWith('.bill.json'))
  .map((f) => f.replace(/\.bill\.json$/, ''))
  .filter((n) => !filter || n.includes(filter))
  .sort();

let checked = 0;
const failed: string[] = [];

for (const name of names) {
  let trace: Trace;
  let bill: BillJson;
  try {
    trace = Trace.parse(readFileSync(join(TRACES, `${name}.json`), 'utf8'));
    bill = JSON.parse(readFileSync(join(TRACES, `${name}.bill.json`), 'utf8')) as BillJson;
  } catch (e) {
    failed.push(`  ${name.padEnd(30)} unreadable: ${(e as Error).message}`);
    continue;
  }
  if (!bill.observed) continue;

  const model = new LedgerModel(trace, bill);
  const close = model.at(trace.duration);
  const problems: string[] = [];

  if (Math.abs(close.cost - bill.observed.cost) > RAPPEN) {
    problems.push(`cost ${close.cost.toFixed(4)} vs bill ${bill.observed.cost.toFixed(4)}`);
  }
  if (Math.abs(close.profit - bill.observed.profit) > RAPPEN) {
    problems.push(`profit ${close.profit.toFixed(4)} vs bill ${bill.observed.profit.toFixed(4)}`);
  }
  for (const b of Object.keys(bill.observed.buckets) as (keyof typeof bill.observed.buckets)[]) {
    const mine = close.buckets[b];
    const theirs = bill.observed.buckets[b];
    if (Math.abs(mine - theirs) > RAPPEN) {
      problems.push(`${b} ${mine.toFixed(4)} vs bill ${theirs.toFixed(4)}`);
    }
  }

  // Nothing may arrive before the run does, and nothing may arrive twice.
  const opening = model.at(0);
  if (opening.cost > close.cost + RAPPEN) problems.push('cost goes down over the run');
  let last = -Infinity;
  for (let i = 0; i <= 40; i++) {
    const at = model.at((trace.duration * i) / 40).cost;
    if (at < last - RAPPEN) {
      problems.push(`cost is not monotonic at ${((i / 40) * 100).toFixed(0)}%`);
      break;
    }
    last = at;
  }

  // The attribution, summed over the fleet, against the same line's own total.
  const perMachine = trace.machines.map((m) => model.at(trace.duration, m.name));
  for (let i = 0; i < close.lines.length; i++) {
    const line = close.lines[i].line;
    let sum = 0;
    for (const p of perMachine) {
      const row = p.lines.find((x) => x.line === line);
      if (row) sum += row.mine;
    }
    // Against what has *arrived*, not against the line's total: a shape that has
    // not finished is the closing check's business, and mixing the two would
    // report one fault as two.
    const whole = close.lines[i].sofar;
    if (whole <= 1e-9) continue;
    const share = sum / whole;
    // Either all of it is somebody's or none of it is. Anything between is a
    // line that half-belongs to the fleet, which is not a claim anyone can read.
    if (share > 0.0001 && Math.abs(share - 1) > 0.0001) {
      problems.push(`"${line.what}" attributed ${(share * 100).toFixed(2)}% of itself`);
    }
  }

  // And the fleet's shares of the total must not exceed the total.
  const together = perMachine.reduce((a, p) => a + (p.focus?.cost ?? 0), 0);
  if (together > close.cost + RAPPEN) {
    problems.push(`machines carry ${together.toFixed(4)} of a ${close.cost.toFixed(4)} bill`);
  }

  checked++;
  if (problems.length) failed.push(`  ${name}\n${problems.map((p) => `    ${p}`).join('\n')}`);
}

console.log(`S7  ${checked} bills`);
console.log(`    ${checked - failed.length} agree, ${failed.length} differ`);
if (failed.length) {
  console.log();
  console.log(failed.slice(0, 10).join('\n'));
  if (failed.length > 10) console.log(`  ... and ${failed.length - 10} more`);
  process.exitCode = 1;
}
