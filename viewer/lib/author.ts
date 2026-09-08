/**
 * A simulation, being written.
 *
 * The console composes one of these and prints it as YAML; the lab loads that
 * YAML with the same loader a simulation uses and refuses it if it is wrong. So
 * this file has exactly one job — produce a file that says what the form says —
 * and it is deliberately not a second validator. Two validators disagree, and
 * the one that matters is the one the simulation uses.
 *
 * What it *does* have to get right is the vocabulary, because the loader is
 * strict about it: a `runs:` entry is a service and the `.java` file that
 * implements it, `retries:` names dotted gRPC methods, every duration says what
 * kind of time it is, and a pool of one is written without a count so its node
 * keeps the pool's own name.
 *
 * **One class of mistake is gone rather than checked.** A failure used to name
 * the node it hit by string, so the form had to hold the naming rules and get
 * them right or write a file that would not load. Failures are written *inside*
 * the node they happen to now, so there is no name to get wrong. The one string
 * left is the far end of a partition, because reachability is a property of a
 * pair.
 */
import type { Offered, Palette, Region } from './lab.ts';

/**
 * One service a node runs, and what goes wrong with it there.
 *
 * Both halves of a `runs:` entry, because the file has both: the key is the
 * service as gRPC names it, the value is the path to the file implementing it.
 * A form holding one of them would have to invent the other on save.
 */
export interface Runs {
  service: string;
  /** From the project root — `src/Shrinker.java`. */
  file: string;
  /**
   * What misbehaves here, keyed by rpc. Usually nothing.
   *
   * Per node rather than per service, which is the whole reason it is written
   * inside `runs:`: the same service can be the bad replica on one node and
   * fine on its peers, and a design that routes around it is a different design
   * from one that does not.
   */
  failures: Record<string, RpcFailure[]>;
}

/** One block of nodes that grow and shrink together. A single node is a pool of one. */
export interface Pool {
  name: string;
  /** 1 writes no `count:` at all, and the node is called after the pool. */
  count: number;
  /**
   * What the nodes in it are called: `prefix0`, `prefix1`.
   *
   * Usually the pool's own name, and then a pool of one keeps that name with no
   * digit on it. Set it apart — `workers` numbered `w0`, `w1` — and the count
   * and the prefix are both written, because the naming no longer follows from
   * the pool's name alone.
   */
  prefix: string;
  instance: string;
  /** Nodes are dealt round-robin over these. */
  zones: string[];
  runs: Runs[];
  /**
   * What happens to every node in this pool, each on its own draw.
   *
   * A `per: 2 refSeconds` on a pool of six is six nodes each failing every two
   * seconds, not one of the six doing it. That follows from where it is written
   * — the block is the node — and it is the reading that survives the pool
   * being resized, which is the one thing the probe grid does to it.
   */
  failures: Failure[];
  /**
   * A memory cap, or `null` for whatever the instance type says.
   *
   * Three states, not two: `null` inherits, a number overrides, and 0 means a
   * node that cannot hold anything. That is a legal simulation and a different
   * one, so the form must be able to write it and must not write it by
   * accident.
   */
  memoryMb: number | null;
  /** The same, for disk. Nothing is capped until something writes. */
  diskMb: number | null;
  /** Nodes in this pool that differ from their siblings. Usually none. */
  overrides: Override[];
}

/**
 * One node in a pool, set apart from the rest.
 *
 * An empty string or a null number falls back to the pool's own value, because
 * that is what a key the file leaves out means. A pool of eight where one is
 * half the size is the cheapest way to build a straggler; a pool where one has
 * a smaller disk shows a node filling up while its neighbours do not; and a
 * pool where one dies is the bad replica every design handles worst.
 */
export interface Override {
  /** The node's own name — `w2`, not `workers`. */
  node: string;
  /** '' keeps the pool's. */
  instance: string;
  /** '' keeps the zone the pool would have dealt it. */
  zone: string;
  memoryMb: number | null;
  diskMb: number | null;
  /** Replaces the pool's, rather than adding to it — the way `instance` does. */
  failures: Failure[];
}

/** Everything that can happen to a node, in the order the form offers them. */
export const FAILURE_KINDS = [
  'kill', 'freeze', 'degrade', 'restart', 'spotReclaim', 'partition', 'heal',
] as const;

export type FailureKind = (typeof FAILURE_KINDS)[number];

/** The two whose value is another node rather than the node it is written in. */
export const PAIRED: readonly FailureKind[] = ['partition', 'heal'];

/**
 * The three that can stand as a rate.
 *
 * The other four either bring a node back or take it away for good, and neither
 * is a thing that can go on happening — so they take an `at:` and the loader
 * refuses a `per:` on them.
 */
export const RATEABLE: readonly FailureKind[] = ['kill', 'freeze', 'degrade'];

/**
 * One thing that happens to the node it is written in.
 *
 * Exactly one of `atRefMs` and `perRefMs` is above zero: one is an instant, the
 * other a mean gap drawn exponentially. Both would be two failures written as
 * one, and neither is a failure that never fires — which reads in a trace
 * exactly like a system that survived it.
 *
 * Which of the rest means anything depends on `kind`, and only those are
 * written: a kill's `restartAfterRefMs`, a freeze's `forRefMs`, a degrade's
 * `factor`, a spot reclaim's `noticeRefMs`. The others are kept so switching
 * kind in the form does not lose what was typed under the previous one.
 */
export interface Failure {
  kind: FailureKind;
  atRefMs: number;
  perRefMs: number;
  /**
   * The far end — `partition` and `heal` only, and empty otherwise.
   *
   * Reachability is a property of a *pair*: both nodes stay alive, stay in the
   * registry and keep serving everybody else, and one caller sees nothing. No
   * other failure can make that point, and it is the only node name left in the
   * whole file.
   */
  other: string;
  /** How long a freeze holds. A degrade has no end — see `toYaml`. */
  forRefMs: number;
  /** How many times slower a degrade makes it. Above 1. */
  factor: number;
  /** Spot reclaim only: how long the warning comes before the node goes. */
  noticeRefMs: number;
  /** Kill and spot reclaim. 0 means it never comes back, a different exercise. */
  restartAfterRefMs: number;
}

export const RPC_FAILURE_KINDS = ['status', 'slow', 'drop'] as const;
export type RpcFailureKind = (typeof RPC_FAILURE_KINDS)[number];

/**
 * One thing that happens to one rpc on one node, one call in `perCalls`.
 *
 * Rates only, and that is not a gap: a failure that begins at an instant and
 * stays is a property of the node, and `degrade` already says it.
 */
export interface RpcFailure {
  kind: RpcFailureKind;
  /** A gRPC code name — `status` only, and never `OK`. */
  status: string;
  /** How many times its declared duration the call takes — `slow` only, above 1. */
  factor: number;
  perCalls: number;
}

/**
 * The medium the system talks over.
 *
 * All four zero is the same file as no `network:` key at all, which is what
 * `toYaml` then writes — and it is the default a simulation gets by saying
 * nothing: instant, lossless, and the reason a design that never sets this can
 * only be tested on a network that never costs it anything.
 */
export interface Net {
  /** What a call between two nodes in one zone costs. */
  sameZoneRefMs: number;
  /** And between zones — the only thing that makes placement a decision. */
  crossZoneRefMs: number;
  /** Spread around both, so no two calls take exactly as long. */
  jitterRefMs: number;
  /** 0 to 1. A dropped call, indistinguishable from a dead node to the caller. */
  loss: number;
}

export interface RetryRule {
  /** Dotted, as `retries:` names it — `lab.Worker.Map`. */
  method: string;
  attempts: number;
  backoffRefMs: number;
  /**
   * What the wait is multiplied by after each attempt. 1 is flat.
   *
   * Above 1 gives exponential backoff: each retry eases off a struggling node
   * instead of asking it again at the same fixed rate. A fixed rate turns one
   * slow node into an outage for the whole system.
   */
  multiplier: number;
  /** Retrying something the `.proto` did not declare idempotent, on purpose. */
  unsafe: boolean;
}

/**
 * What one rpc costs on the reference machine.
 *
 * Keyed on the **file**, not on the service: two implementations of one service
 * in one system is how a design is compared with another, and a cost keyed on
 * the service would say they take the same time. `runs` is the path, exactly as
 * the `runs:` entry writes it, so one string names one piece of code.
 */
export interface CostRule {
  runs: string;
  rpc: string;
  /** What a call takes regardless of what is in it. */
  fixedRefMs: number;
  /** What each unit the handler declares adds, in reference nanoseconds. */
  perUnitRefNs: number;
}

/**
 * The workload, at full size.
 *
 * Three fields, and the form draws all three. What it replaced was a block of
 * parts named by a driver class's own declaration, so the form had to ask the
 * code what rows to draw before it could draw any — and a form that could not
 * reach the code could not write an input at all.
 */
export interface Workload {
  /**
   * A file or a folder, from the project root. Empty means none.
   *
   * Left out, `Job.Load` generates the workload from the seed instead:
   * reproducible from it, different across a sweep, and free, because `Load` is
   * off the clock.
   */
  source: string;
  /** What one item is called, singular — frame, line, order. */
  unit: string;
  /** How many, at full size. The one number the engine varies. */
  count: number;
}

/** The biggest run the engine can measure — the top of its own probe ladder. */
export const BASE_UNITS = 8000;

export interface Draft {
  name: string;
  seed: number;
  /**
   * How many times bigger the design is than the run measuring it.
   *
   * 1 is not a scale model at all — it is the simulation itself, at the size
   * `input:` says. Above 1 it is a model of `scale × BASE_UNITS`, and everything
   * the engine needs to build that model — the probe ladder, the cluster sizes,
   * how fast the clock runs — follows from this one number rather than being
   * asked of the person writing the file. None of those is knowable in advance
   * by anybody, which is the whole reason to run a simulator.
   */
  scale: number;
  net: Net;
  pools: Pool[];
  retries: RetryRule[];
  /**
   * What each placed file's rpcs cost.
   *
   * Nothing in a handler declares this: a duration is a claim about the machine
   * a design would run on, so it belongs to the simulation — which leaves a
   * student's Java with no losim symbol in it at all. A system that declares
   * none of these runs, and every call in it is instant.
   */
  simulatedDuration: CostRule[];
  input: Workload;
}

/** One node, once the pools have been dealt out. */
export interface Node {
  name: string;
  pool: string;
  instance: string;
  zone: string;
  runs: Runs[];
}

/**
 * The nodes a draft would produce, named the way the loader names them.
 *
 * A pool of one keeps the pool's own name; a pool of more is `prefix0`,
 * `prefix1`. Overrides are aimed at these names, and so is the far end of a
 * partition, so getting the naming wrong here is a file that will not load.
 */
export function nodes(draft: Draft): Node[] {
  const out: Node[] = [];
  for (const p of draft.pools) {
    const n = Math.max(1, Math.round(p.count));
    const zones = p.zones.length ? p.zones : ['eu-central-1a'];
    // The same condition `toYaml` writes `count:` under, because the two have to
    // agree about naming or an override points at a node that is not there.
    const numbered = n > 1 || p.prefix !== p.name;
    for (let i = 0; i < n; i++) {
      const name = numbered ? `${p.prefix}${i}` : p.name;
      const over = p.overrides.find((o) => o.node === name);
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
 * How far apart two nodes are, in the only four steps a bill distinguishes.
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
  const ms = nodes(draft);
  for (let i = 0; i < ms.length; i++) {
    for (let j = i + 1; j < ms.length; j++) out[linkOf(ms[i].zone, ms[j].zone, regions)]++;
  }
  return out;
}

/**
 * What this system costs per hour, on the catalogue's own default prices.
 *
 * A rate, not a bill. What a simulation costs is what `losim bill` says after it
 * has happened, against a price list this app has never seen — and a second
 * number here that looked like a prediction would be a second accountant. This
 * one is a property of the nodes you drew, and it is true before anything runs.
 */
export function perHour(draft: Draft, palette: Palette): number {
  let total = 0;
  for (const m of nodes(draft)) {
    total += palette.instances.find((i) => i.name === m.instance)?.onDemandPerHour ?? 0;
  }
  return total;
}

/** Files the code offers that no node has been given. */
export function unplaced(draft: Draft, palette: Palette): Offered[] {
  const placed = new Set(draft.pools.flatMap((p) => p.runs.map((r) => r.file)));
  return palette.services.filter((s) => !placed.has(s.file));
}

/** The one entry a simulation starts in, or none — `losim.Job`, wherever it is placed. */
export function entryOf(draft: Draft): Runs | null {
  for (const p of draft.pools) {
    for (const r of p.runs) if (r.service === 'losim.Job') return r;
  }
  return null;
}

/* --------------------------------------------------------------------- YAML */

const q = (s: string) => (/^[A-Za-z_][\w./-]*$/.test(s) ? s : JSON.stringify(s));

/** One node-level failure, as the one line the loader reads. */
function failureLine(f: Failure): string {
  // Each kind writes only what it actually obeys. The loader refuses the rest
  // rather than dropping it — a `for:` on a degrade would be a number in the
  // file that nothing schedules an end to, and the node stays slow while the
  // file says otherwise.
  let tail = '';
  if (f.kind === 'kill' && f.restartAfterRefMs > 0) {
    tail = `, restartAfter: ${f.restartAfterRefMs} refMs`;
  } else if (f.kind === 'freeze') {
    tail = `, for: ${f.forRefMs} refMs`;
  } else if (f.kind === 'spotReclaim') {
    // The notice is the whole lesson, so it is always written — a spot node
    // that gives no warning is just a kill by another name.
    tail = `, notice: ${f.noticeRefMs} refMs`;
    if (f.restartAfterRefMs > 0) tail += `, restartAfter: ${f.restartAfterRefMs} refMs`;
  }
  // The value says what the kind needs: a factor for a degrade, the far end for
  // a pair, and `true` for the four that simply happen.
  const value = f.kind === 'degrade' ? String(f.factor)
    : PAIRED.includes(f.kind) ? q(f.other)
    : 'true';
  const when = f.perRefMs > 0 ? `per: ${f.perRefMs} refMs` : `at: ${f.atRefMs} refMs`;
  return `{ ${f.kind}: ${value}, ${when}${tail} }`;
}

/** One rpc-level failure. Rates only, counted in calls. */
function rpcFailureLine(f: RpcFailure): string {
  const what = f.kind === 'status' ? `status: ${f.status}`
    : f.kind === 'slow' ? `slow: ${f.factor}`
    : 'drop: true';
  return `{ ${what}, per: ${Math.round(f.perCalls)} calls }`;
}

/**
 * The draft, as the file that will be written.
 *
 * Shown in full while it is being composed, because the file *is* the
 * simulation: a student who only ever sees a form learns a form, and what they
 * have to be able to read by the end of the course is the YAML their classmate
 * sent them.
 */
export function toYaml(draft: Draft): string {
  const L: string[] = [];
  L.push(`# ${draft.name}.yaml — written by the lab console`);
  L.push(`seed: ${Math.round(draft.seed)}`);
  // Omitted at 1, which is what a simulation gets by saying nothing: one run of
  // itself, and nothing projected from it.
  if (draft.scale > 1) L.push(`scale: ${draft.scale}`);
  // Only the numbers that are actually set, and no key at all when none is:
  // every one of these defaults to 0, so `network: { loss: 0 }` and silence are
  // the same simulation, and writing the silent ones out on every save would put
  // four lines of nothing into every file the console touches.
  const n = draft.net;
  const net: string[] = [];
  if (n.sameZoneRefMs > 0) net.push(`sameZone: ${n.sameZoneRefMs} refMs`);
  if (n.crossZoneRefMs > 0) net.push(`crossZone: ${n.crossZoneRefMs} refMs`);
  if (n.jitterRefMs > 0) net.push(`jitter: ${n.jitterRefMs} refMs`);
  if (n.loss > 0) net.push(`loss: ${n.loss}`);
  if (net.length) L.push(`network: { ${net.join(', ')} }`);
  L.push('');
  L.push('nodes:');
  for (const p of draft.pools) {
    const count = Math.max(1, Math.round(p.count));
    L.push(`  ${q(p.name)}:`);
    L.push(`    instance: ${p.instance}`);
    L.push(
      p.zones.length === 1
        ? `    zone: ${p.zones[0]}`
        : `    zone: [${p.zones.join(', ')}]`,
    );
    // A pool of one is written without a count, so its node keeps the pool's own
    // name — `master`, not `master0`. Overrides and partitions name nodes.
    //
    // Unless the prefix says otherwise: a pool called `workers` whose nodes are
    // `w0`, `w1` needs both keys written even at a count of one, because the
    // naming no longer follows from the pool's name.
    if (count > 1 || p.prefix !== p.name) {
      L.push(`    count: ${count}`);
      L.push(`    prefix: ${q(p.prefix)}`);
    }
    if (p.runs.length) {
      // Shorthand when nothing goes wrong here, which is almost every entry;
      // longhand when something does, and then the `file:` is the same string
      // the shorthand would have been.
      const plain = p.runs.every((r) => !Object.keys(r.failures).length);
      if (plain) {
        L.push(`    runs: { ${p.runs.map((r) => `${q(r.service)}: ${q(r.file)}`).join(', ')} }`);
      } else {
        L.push('    runs:');
        for (const r of p.runs) {
          const rpcs = Object.entries(r.failures).filter(([, fs]) => fs.length);
          if (!rpcs.length) {
            L.push(`      ${q(r.service)}: ${q(r.file)}`);
            continue;
          }
          L.push(`      ${q(r.service)}:`);
          L.push(`        file: ${q(r.file)}`);
          L.push('        failures:');
          for (const [rpc, fs] of rpcs) {
            L.push(`          ${q(rpc)}:`);
            for (const f of fs) L.push(`            - ${rpcFailureLine(f)}`);
          }
        }
      }
    }
    // A cap the pool never set is the instance type's own. Writing `memoryMb: 0`
    // for it would instead be a node that cannot hold anything.
    if (p.memoryMb !== null) L.push(`    memoryMb: ${p.memoryMb}`);
    if (p.diskMb !== null) L.push(`    diskMb: ${p.diskMb}`);
    if (p.failures.length) {
      L.push('    failures:');
      for (const f of p.failures) L.push(`      - ${failureLine(f)}`);
    }
    if (p.overrides.length) {
      L.push('    overrides:');
      for (const o of p.overrides) {
        // Only what this node actually differs in. An override that repeated the
        // pool's own values would be four lines saying nothing, and the point of
        // the block is that one node is not like the others.
        const bits: string[] = [];
        if (o.instance) bits.push(`instance: ${o.instance}`);
        if (o.zone) bits.push(`zone: ${o.zone}`);
        if (o.memoryMb !== null) bits.push(`memoryMb: ${o.memoryMb}`);
        if (o.diskMb !== null) bits.push(`diskMb: ${o.diskMb}`);
        if (!o.failures.length) {
          L.push(`      ${q(o.node)}: { ${bits.join(', ')} }`);
          continue;
        }
        L.push(`      ${q(o.node)}:`);
        for (const b of bits) L.push(`        ${b}`);
        L.push('        failures:');
        for (const f of o.failures) L.push(`          - ${failureLine(f)}`);
      }
    }
  }
  L.push('');
  L.push('input:');
  if (draft.input.source) L.push(`  source: ${q(draft.input.source)}`);
  L.push(`  unit:   ${q(draft.input.unit)}`);
  L.push(`  count:  ${Math.round(draft.input.count)}`);
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
  const priced = draft.simulatedDuration.filter((c) => c.fixedRefMs > 0 || c.perUnitRefNs > 0);
  if (priced.length) {
    L.push('');
    L.push('simulatedDuration:');
    // Grouped by the file, which is how the file is written and read: one
    // heading per thing that runs, its rpcs under it.
    for (const runs of [...new Set(priced.map((c) => c.runs))]) {
      const rows = priced.filter((c) => c.runs === runs).map((c) => {
        const perUnit = c.perUnitRefNs > 0 ? `, perUnit: ${c.perUnitRefNs} refNs` : '';
        return `${q(c.rpc)}: { fixed: ${c.fixedRefMs} refMs${perUnit} }`;
      });
      L.push(`  ${q(runs)}: { ${rows.join(', ')} }`);
    }
  }
  return L.join('\n') + '\n';
}

/**
 * A draft to start from, built out of what this system actually offers.
 *
 * One node for the entry and a pool for everything else, because that is what
 * almost every simulation in this course is — and starting from an empty form
 * means starting by reading the manual to find out what a pool is called.
 */
export function firstDraft(palette: Palette): Draft {
  const zone = palette.regions[0]?.zones[0] ?? 'eu-central-1a';
  const entry = palette.services.find((s) => s.entry);
  const worker = palette.services.find((s) => !s.entry);
  const has = (n: string) => palette.instances.some((i) => i.name === n);
  const runs = (o: Offered | undefined): Runs[] =>
    o ? [{ service: o.service, file: o.file, failures: {} }] : [];
  return {
    name: 'authored',
    seed: 1,
    // One run of itself: what a simulation gets by saying nothing, and the only
    // starting point that promises nothing the engine has not measured.
    scale: 1,
    // Instant and lossless, which is what a simulation that says nothing gets.
    net: { sameZoneRefMs: 0, crossZoneRefMs: 0, jitterRefMs: 0, loss: 0 },
    pools: [
      {
        name: 'entry',
        count: 1,
        prefix: 'entry',
        instance: has('m5.large') ? 'm5.large' : (palette.instances[0]?.name ?? 'm5.large'),
        zones: [zone],
        runs: runs(entry),
        failures: [],
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
        runs: runs(worker),
        failures: [],
        memoryMb: null,
        diskMb: null,
        overrides: [],
      },
    ],
    retries: [],
    // Every rpc the one placed file serves, at zero — a row to fill in rather
    // than a block to remember. A system that leaves them at zero is one where
    // every call is instant, which the form says out loud beside them.
    simulatedDuration: (worker?.rpcs ?? []).map((m) => ({
      runs: worker!.file, rpc: m.name, fixedRefMs: 0, perUnitRefNs: 0,
    })),
    // One item, which is the smallest legal workload, and a unit nobody has
    // named yet. Both are the form's to change and neither can be left out.
    input: { source: '', unit: 'item', count: 1 },
  };
}
