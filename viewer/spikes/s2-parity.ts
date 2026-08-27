/**
 * S2 — is the layout port faithful?
 *
 *   node viewer/spikes/s2-parity.ts [glob-ish substring]
 *
 * Runs the TypeScript `Layout` over every gallery trace and diffs every
 * position, every zone rectangle, every column label, every payload digest and
 * every decoded series channel against a **frozen oracle**. Same numbers or the
 * layout has moved.
 *
 * This is why the port comes *before* anything renders. A layout bug found by
 * looking at a picture is found slowly and argued about; found by a diff it is a
 * line number. It is also why the comparison is exact rather than tolerant: the
 * two implementations do the same arithmetic in the same order on the same
 * doubles, so anything but bit-equality is a difference in the code and not in
 * the floating point.
 *
 * The oracle is `fixtures/layout/<trace>.json.gz` — what the Python `Layout`
 * this was ported from computed, on the day the port was proved against it, over
 * all eighty-one traces. The Python is gone; keeping its *answer* rather than
 * its code is what lets the check outlive it. A layout change that is deliberate
 * regenerates the fixtures in the same commit, and one that is not shows up here
 * as a line number.
 */
import { gunzipSync } from 'node:zlib';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { LEGACY, Layout } from '../lib/layout.ts';
import { Trace, digest, contents, entries } from '../lib/trace.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const TRACES = resolve(ROOT, 'build/gallery/traces');

/**
 * The clock the Python reckoned by: the job, or the last span to close.
 *
 * `Trace.duration` deliberately no longer means this. The film's clock has to
 * cover every event the trace carries, because the heap walk runs every eighth
 * tick and an out-of-memory therefore lands *after* the job it ended (D12) —
 * clipped at the job's end, the OOM cannot be scrubbed to and never reaches the
 * bill. That is a change to the viewer, not to the layout, so the parity check
 * keeps asking its own question on the old clock.
 */
function pythonDuration(trace: Trace): number {
  let end = Math.max(1.0, Number(trace.meta['durationRefMs'] ?? 0));
  for (const s of trace.spans) if (s.t1 > end) end = s.t1;
  return end;
}

function dump(path: string): Record<string, unknown> {
  const trace = Trace.parse(readFileSync(path, 'utf8'));
  // The Python renderer's spacing, deliberately: this check is about whether the
  // port computes the same layout, not about the spacing chosen afterwards.
  const lay = new Layout(trace, 3.05, 1.62, LEGACY);
  const columns = lay.columns;

  const out: Record<string, unknown> = {
    machines: trace.machines.length,
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
    job: trace.job,
    phases: trace.phases().map((s) => [s.label, Layout.stage(s.label), s.t0, s.t1]),
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
  for (const m of trace.machines) {
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

const filter = process.argv[2];
let names: string[];
try {
  names = readdirSync(TRACES)
    .filter((f) => f.endsWith('.json'))
    .map((f) => f.replace(/\.json$/, ''))
    .filter((n) => !filter || n.includes(filter))
    .sort();
} catch {
  console.error(`no traces in ${TRACES} — run ./gallery/run.sh first`);
  process.exit(1);
}

let same = 0;
const failed: string[] = [];
for (const name of names) {
  const path = join(TRACES, `${name}.json`);
  let mine: Record<string, unknown>;
  try {
    mine = dump(path);
  } catch (e) {
    failed.push(`  ${name.padEnd(28)} ts threw: ${(e as Error).message}`);
    continue;
  }
  let theirs: unknown;
  try {
    theirs = JSON.parse(
      gunzipSync(readFileSync(join(HERE, 'fixtures', 'layout', `${name}.json.gz`))).toString('utf8'),
    );
  } catch {
    // A trace with no fixture is a trace the gallery gained since the oracle was
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
console.log(`    ${same} identical, ${failed.length} differ`);
if (failed.length) {
  console.log();
  console.log(failed.slice(0, 8).join('\n'));
  if (failed.length > 8) console.log(`  ... and ${failed.length - 8} more`);
  process.exitCode = 1;
}
