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
  instance: string;
  /** Machines are dealt round-robin over these. */
  zones: string[];
  /** Java classes, fully qualified. */
  runs: string[];
}

export interface Kill {
  atRefMs: number;
  target: string;
  /** 0 means it never comes back, which is a different exercise. */
  restartAfterRefMs: number;
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
  pools: Pool[];
  kills: Kill[];
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
    for (let i = 0; i < n; i++) {
      out.push({
        name: n === 1 ? p.name : `${p.name}${i}`,
        pool: p.name,
        instance: p.instance,
        zone: zones[i % zones.length],
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
    if (n > 1) {
      L.push(`    count: ${n}`);
      L.push(`    prefix: ${q(p.name)}`);
    }
    if (p.runs.length) L.push(`    runs: [${p.runs.join(', ')}]`);
  }
  if (draft.kills.length) {
    L.push('');
    L.push('faults:');
    for (const f of draft.kills) {
      const after = f.restartAfterRefMs > 0
        ? `, restart_after: ${f.restartAfterRefMs} refMs` : '';
      L.push(`  - { at: ${f.atRefMs} refMs, kill: ${q(f.target)}${after} }`);
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
      L.push(`  - { method: ${q(r.method)}, attempts: ${r.attempts}, `
             + `backoff: ${r.backoffRefMs} refMs${unsafe} }`);
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
    pools: [
      {
        name: 'coordinator',
        count: 1,
        instance: has('m5.large') ? 'm5.large' : (palette.instances[0]?.name ?? 'm5.large'),
        zones: [zone],
        runs: [],
      },
      {
        name: 'workers',
        count: 3,
        instance: has('c5.large') ? 'c5.large' : (palette.instances[0]?.name ?? 'c5.large'),
        zones: [zone],
        runs: worker ? [worker.cls] : [],
      },
    ],
    kills: [],
    chaos: [],
    retries: [],
  };
}
