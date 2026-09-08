/**
 * has the layout moved?
 *
 *   node viewer/checks/parity.ts [glob-ish substring]
 *   node viewer/checks/parity.ts --freeze          # write the oracle again
 *   node viewer/checks/parity.ts --capture         # replace the traces it reads
 *
 * Runs `Layout` over every trace in `viewer/checks/traces/` and diffs every
 * position, every zone rectangle, every column label, every payload digest and
 * every decoded series channel against a **frozen oracle**. Same numbers or
 * something moved.
 *
 * A layout bug found by looking at a picture is found slowly and argued about;
 * found by a diff it is a line number. That is also why the comparison is exact
 * rather than tolerant — the same arithmetic in the same order on the same
 * doubles — so anything but bit-equality is a difference in the code and not in
 * the floating point.
 *
 * **Both halves are committed, and that is what makes the claim above true.**
 * This read `build/tests/traces` once, which `dev suite` rewrites — and a run is
 * deliberately not reproducible: real threads, a real wall clock, no simulated
 * scheduler. Two suite runs of identical code differed in 75 of 75 sampled
 * series channels and moved `durationRefMs` by 121, which reached this check as
 * different channel values, different payload digests, and float noise in the
 * fifteenth digit of a position. All 22 traces differed. So the check was green
 * only until somebody ran the suite, and then reported a wall of differences
 * whose cause was nothing anybody had done.
 *
 * Freezing the output while the input drifts cannot hold. So the input is frozen
 * too: `traces/*.json.gz` are 22 real suite traces, captured once and committed
 * beside the layouts fitted from them. 1.8 MB against the 85 MB of vendored jars
 * this repository already carries, and the same bargain — a check that needs a
 * four-minute build to say anything is a check nobody runs.
 *
 * **What the oracle is, and what it is not.** It was once what the Python
 * `Layout` this was ported from computed, over eighty-one gallery traces, on the
 * day the port was proved against it. That claim is spent: the Python is gone,
 * the gallery it ran over is not in this repository, and 3.0 deliberately
 * changed what a column is. What is frozen now is this code's own answer over an
 * input that does not move. It catches a layout that moved when nobody meant it
 * to, which is the property that was always doing the work; it can no longer
 * tell you the port is faithful, because there is nothing left to be faithful
 * to.
 *
 * A deliberate change to the layout runs `--freeze` in the same commit and reads
 * the diff. `--capture` is the other one, and it is rarer and heavier: it
 * replaces the **traces** from whatever `dev suite` last produced, which moves
 * every number here at once. Run it when the trace format changes — a schema
 * bump, a new channel — and never to make a red check green, because it will,
 * and it will do it whether or not anything is wrong.
 */
import { gunzipSync, gzipSync } from 'node:zlib';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { LEGACY, Layout } from '../lib/layout.ts';
import { Trace, digest, contents, entries } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
/** The committed input: real suite traces, captured once so they stop moving. */
const TRACES = resolve(HERE, 'traces');

/** Where `dev suite` writes, and the only thing `--capture` reads. */
const FRESH = resolve(ROOT, 'build/tests/traces');

/**
 * The clock the Python reckoned by: the job, or the last span to close.
 *
 * `Trace.duration` answers a different question: it covers every event the
 * trace carries, because the heap walk runs every eighth tick and an
 * out-of-memory therefore lands *after* the job it ended (D12) — clipped at
 * the job's end, the OOM cannot be scrubbed to and never reaches the bill.
 * That is a fact about the viewer's clock, not about the layout, so the parity
 * check keeps asking its own question on this narrower one.
 */
function pythonDuration(trace: Trace): number {
  let end = Math.max(1.0, Number(trace.meta['durationRefMs'] ?? 0));
  for (const s of trace.spans) if (s.t1 > end) end = s.t1;
  return end;
}

function dump(path: string): Record<string, unknown> {
  const trace = Trace.parse(gunzipSync(readFileSync(path)).toString('utf8'));
  // The Python renderer's spacing, deliberately: this check is about whether the
  // port computes the same layout, not about the spacing chosen afterwards.
  const lay = new Layout(trace, 3.05, 1.62, LEGACY);
  const columns = lay.columns;

  const out: Record<string, unknown> = {
    nodes: trace.nodes.length,
    columns,
    labels: lay.labels,
    column_labels: columns.map((_, i) => lay.columnLabel(i)),
    column_x: columns.map((_, i) => lay.columnCentre(i)),
    home: sorted(Object.fromEntries(lay.home)),
    zones: lay.zones,
    width: lay.width,
    height: lay.height,
    scale_for: lay.scaleFor,
    column_floor: lay.columnFloor(),
    inlet: lay.inlet,
    outlet: lay.outlet,
    at: sorted(Object.fromEntries([...lay.at].map(([n, p]) => [n, [...p]]))),
    size: sorted(Object.fromEntries([...lay.size.keys()].map((n) => [n, [...lay.sizeOf(n)]]))),
    zone_rect: Object.fromEntries(lay.zones.map((z) => [z, [...lay.zoneRect(z)]])),
    duration: pythonDuration(trace),
    entry: trace.entry,
    tasks: Object.fromEntries([...trace.tasks()].sort((a, b) => a[0] - b[0]).map(([k, v]) => [String(k), v])),
  };

  const said: Record<string, unknown> = {};
  for (const s of trace.spans) {
    for (const side of ['arg', 'result']) {
      const body = s.detail[side];
      if (body === undefined || body === null) continue;
      said[`${s.id}.${side}`] = digest(body);
      said[`${s.id}.${side}!`] = digest(body, 2, false);
      if (typeof body === 'object' && !Array.isArray(body)) {
        const rec = body as Record<string, unknown>;
        said[`${s.id}.${side}~`] = contents(rec);
        const counts: Record<string, number> = {};
        for (const k of Object.keys(rec)) {
          const v = rec[k];
          if (Array.isArray(v) || (v !== null && typeof v === 'object')) counts[k] = entries(v)[1];
        }
        said[`${s.id}.${side}#`] = counts;
      }
    }
  }
  out.digest = said;

  const probes: Record<string, number[]> = {};
  const span = pythonDuration(trace);
  for (const m of trace.nodes) {
    for (const metric of ['heldMb', 'diskMb', 'busy', 'capMb', 'alive']) {
      const row: number[] = [];
      for (let i = 0; i <= 20; i++) row.push(trace.channel(m.name, metric, (span * i) / 20));
      if (row.some((v) => v)) probes[`${m.name}.${metric}`] = row;
    }
  }
  out.channels = probes;
  return out;
}

function sorted(o: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.keys(o).sort().map((k) => [k, o[k]]));
}

/** JSON with object keys in a fixed order, so the diff can only report content. */
function canonical(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v) ?? 'undefined';
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  const o = v as Record<string, unknown>;
  return `{${Object.keys(o).sort().map((k) => `${JSON.stringify(k)}:${canonical(o[k])}`).join(',')}}`;
}

/** Walk both trees together and name the first few places they disagree. */
function differences(a: unknown, b: unknown, path: string, out: string[], cap = 6): void {
  if (out.length >= cap) return;
  if (canonical(a) === canonical(b)) return;
  const objA = a && typeof a === 'object' && !Array.isArray(a);
  const objB = b && typeof b === 'object' && !Array.isArray(b);
  if (objA && objB) {
    const keys = new Set([...Object.keys(a as object), ...Object.keys(b as object)]);
    for (const k of [...keys].sort()) {
      differences((a as never)[k], (b as never)[k], `${path}.${k}`, out, cap);
    }
    return;
  }
  if (Array.isArray(a) && Array.isArray(b) && a.length === b.length) {
    for (let i = 0; i < a.length; i++) differences(a[i], b[i], `${path}[${i}]`, out, cap);
    return;
  }
  out.push(`    ${path}\n      was ${clip(canonical(b))}\n      now ${clip(canonical(a))}`);
}

function clip(s: string): string {
  return s.length > 140 ? `${s.slice(0, 137)}...` : s;
}

// ------------------------------------------------------------------ the check

const args = process.argv.slice(2);
const freeze = args.includes('--freeze');
const capture = args.includes('--capture');
const filter = args.find((a) => !a.startsWith('--'));

// Replaces the input, not the oracle. Deliberately a separate flag from
// --freeze: re-blessing a layout you changed on purpose is an ordinary thing to
// do, and replacing every trace underneath it is not.
if (capture) {
  let taken = 0;
  for (const f of readdirSync(FRESH).filter((f) => f.endsWith('.json')).sort()) {
    writeFileSync(
      join(TRACES, `${f}.gz`),
      gzipSync(readFileSync(join(FRESH, f)), { level: 9 }),
    );
    taken++;
  }
  console.log(`S2  ${taken} traces captured from ${FRESH}`);
  console.log('    now run --freeze, and read that diff: every number will have moved');
  process.exit(0);
}

let names: string[];
try {
  names = readdirSync(TRACES)
    .filter((f) => f.endsWith('.json.gz'))
    .map((f) => f.replace(/\.json\.gz$/, ''))
    .filter((n) => !filter || n.includes(filter))
    .sort();
} catch {
  console.error(`no traces in ${TRACES} — they are committed, so this is a broken checkout`);
  process.exit(1);
}
if (!names.length) {
  console.error(`no traces in ${TRACES} — they are committed, so this is a broken checkout`);
  process.exit(1);
}

let same = 0;
const failed: string[] = [];
for (const name of names) {
  const path = join(TRACES, `${name}.json.gz`);
  let mine: Record<string, unknown>;
  try {
    mine = dump(path);
  } catch (e) {
    failed.push(`  ${name.padEnd(28)} ts threw: ${(e as Error).message}`);
    continue;
  }
  if (freeze) {
    writeFileSync(
      join(HERE, 'fixtures', 'layout', `${name}.json.gz`),
      gzipSync(Buffer.from(JSON.stringify(mine)), { level: 9 }),
    );
    same++;
    continue;
  }
  let theirs: unknown;
  try {
    theirs = JSON.parse(
      gunzipSync(readFileSync(join(HERE, 'fixtures', 'layout', `${name}.json.gz`))).toString('utf8'),
    );
  } catch {
    // A trace with no fixture is one the suite gained since the oracle was
    // frozen. That is a thing to notice rather than to pass over quietly.
    failed.push(`  ${name.padEnd(28)} no frozen layout to compare against`);
    continue;
  }
  if (canonical(mine) === canonical(theirs)) {
    same++;
    continue;
  }
  const where: string[] = [];
  differences(mine, theirs, '', where);
  failed.push(`  ${name}\n${where.join('\n')}`);
}

console.log(`S2  ${names.length} traces`);
console.log(freeze
  ? `    ${same} frozen — read the diff before committing them`
  : `    ${same} identical, ${failed.length} differ`);
if (failed.length) {
  console.log();
  console.log(failed.slice(0, 8).join('\n'));
  if (failed.length > 8) console.log(`  ... and ${failed.length - 8} more`);
  process.exitCode = 1;
}
