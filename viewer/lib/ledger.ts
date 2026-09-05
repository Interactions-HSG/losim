/**
 * What the run has cost *so far*, and whose fault it is.
 *
 * `losim bill` says what a run cost. That is the wrong tense for a film: the
 * whole point of watching `mr-cascade` is seeing the incidents bucket fill up
 * partway through, while it is still happening, and a total at the end cannot
 * show that.
 *
 * So this accrues. It does **not** re-price anything — every amount here is a
 * line the CLI already computed, from the rates it already used, and all that is
 * added is a shape saying how that line arrives over time. That is deliberate:
 * two implementations of a pricing model are two accountants who will eventually
 * disagree, and the one thing that stops it is that there is only one of them.
 *
 * **Exact at the end, approximate in between**, and the approximation is only
 * ever in *when*. Every line's shape is normalised to reach exactly its billed
 * amount at the end of the run, so the closing total equals `losim bill` to the
 * rappen — which is a thing to check rather than to claim (S7).
 *
 * ## Attribution, which is not re-pricing either
 *
 * Pointing at a machine should light up its money. That needs a second question
 * answered — *whose line is this?* — and the same discipline applies: the amount
 * never changes, only the claim about who is answerable for it. Three kinds:
 *
 * - **its own** — a capacity line names one machine and belongs to it entirely
 * - **its share** — a fleet total split by a quantity the trace already holds,
 *   so cross-zone egress is split by how many cross-zone bytes each machine
 *   actually sent
 * - **nobody's** — the late-finish penalty belongs to the *job*. Spreading it
 *   over nine machines would invent a claim nothing supports, so it is left
 *   unattributed and says so
 *
 * The classification is matched against the exact line labels `losim.price.Bill`
 * writes. That coupling is on purpose and it is checked: an unrecognised line is
 * `unknown`, attributed to nobody rather than guessed at.
 */
import type { Trace } from './trace.ts';

/**
 * The four costs, and no revenue.
 *
 * What a run earns is not a property of the run — it depends on what the
 * service is worth to somebody, which is a business question this course does
 * not answer, and a revenue line would have to answer it anyway with a number
 * somebody merely picked. What a design costs is computed from what actually
 * happened, and that is the number worth arguing about.
 */
export const BUCKETS = ['build', 'capacity', 'consumption', 'incidents'] as const;
export type Bucket = (typeof BUCKETS)[number];

export interface BillLine {
  bucket: Bucket;
  what: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  amount: number;
  why?: string;
}

export interface BillJson {
  rates: Record<string, number | string>;
  observed: {
    currency: string;
    buckets: Record<Bucket, number>;
    cost: number;
    lines: BillLine[];
  };
  projected?: BillJson['observed'];
}

/** One line of the bill, as it stands at this instant. */
export interface LedgerLine {
  line: BillLine;
  /** How much of it has arrived by now. */
  sofar: number;
  /** How much of `sofar` the focused machine is answerable for. 0 when none. */
  mine: number;
  /** Why it is theirs, in words. Empty when it is not theirs at all. */
  why: string;
}

export interface Ledger {
  currency: string;
  /** Each bucket, as it stands at this instant. */
  buckets: Record<Bucket, number>;
  cost: number;
  /** What the whole run comes to, for the bar to be drawn against. */
  finalCost: number;
  /** Which lines have started arriving, largest first. */
  lines: LedgerLine[];
  /** The machine being pointed at, and what it is answerable for. */
  focus: Focus | null;
}

export interface Focus {
  name: string;
  /** Its share of `cost` so far. */
  cost: number;
  /** Its share of the whole run's cost. */
  finalCost: number;
  buckets: Record<Bucket, number>;
}

/** What a bill line is, once matched against what `Bill.java` writes. */
type Kind =
  | { k: 'capacity'; machine: string }
  | { k: 'egress' }
  | { k: 'storage' }
  | { k: 'timeouts' }
  | { k: 'lost' }
  | { k: 'filled' }
  | { k: 'late' }
  | { k: 'build' }
  | { k: 'unknown' };

/**
 * Which line is which.
 *
 * Matched on the label because the label is what the bill carries — there is no
 * machine-readable tag on a line, and inventing one would mean changing the
 * trace contract for the viewer's convenience (D9). The fallbacks are loose so a
 * reworded label degrades to a worse shape rather than to a wrong one.
 */
function classify(line: BillLine): Kind {
  const what = line.what;
  switch (line.bucket) {
    case 'capacity': {
      // `m0 (c5.large)`, or `m0 (c5.large, spot)`.
      const cut = what.indexOf(' (');
      return { k: 'capacity', machine: cut < 0 ? what : what.slice(0, cut) };
    }
    case 'consumption':
      return /storage|disk|spill/i.test(what) ? { k: 'storage' } : { k: 'egress' };
    case 'incidents':
      if (/in time|deadline|rerun|retr/i.test(what)) return { k: 'timeouts' };
      if (/lost/i.test(what)) return { k: 'lost' };
      if (/filled|full|memory/i.test(what)) return { k: 'filled' };
      if (/late|sla|service level/i.test(what)) return { k: 'late' };
      return { k: 'unknown' };
    case 'build':
      return { k: 'build' };
  }
}

/** Which events each incident line is counting, matching `Bill.java` exactly. */
const COUNTS: Record<string, string[]> = {
  timeouts: ['rpc_timeout'],
  lost: ['kill', 'spot_notice'],
  filled: ['oom', 'disk_full'],
};

export class LedgerModel {
  readonly currency: string;
  readonly finalCost: number;
  private readonly lines: BillLine[];
  private readonly shapes: ((t: number) => number)[];
  /** Per line, how much of it each machine is answerable for. */
  private readonly blame: Map<string, number>[];
  /** Per line, why — in words, for the machine it is being shown to. */
  private readonly why: string[];

  constructor(trace: Trace, bill: BillJson) {
    const account = bill.observed;
    this.currency = account.currency;
    this.finalCost = account.cost;
    this.lines = account.lines;

    const duration = trace.duration;
    // The bill priced the *job*, so the shapes that follow the bill's own
    // arithmetic follow the job's clock — the film may run a moment longer.
    const job = trace.jobRefMs;
    const minSeconds = Number(bill.rates['billingMinimumSeconds'] ?? 60);
    const slaSeconds = Number(bill.rates['slaSeconds'] ?? 0);
    const done = doneAt(trace) ?? duration;
    const egress = cumulative(trace, 'bytesOutMb');
    const storage = integral(trace, 'diskMb');
    const when = new Map<string, number[]>();
    for (const e of trace.events) {
      const kind = String(e.kind);
      const list = when.get(kind) ?? [];
      list.push(Number(e.t ?? 0));
      when.set(kind, list);
    }

    const kinds = account.lines.map(classify);

    this.shapes = kinds.map((kind) => {
      switch (kind.k) {
        case 'build':
          // Engineering time, spread over the design's life. It is carried, so it
          // arrives evenly rather than at any particular moment.
          return (t: number) => clamp(t / duration);

        case 'capacity': {
          // **Committed before anything happens.** You pay for a machine from the
          // moment you ask for it, with a floor — so a five-second job on forty
          // machines has already bought a minute of forty machines by the time the
          // first call is made, and the line is flat from t=0. A run that outlasts
          // the floor starts accruing again beyond it.
          const runSeconds = job / 1000;
          const billed = Math.max(minSeconds, runSeconds);
          return (t: number) => clamp(Math.max(minSeconds, t / 1000) / billed);
        }

        // Consumption follows the thing being consumed. Egress tracks bytes
        // leaving; storage tracks disk held *over time*, which is why it is an
        // integral and not a level: a gigabyte for an hour is not a gigabyte.
        case 'egress':
          return (t: number) => egress(t);
        case 'storage':
          return (t: number) => storage(t);

        case 'late': {
          // A penalty per second past the service level, so it starts at nothing,
          // begins at the instant the deadline passes, and climbs from there. As a
          // step at the end it would say the job was late all along, which is the
          // opposite of what it is for.
          const over = job / 1000 - slaSeconds;
          if (over <= 0) return () => 1;
          return (t: number) => clamp((t / 1000 - slaSeconds) / over);
        }

        case 'timeouts':
        case 'lost':
        case 'filled': {
          // Arrives in steps, at the instants things actually broke — and counting
          // exactly the events the bill counted, so the number of steps is the
          // number on the line.
          const times = COUNTS[kind.k].flatMap((k) => when.get(k) ?? []).sort((a, b) => a - b);
          if (!times.length) return () => 1;
          return (t: number) => times.filter((x) => x <= t).length / times.length;
        }

        case 'unknown':
          // Something the bill grew that this does not know the shape of. Straight
          // line: wrong about *when*, right about the total, and visibly neither
          // invented nor dropped.
          return (t: number) => clamp(t / duration);
      }
    });

    // ------------------------------------------------------------- attribution

    const share = (of: (name: string) => number): Map<string, number> => {
      const out = new Map<string, number>();
      let total = 0;
      for (const m of trace.machines) total += Math.max(0, of(m.name));
      if (total <= 0) return out;
      for (const m of trace.machines) {
        const v = Math.max(0, of(m.name)) / total;
        if (v > 0) out.set(m.name, v);
      }
      return out;
    };

    const byEvent = (kinds: string[], blame: (e: Record<string, unknown>) => string): Map<string, number> => {
      const count = new Map<string, number>();
      let total = 0;
      for (const e of trace.events) {
        if (!kinds.includes(String(e.kind))) continue;
        const who = blame(e as unknown as Record<string, unknown>);
        if (!who) continue;
        count.set(who, (count.get(who) ?? 0) + 1);
        total++;
      }
      if (!total) return new Map();
      return new Map([...count].map(([k, v]) => [k, v / total]));
    };

    const raw = (name: string, key: string): number =>
      Number(trace.byName.get(name)?.raw[key] ?? 0);

    // The bill charges storage for **the worst machine's** spill, so it belongs
    // to that machine alone. Splitting it across the fleet would be a different
    // and much smaller claim about each of them.
    let worst = '';
    let mostDisk = 0;
    for (const m of trace.machines) {
      const held = raw(m.name, 'diskMb');
      if (held > mostDisk) {
        mostDisk = held;
        worst = m.name;
      }
    }

    // Build is priced per *distinct* service, so a service two machines offer is
    // one line item they each half-carry.
    const offeredBy = new Map<string, string[]>();
    for (const m of trace.machines) {
      for (const s of m.serves) offeredBy.set(s, [...(offeredBy.get(s) ?? []), m.name]);
    }
    const services = Math.max(1, offeredBy.size);

    this.blame = kinds.map((kind) => {
      switch (kind.k) {
        case 'capacity':
          return new Map([[kind.machine, 1]]);
        case 'egress':
          return share((n) => raw(n, 'crossZoneMb'));
        case 'storage':
          return worst ? new Map([[worst, 1]]) : new Map();
        case 'timeouts':
          // The callee is the one that did not answer, and the bill's words are
          // "a machine did not answer inside its deadline". The event is written
          // by the caller, so the machine to charge is the one it was calling.
          return byEvent(COUNTS.timeouts, (e) =>
            String((e.detail as Record<string, unknown>)?.['to'] ?? e['vm'] ?? ''),
          );
        case 'lost':
          return byEvent(COUNTS.lost, (e) => String(e['vm'] ?? ''));
        case 'filled':
          return byEvent(COUNTS.filled, (e) => String(e['vm'] ?? ''));
        case 'build': {
          const out = new Map<string, number>();
          for (const [, holders] of offeredBy) {
            for (const h of holders) {
              out.set(h, (out.get(h) ?? 0) + 1 / holders.length / services);
            }
          }
          return out;
        }
        // The job's, not any machine's. Left empty on purpose.
        case 'late':
        case 'unknown':
          return new Map<string, number>();
      }
    });

    this.why = kinds.map((kind) => {
      switch (kind.k) {
        case 'capacity':
          return 'its own reservation, whether it was busy or idle';
        case 'egress':
          return 'its share of the bytes that crossed a zone';
        case 'storage':
          return 'the whole line — this is the worst machine’s spill, and it is the worst machine';
        case 'timeouts':
          return 'calls it did not answer in time';
        case 'lost':
          return 'it went away mid-job';
        case 'filled':
          return 'it ran out of what it was given';
        case 'build':
          return 'its share of carrying the services it offers';
        case 'late':
        case 'unknown':
          return '';
      }
    });
  }

  /** Every machine that carries any of the bill, so the film can say who does not. */
  answerable(): Set<string> {
    const out = new Set<string>();
    for (const m of this.blame) for (const k of m.keys()) out.add(k);
    return out;
  }

  at(t: number, focus: string | null = null): Ledger {
    const buckets: Record<Bucket, number> = zero();
    const mineBuckets: Record<Bucket, number> = zero();
    const lines: LedgerLine[] = [];
    let mineFinal = 0;

    for (let i = 0; i < this.lines.length; i++) {
      const line = this.lines[i];
      const sofar = line.amount * this.shapes[i](t);
      buckets[line.bucket] += sofar;
      const cut = focus ? (this.blame[i].get(focus) ?? 0) : 0;
      const mine = sofar * cut;
      mineBuckets[line.bucket] += mine;
      mineFinal += line.amount * cut;
      if (sofar > 0 || mine > 0) lines.push({ line, sofar, mine, why: cut > 0 ? this.why[i] : '' });
    }

    // Theirs first, then by size: pointing at a machine should bring its own
    // money to the top rather than leave it to be hunted for down the table.
    lines.sort((a, b) => b.mine - a.mine || b.sofar - a.sofar);
    const cost = buckets.build + buckets.capacity + buckets.consumption + buckets.incidents;
    return {
      currency: this.currency,
      buckets,
      cost,
      finalCost: this.finalCost,
      lines,
      focus: focus
        ? {
            name: focus,
            cost:
              mineBuckets.build +
              mineBuckets.capacity +
              mineBuckets.consumption +
              mineBuckets.incidents,
            finalCost: mineFinal,
            buckets: mineBuckets,
          }
        : null,
    };
  }
}

function zero(): Record<Bucket, number> {
  return { build: 0, capacity: 0, consumption: 0, incidents: 0 };
}

function clamp(v: number): number {
  return Math.max(0, Math.min(1, v));
}

/** When the job finished, or nothing if it never did. */
function doneAt(trace: Trace): number | null {
  const done = trace.events.find((e) => e.kind === 'done');
  if (done) return Number(done.t ?? 0);
  const job = trace.spans.find((s) => s.kind === 'job');
  return job && job.t1 >= 0 && job.status === 'OK' ? job.t1 : null;
}

/** A fleet-wide running total of one series, as a share of its final value. */
function cumulative(trace: Trace, metric: string): (t: number) => number {
  const times = trace.series(trace.machines[0]?.name ?? '', metric).t;
  const total = new Array(times.length).fill(0);
  for (const m of trace.machines) {
    const v = trace.series(m.name, metric).v;
    for (let i = 0; i < total.length; i++) total[i] += v[Math.min(i, v.length - 1)] ?? 0;
  }
  const end = total[total.length - 1] || 1;
  return (t: number) => clamp(sampleAt(times, total, t) / end);
}

/**
 * The area under a series, as a share of its final area.
 *
 * Storage is priced per gigabyte-month, so what is owed at any instant is how
 * much has been held *and for how long* — a level read straight off would charge
 * a machine that filled its disk at the very end as though it had held it all
 * run.
 */
function integral(trace: Trace, metric: string): (t: number) => number {
  const times = trace.series(trace.machines[0]?.name ?? '', metric).t;
  const level = new Array(times.length).fill(0);
  for (const m of trace.machines) {
    const v = trace.series(m.name, metric).v;
    for (let i = 0; i < level.length; i++) level[i] += v[Math.min(i, v.length - 1)] ?? 0;
  }
  const area = new Array(times.length).fill(0);
  for (let i = 1; i < times.length; i++) {
    area[i] = area[i - 1] + ((level[i] + level[i - 1]) / 2) * (times[i] - times[i - 1]);
  }
  const end = area[area.length - 1] || 1;
  return (t: number) => clamp(sampleAt(times, area, t) / end);
}

function sampleAt(times: number[], values: number[], t: number): number {
  if (!times.length) return 0;
  if (t <= times[0]) return values[0];
  if (t >= times[times.length - 1]) return values[values.length - 1];
  let lo = 0;
  let hi = times.length - 1;
  while (lo < hi - 1) {
    const mid = (lo + hi) >> 1;
    if (times[mid] <= t) lo = mid;
    else hi = mid;
  }
  const span = times[hi] - times[lo] || 1;
  const u = (t - times[lo]) / span;
  return values[lo] + (values[hi] - values[lo]) * u;
}

export async function loadBill(href: string): Promise<BillJson | null> {
  try {
    const res = await fetch(href, { cache: 'no-store' });
    if (!res.ok) return null;
    return (await res.json()) as BillJson;
  } catch {
    return null;
  }
}

export function money(v: number, currency: string): string {
  const sign = v < 0 ? '-' : '';
  const a = Math.abs(v);
  return `${sign}${currency} ${a < 10 ? a.toFixed(4) : a.toFixed(2)}`;
}
