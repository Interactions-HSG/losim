/**
 * A scenario, being written.
 *
 * The console composes one of these and prints it as YAML; the lab loads that
 * YAML with the same loader a run uses and refuses it if it is wrong. So this
 * file has exactly one job — produce a file that says what the form says — and
 * it is deliberately not a second validator. Two validators disagree, and the
 * one that matters is the one the run uses.
 *
 * What it *does* have to get right is the vocabulary, because the loader is
 * strict about it: `runs:` names Java classes, `retries:` names dotted gRPC
 * methods, every duration says what kind of time it is, and a pool of one is
 * written without a count so its machine keeps the pool's own name.
 */
import type { Offered, Palette, Region } from './lab.ts';

/** One block of machines that grow and shrink together. A single machine is a pool of one. */
export interface Pool {
  name: string;
  /** 1 writes no `count:` at all, and the machine is called after the pool. */
  count: number;
  /**
   * What the machines in it are called: `prefix0`, `prefix1`.
   *
   * Usually the pool's own name, and then a pool of one keeps that name with no
   * digit on it. Set it apart — `mappers` numbered `m0`, `m1` — and the count
   * and the prefix are both written, because the naming no longer follows from
   * the pool's name alone and every fault points at a name.
   */
  prefix: string;
  instance: string;
  /** Machines are dealt round-robin over these. */
  zones: string[];
  /** Java classes, fully qualified. */
  runs: string[];
  /**
   * A memory cap, or `null` for whatever the instance type says.
   *
   * Three states, not two: `null` inherits, a number overrides, and 0 means a
   * machine that cannot hold anything. That is a legal scenario and a different
   * one, so the form must be able to write it and must not write it by
   * accident.
   */
  memoryMb: number | null;
  /** The same, for disk. Nothing is capped until something writes. */
  diskMb: number | null;
  /** Machines in this pool that differ from their siblings. Usually none. */
  overrides: Override[];
}

/**
 * One machine in a pool, set apart from the rest.
 *
 * An empty string or a null number falls back to the pool's own value, because
 * that is what a key the file leaves out means. A pool of eight where one is
 * half the size is the cheapest way to build a straggler; a pool where one has
 * a smaller disk shows a machine filling up while its neighbours do not.
 */
export interface Override {
  /** The machine's own name — `w2`, not `workers`. */
  machine: string;
  /** '' keeps the pool's. */
  instance: string;
  /** '' keeps the zone the pool would have dealt it. */
  zone: string;
  memoryMb: number | null;
  diskMb: number | null;
}

/** Everything that can happen at an instant, in the order the form offers them. */
export const FAULT_KINDS = [
  'kill', 'freeze', 'degrade', 'restart', 'spot_reclaim', 'partition', 'heal',
] as const;

export type FaultKind = (typeof FAULT_KINDS)[number];

/** The two whose value is a pair of machines rather than one. */
export const PAIRED: readonly FaultKind[] = ['partition', 'heal'];

/**
 * One thing that happens at one instant.
 *
 * Which fields mean anything depends on `kind`, and only those are written:
 * a kill's `restartAfterRefMs`, a freeze's `forRefMs`, a degrade's `factor`, a
 * spot reclaim's `noticeRefMs`. The others are kept so switching kind in the
 * form does not lose what was typed under the previous one.
 */
export interface Fault {
  kind: FaultKind;
  atRefMs: number;
  target: string;
  /**
   * The second machine — `partition` and `heal` only, and empty otherwise.
   *
   * Reachability is a property of a *pair*: both machines stay alive, stay in
   * the registry and keep serving everybody else, and one caller sees nothing.
   * No other fault can make that point.
   */
  other: string;
  /** How long a freeze holds. A degrade has no end — see `toYaml`. */
  forRefMs: number;
  /** How many times slower a degrade makes it. */
  factor: number;
  /** Spot reclaim only: how long the warning comes before the machine goes. */
  noticeRefMs: number;
  /** Kill and spot reclaim. 0 means it never comes back, a different exercise. */
  restartAfterRefMs: number;
}

/**
 * How much work there is at full scale, and the grid the engine probes with.
 *
 * `null` on a draft means no `workload:` key at all. That is a different
 * scenario from one declaring a single record, and `mode: scaled` needs a
 * workload to scale down from.
 */
export interface Workload {
  /** The size the design is meant to handle. At least 1. */
  records: number;
  /**
   * The ladder the engine climbs to fit its laws. At least four rungs — three
   * cannot show whether a law bends, and one that bends has to be refused
   * rather than extrapolated across.
   */
  probe: number[];
  /** How many machines to put in each multi-machine pool, varied independently. */
  workers: number[];
}

/**
 * The medium the fleet talks over.
 *
 * All four zero is the same file as no `network:` key at all, which is what
 * `toYaml` then writes — and it is the default a scenario gets by saying
 * nothing: instant, lossless, and the reason a fleet that never sets this can
 * only be tested on a network that never costs it anything.
 */
export interface Net {
  /** What a call between two machines in one zone costs. */
  sameZoneRefMs: number;
  /** And between zones — the only thing that makes placement a decision. */
  crossZoneRefMs: number;
  /** Spread around both, so no two calls take exactly as long. */
  jitterRefMs: number;
  /** 0 to 1. A dropped call, indistinguishable from a dead machine to the caller. */
  loss: number;
}

export interface Chaos {
  kind: 'kill' | 'freeze' | 'degrade';
  everyRefMs: number;
  /** A pool, or one machine. */
  among: string;
  forRefMs: number;
  factor: number;
}

export interface RetryRule {
  /** Dotted, as `retries:` names it — `lab.Worker.Map`. */
  method: string;
  attempts: number;
  backoffRefMs: number;
  /**
   * What the wait is multiplied by after each attempt. 1 is flat.
   *
   * Above 1 gives exponential backoff: each retry eases off a struggling
   * machine instead of asking it again at the same fixed rate. A fixed rate
   * turns one slow machine into an outage for the whole fleet.
   */
  multiplier: number;
  /** Retrying something the `.proto` did not declare idempotent, on purpose. */
  unsafe: boolean;
}

export interface Draft {
  name: string;
  job: string;
  seed: number;
  /**
   * How much real time this run costs you, independent of what its durations
   * mean — every declared `refMs` is divided by this before it is slept. 1 is
   * no compression, real milliseconds; a normal teaching scenario is 2–10.
   */
  kTime: number;
  expectedRunRefSeconds: number;
  /**
   * A marker, and only a marker: it is recorded in the trace's `meta` and
   * nothing in the run reads it. For exercises where the gap between a design
   * working and not working is deliberately thin.
   */
  tightMargin: boolean;
  /** `direct` runs what the workload says; `scaled` probes and projects to it. */
  mode: 'direct' | 'scaled';
  /** `null` writes no `workload:` at all. Scaled mode requires one. */
  workload: Workload | null;
  net: Net;
  pools: Pool[];
  faults: Fault[];
  chaos: Chaos[];
  retries: RetryRule[];
}

/** One machine, once the pools have been dealt out. */
export interface Machine {
  name: string;
  pool: string;
  instance: string;
  zone: string;
  runs: string[];
}

/**
 * The machines a draft would produce, named the way the loader names them.
 *
 * A pool of one keeps the pool's own name; a pool of more is `prefix0`,
 * `prefix1`, and the prefix is the pool's name. Faults are aimed at these, so
 * getting the naming wrong here is a scenario that will not load.
 */
export function expand(draft: Draft): Machine[] {
  const out: Machine[] = [];
  for (const p of draft.pools) {
    const n = Math.max(1, Math.round(p.count));
    const zones = p.zones.length ? p.zones : ['eu-central-1a'];
    // The same condition `toYaml` writes `count:` under, because the two have to
    // agree about naming or every fault points at a machine that is not there.
    const numbered = n > 1 || p.prefix !== p.name;
    for (let i = 0; i < n; i++) {
      const name = numbered ? `${p.prefix}${i}` : p.name;
      const over = p.overrides.find((o) => o.machine === name);
      out.push({
        name,
        pool: p.name,
        instance: over?.instance || p.instance,
        zone: over?.zone || zones[i % zones.length],
        runs: p.runs,
      });
    }
  }
  return out;
}

/* ------------------------------------------------------------------ distance */

export type Link = 'same zone' | 'same region' | 'same continent' | 'across an ocean';

/** The region a zone is in, spelled the way `losim.res.Regions` parses it. */
export function regionOf(zone: string, regions: Region[]): string {
  for (const r of regions) if (r.zones.includes(zone)) return r.name;
  // Unknown is its own region, which is the honest answer: nothing here knows
  // where `rack-3` is either.
  return zone;
}

/**
 * How far apart two machines are, in the only four steps a bill distinguishes.
 *
 * Client-side so the form can say what a placement costs before it is written —
 * the arithmetic is losim's, and this is a copy of it against the same region
 * table the lab just sent.
 */
export function linkOf(a: string, b: string, regions: Region[]): Link {
  if (a === b) return 'same zone';
  const ra = regionOf(a, regions);
  const rb = regionOf(b, regions);
  if (ra === rb) return 'same region';
  const ca = regions.find((r) => r.name === ra)?.continent;
  const cb = regions.find((r) => r.name === rb)?.continent;
  return ca && cb && ca === cb ? 'same continent' : 'across an ocean';
}

/** Every distance that appears in a draft, and how many pairs are at it. */
export function distances(draft: Draft, regions: Region[]): Record<Link, number> {
  const out = { 'same zone': 0, 'same region': 0, 'same continent': 0, 'across an ocean': 0 };
  const ms = expand(draft);
  for (let i = 0; i < ms.length; i++) {
    for (let j = i + 1; j < ms.length; j++) out[linkOf(ms[i].zone, ms[j].zone, regions)]++;
  }
  return out;
}

/**
 * What this fleet costs per hour, on the catalogue's own default prices.
 *
 * A rate, not a bill. What a run costs is what `losim bill` says after it has
 * happened, against a price list this app has never seen — and a second number
 * here that looked like a prediction would be a second accountant. This one is
 * a property of the machines you drew, and it is true before anything runs.
 */
export function perHour(draft: Draft, palette: Palette): number {
  let total = 0;
  for (const m of expand(draft)) {
    total += palette.instances.find((i) => i.name === m.instance)?.onDemandPerHour ?? 0;
  }
  return total;
}

/** Services the code offers that no machine has been given. */
export function unplaced(draft: Draft, palette: Palette): Offered[] {
  const placed = new Set(draft.pools.flatMap((p) => p.runs));
  return palette.services.filter((s) => !placed.has(s.cls));
}

/* --------------------------------------------------------------------- YAML */

const q = (s: string) => (/^[A-Za-z_][\w.-]*$/.test(s) ? s : JSON.stringify(s));

/**
 * The draft, as the file that will be written.
 *
 * Shown in full while it is being composed, because the file *is* the scenario:
 * a student who only ever sees a form learns a form, and what they have to be
 * able to read by the end of the course is the YAML their classmate sent them.
 */
export function toYaml(draft: Draft): string {
  const L: string[] = [];
  L.push(`# ${draft.name}.yaml — written by the lab console`);
  L.push(`seed: ${Math.round(draft.seed)}`);
  // Omitted at 1: the loader defaults to the same value, so an explicit
  // `kTime: 1` and no key at all mean the same thing, and writing the
  // default on every save would be noise on every scenario that never
  // touched it.
  if (draft.kTime !== 1) L.push(`kTime: ${draft.kTime}`);
  L.push(`job: ${q(draft.job)}`);
  L.push(`expectedRun: ${draft.expectedRunRefSeconds} refSeconds`);
  // Omitted at `direct`, which is what a scenario gets by saying nothing.
  if (draft.mode === 'scaled') L.push('mode: scaled');
  // False is what a scenario gets by saying nothing, so it says nothing.
  if (draft.tightMargin) L.push('tightMargin: true');
  // Written in full whenever there is one, ladder and all. The loader fills a
  // ladder the file leaves out, so omitting it here would mean the form showing
  // rungs the file does not contain — and the file is the thing a classmate
  // reads. Every number on screen is a number in the file.
  if (draft.workload) {
    const w = draft.workload;
    L.push(`workload: { records: ${Math.round(w.records)}, `
           + `probe: [${w.probe.map(Math.round).join(', ')}], `
           + `workers: [${w.workers.map(Math.round).join(', ')}] }`);
  }
  // Only the numbers that are actually set, and no key at all when none is:
  // every one of these defaults to 0, so `network: { loss: 0 }` and silence are
  // the same scenario, and writing the silent ones out on every save would put
  // four lines of nothing into every file the console touches.
  const n = draft.net;
  const net: string[] = [];
  if (n.sameZoneRefMs > 0) net.push(`sameZone: ${n.sameZoneRefMs} refMs`);
  if (n.crossZoneRefMs > 0) net.push(`crossZone: ${n.crossZoneRefMs} refMs`);
  if (n.jitterRefMs > 0) net.push(`jitter: ${n.jitterRefMs} refMs`);
  if (n.loss > 0) net.push(`loss: ${n.loss}`);
  if (net.length) L.push(`network: { ${net.join(', ')} }`);
  L.push('');
  L.push('machines:');
  for (const p of draft.pools) {
    const n = Math.max(1, Math.round(p.count));
    L.push(`  ${q(p.name)}:`);
    L.push(`    instance: ${p.instance}`);
    L.push(
      p.zones.length === 1
        ? `    zone: ${p.zones[0]}`
        : `    zone: [${p.zones.join(', ')}]`,
    );
    // A pool of one is written without a count, so its machine keeps the pool's
    // own name — `master`, not `master0`. Faults name machines.
    //
    // Unless the prefix says otherwise: a pool called `mappers` whose machines
    // are `m0`, `m1` needs both keys written even at a count of one, because the
    // naming no longer follows from the pool's name.
    if (n > 1 || p.prefix !== p.name) {
      L.push(`    count: ${n}`);
      L.push(`    prefix: ${q(p.prefix)}`);
    }
    if (p.runs.length) L.push(`    runs: [${p.runs.join(', ')}]`);
    // A cap the pool never set is the instance type's own. Writing `memoryMb: 0`
    // for it would instead be a machine that cannot hold anything.
    if (p.memoryMb !== null) L.push(`    memoryMb: ${p.memoryMb}`);
    if (p.diskMb !== null) L.push(`    diskMb: ${p.diskMb}`);
    if (p.overrides.length) {
      L.push('    overrides:');
      for (const o of p.overrides) {
        // Only what this machine actually differs in. An override that repeated
        // the pool's own values would be four lines saying nothing, and the
        // point of the block is that one machine is not like the others.
        const bits: string[] = [];
        if (o.instance) bits.push(`instance: ${o.instance}`);
        if (o.zone) bits.push(`zone: ${o.zone}`);
        if (o.memoryMb !== null) bits.push(`memoryMb: ${o.memoryMb}`);
        if (o.diskMb !== null) bits.push(`diskMb: ${o.diskMb}`);
        L.push(`      ${q(o.machine)}: { ${bits.join(', ')} }`);
      }
    }
  }
  if (draft.faults.length) {
    L.push('');
    L.push('faults:');
    for (const f of draft.faults) {
      // Each kind writes only what it actually obeys. A `for:` on a degrade is
      // accepted by the loader and then ignored by the run — a one-time degrade
      // schedules no thaw and the machine stays slow — so writing one would put
      // a number in the file that the scenario does not honour.
      let tail = '';
      if (f.kind === 'kill' && f.restartAfterRefMs > 0) {
        tail = `, restart_after: ${f.restartAfterRefMs} refMs`;
      } else if (f.kind === 'freeze') {
        tail = `, for: ${f.forRefMs} refMs`;
      } else if (f.kind === 'degrade') {
        tail = `, factor: ${f.factor}`;   // required: the loader refuses a degrade without one
      } else if (f.kind === 'spot_reclaim') {
        // The notice is the whole lesson, so it is always written — a spot
        // machine that gives no warning is just a kill by another name.
        tail = `, notice: ${f.noticeRefMs} refMs`;
        if (f.restartAfterRefMs > 0) tail += `, restart_after: ${f.restartAfterRefMs} refMs`;
      }
      // A pair fault names two machines under one key. `restart` names one and
      // takes nothing else.
      const who = PAIRED.includes(f.kind)
        ? `[${q(f.target)}, ${q(f.other)}]`
        : q(f.target);
      L.push(`  - { at: ${f.atRefMs} refMs, ${f.kind}: ${who}${tail} }`);
    }
  }
  if (draft.chaos.length) {
    L.push('');
    L.push('chaos:');
    for (const c of draft.chaos) {
      const extra = c.kind === 'degrade' ? `, factor: ${c.factor}` : '';
      const held = c.kind === 'kill' ? '' : `, for: ${c.forRefMs} refMs`;
      L.push(`  - { ${c.kind}: { every: ${c.everyRefMs} refMs, among: ${q(c.among)}${held}${extra} } }`);
    }
  }
  if (draft.retries.length) {
    L.push('');
    L.push('retries:');
    for (const r of draft.retries) {
      const unsafe = r.unsafe ? ', unsafe: true' : '';
      // Omitted at 1, which is the loader's own default and a flat backoff.
      const mult = r.multiplier !== 1 ? `, multiplier: ${r.multiplier}` : '';
      L.push(`  - { method: ${q(r.method)}, attempts: ${r.attempts}, `
             + `backoff: ${r.backoffRefMs} refMs${mult}${unsafe} }`);
    }
  }
  return L.join('\n') + '\n';
}

/**
 * A draft to start from, built out of what this system actually offers.
 *
 * A coordinator and a pool of workers, because that is what almost every
 * scenario in this course is — and starting from an empty form means starting
 * by reading the manual to find out what a pool is called.
 */
export function firstDraft(palette: Palette): Draft {
  const zone = palette.regions[0]?.zones[0] ?? 'eu-central-1a';
  const worker = palette.services[0];
  const has = (n: string) => palette.instances.some((i) => i.name === n);
  return {
    name: 'authored',
    job: palette.jobs[0] ?? '',
    seed: 1,
    kTime: 1,
    expectedRunRefSeconds: 20,
    // Direct, and no workload: what a scenario gets by saying nothing, and the
    // only pair of the two that needs no numbers decided for the student.
    tightMargin: false,
    mode: 'direct',
    workload: null,
    // Instant and lossless, which is what a scenario that says nothing gets.
    net: { sameZoneRefMs: 0, crossZoneRefMs: 0, jitterRefMs: 0, loss: 0 },
    pools: [
      {
        name: 'coordinator',
        count: 1,
        prefix: 'coordinator',
        instance: has('m5.large') ? 'm5.large' : (palette.instances[0]?.name ?? 'm5.large'),
        zones: [zone],
        runs: [],
        memoryMb: null,
        diskMb: null,
        overrides: [],
      },
      {
        name: 'workers',
        count: 3,
        prefix: 'workers',
        instance: has('c5.large') ? 'c5.large' : (palette.instances[0]?.name ?? 'c5.large'),
        zones: [zone],
        runs: worker ? [worker.cls] : [],
        memoryMb: null,
        diskMb: null,
        overrides: [],
      },
    ],
    faults: [],
    chaos: [],
    retries: [],
  };
}
