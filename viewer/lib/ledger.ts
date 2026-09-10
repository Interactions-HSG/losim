/**
 * What the run has cost *so far*, and whose fault it is.
 *
 * `dissaly bill` says what a run cost. That is the wrong tense for a film: the
 * whole point of watching `mr-cascade` is seeing capacity commit before anything
 * happens and the failures land while it is still happening, and a total at the
 * end cannot show that.
 *
 * So this accrues. It does **not** re-price anything — every amount here is a
 * line the CLI already computed, from the rates it already used, and all that is
 * added is a shape saying how that line arrives over time. That is deliberate:
 * two implementations of a pricing model are two accountants who will eventually
 * disagree, and the one thing that stops it is that there is only one of them.
 *
 * **Exact at the end, approximate in between**, and the approximation is only
 * ever in *when*. Every line's shape is normalised to reach exactly its billed
 * amount at the end of the run, so the closing total equals `dissaly bill` to the
 * rappen — which is a thing to check rather than to claim (S7).
 *
 * ## Attribution, which is not re-pricing either
 *
 * Pointing at a node should light up its money. That needs a second question
 * answered — *whose line is this?* — and the same discipline applies: the amount
 * never changes, only the claim about who is answerable for it. Three kinds:
 *
 * - **its own** — a capacity line names one node and belongs to it entirely
 * - **its share** — a cluster total split by a quantity the trace already holds,
 *   so cross-zone egress is split by how many cross-zone bytes each node
 *   actually sent
 * - **nobody's** — the late-finish penalty belongs to the *job*. Spreading it
 *   over nine nodes would invent a claim nothing supports, so it is left
 *   unattributed and says so
 *
 * The classification is matched against the exact line labels `dissaly.price.Bill`
 * writes. That coupling is on purpose and it is checked: an unrecognised line is
 * `unknown`, attributed to nobody rather than guessed at.
 */
import { ALARM } from './design.ts';
import { groupText, type Trace } from './trace.ts';

/** What a design costs, computed from what actually happened. */
export const BUCKETS = ['build', 'capacity', 'consumption'] as const;
export type Bucket = (typeof BUCKETS)[number];

const IS_BUCKET = new Set<string>(BUCKETS);

/**
 * One palette for the buckets, wherever they are drawn.
 *
 * Beside the list it colours rather than in a view, so that two pages showing
 * the same bucket cannot disagree about which colour it is.
 */
export const COLOUR: Record<Bucket, string> = {
  build: '#8E6BA8',
  capacity: '#3C6E9F',
  consumption: '#3E8E8A',
};

/** What broke is not a bucket, but it is drawn, and it is drawn in the alarm colour. */
export const COUNTED_COLOUR = ALARM;

export interface BillLine {
  bucket: Bucket;
  what: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  amount: number;
  why?: string;
}

/**
 * Something that happened, counted and not charged.
 *
 * A timeout, a lost machine, a late finish, the bytes a single-zone cluster
 * carried for nothing. The bill used to put a franc a second on being late and
 * two rappen on a timeout, and on a run where everything failed that invention
 * was 99% of the total — so the quantities are carried and the prices are not.
 */
export interface CountedLine {
  what: string;
  quantity: number;
  unit: string;
  why?: string;
}

/** One account: what a set of quantities came to, at the rates in force. */
export interface Account {
  currency: string;
  buckets: Record<Bucket, number>;
  cost: number;
  lines: BillLine[];
  /** What happened, counted. Absent on a run where nothing did. */
  counted?: CountedLine[];
  /**
   * Lines the second account could not be written at all, and why.
   *
   * A projected bill is the observed one re-priced over projected quantities, so
   * a resource the engine would not project takes its line off the bill with it.
   * The line is named and the engine's reason is carried verbatim — a total that
   * quietly dropped its largest line would be a smaller number that looked like
   * a cheaper design.
   */
  unpriceable?: Record<string, string>;
}

export interface BillJson {
  rates: Record<string, number | string>;
  observed: Account;
  /**
   * The same design at the size it was a model of. Absent where the run was
   * itself rather than a model of anything.
   *
   * Not accrued, and it must not be: there is no *when* to accrue it over. The
   * run that happened is the small one, and drawing a projected total against
   * the probe's clock would put a curve on the screen that nothing measured.
   */
  projected?: Account;
}

/** One line of the bill, as it stands at this instant. */
export interface LedgerLine {
  line: BillLine;
  /** How much of it has arrived by now. */
  sofar: number;
  /** How much of `sofar` the focused node is answerable for. 0 when none. */
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
  /** What had happened by now, counted rather than charged. */
  counted: CountedNow[];
  /** The node being pointed at, and what it is answerable for. */
  focus: Focus | null;
}

/** One counted line, as it stood at this instant. */
export interface CountedNow {
  what: string;
  unit: string;
  /** How many by now. */
  quantity: number;
  /** How many by the end of the run. */
  total: number;
  why?: string;
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
  | { k: 'capacity'; node: string }
  | { k: 'egress' }
  | { k: 'storage' }
  | { k: 'build' }
  | { k: 'unknown' };

/**
 * Which line is which.
 *
 * Matched on the label because the label is what the bill carries — there is no
 * node-readable tag on a line, and inventing one would mean changing the
 * trace contract for the viewer's convenience (D9). The fallbacks are loose so a
 * reworded label degrades to a worse shape rather than to a wrong one.
 */
function classify(line: BillLine): Kind {
  const what = line.what;
  switch (line.bucket) {
    case 'capacity': {
      // `m0 (c5.large)`, or `m0 (c5.large, spot)`.
      const cut = what.indexOf(' (');
      return { k: 'capacity', node: cut < 0 ? what : what.slice(0, cut) };
    }
    case 'consumption':
      return /storage|disk|spill/i.test(what) ? { k: 'storage' } : { k: 'egress' };
    case 'build':
      return { k: 'build' };
    default:
      // A bucket this viewer does not know — a bill written by an older engine,
      // most likely, which had a fourth one. Drawn as a straight line rather
      // than dropped, because a total quietly missing a line is worse.
      return { k: 'unknown' };
  }
}

/** Which events each counted line is counting, matching `Bill.java` exactly. */
const COUNTS: Record<string, string[]> = {
  timeouts: ['rpc_timeout'],
  lost: ['kill', 'spot_notice'],
  filled: ['oom', 'disk_full'],
};

/** Which of those a counted line is, read off the label the bill carries. */
export function counting(what: string): keyof typeof COUNTS | 'late' | 'traffic' | 'unknown' {
  if (/in time|deadline|answer/i.test(what)) return 'timeouts';
  if (/lost/i.test(what)) return 'lost';
  if (/filled|full|memory/i.test(what)) return 'filled';
  if (/late|sla|service level/i.test(what)) return 'late';
  if (/traffic|egress|bytes/i.test(what)) return 'traffic';
  return 'unknown';
}

export class LedgerModel {
  readonly currency: string;
  readonly finalCost: number;
  private readonly lines: BillLine[];
  private readonly shapes: ((t: number) => number)[];
  /** Per line, how much of it each node is answerable for. */
  private readonly blame: Map<string, number>[];
  /** Per line, why — in words, for the node it is being shown to. */
  private readonly why: string[];
  /** What happened, and how much of each had happened by `t`. */
  private readonly counted: CountedLine[];
  private readonly countShapes: ((t: number) => number)[];

  constructor(trace: Trace, bill: BillJson) {
    const account = bill.observed;
    this.currency = account.currency;
    this.finalCost = account.cost;
    this.lines = account.lines;

    const duration = trace.duration;
    // The bill priced what `dissaly.Job/Run` was open for, so the shapes that
    // follow the bill's own arithmetic follow that clock — the film may run a
    // moment longer.
    const job = trace.billedRefMs;
    const minSeconds = Number(bill.rates['billingMinimumSeconds'] ?? 60);
    const slaSeconds = Number(bill.rates['slaSeconds'] ?? 0);
    const done = doneAt(trace) ?? duration;
    const bytes = cumulative(trace, 'bytesOutMb');
    // The channel where there is one, a straight line where there is not.
    // `t13-off` runs with telemetry turned off: the bill still knows what the
    // cluster carried, because that is a counter on the machine rather than a
    // series, but there is nothing to follow it with. Followed anyway, the line
    // arrives at nothing and the page shows a quantity that never turns up —
    // which is how the counted traffic on that run closed at zero of 0.85 MB.
    const egress = bytes(duration) > 0 ? bytes : (t: number) => clamp(t / duration);
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
          // **Committed before anything happens.** You pay for a node from the
          // moment you ask for it, with a floor — so a five-second job on forty
          // nodes has already bought a minute of forty nodes by the time the
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

        case 'unknown':
          // Something the bill grew that this does not know the shape of. Straight
          // line: wrong about *when*, right about the total, and visibly neither
          // invented nor dropped.
          return (t: number) => clamp(t / duration);
      }
    });

    // ------------------------------------------------------- what happened, so far
    //
    // The same idea as the shapes above and none of the money: a count is a step
    // at the instant the thing happened, lateness climbs from the moment the
    // service level passes, and traffic follows the bytes.
    this.counted = account.counted ?? [];
    this.countShapes = this.counted.map((c) => {
      switch (counting(c.what)) {
        case 'late': {
          const over = job / 1000 - slaSeconds;
          if (over <= 0) return () => 1;
          return (t: number) => clamp((t / 1000 - slaSeconds) / over);
        }
        case 'traffic':
          return (t: number) => egress(t);
        case 'unknown':
          return (t: number) => clamp(t / duration);
        default: {
          const times = COUNTS[counting(c.what)].flatMap((k) => when.get(k) ?? []).sort((a, b) => a - b);
          if (!times.length) return () => 1;
          return (t: number) => times.filter((x) => x <= t).length / times.length;
        }
      }
    });

    // ------------------------------------------------------------- attribution

    const share = (of: (name: string) => number): Map<string, number> => {
      const out = new Map<string, number>();
      let total = 0;
      for (const m of trace.nodes) total += Math.max(0, of(m.name));
      if (total <= 0) return out;
      for (const m of trace.nodes) {
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

    // The bill charges storage for **the worst node's** spill, so it belongs
    // to that node alone. Splitting it across the cluster would be a different
    // and much smaller claim about each of them.
    let worst = '';
    let mostDisk = 0;
    for (const m of trace.nodes) {
      const held = raw(m.name, 'diskMb');
      if (held > mostDisk) {
        mostDisk = held;
        worst = m.name;
      }
    }

    // Build is priced per *distinct* service, so a service two nodes offer is
    // one line item they each half-carry.
    const offeredBy = new Map<string, string[]>();
    for (const m of trace.nodes) {
      for (const s of m.serves) offeredBy.set(s, [...(offeredBy.get(s) ?? []), m.name]);
    }
    const services = Math.max(1, offeredBy.size);

    this.blame = kinds.map((kind) => {
      switch (kind.k) {
        case 'capacity':
          return new Map([[kind.node, 1]]);
        case 'egress':
          return share((n) => raw(n, 'crossZoneMb'));
        case 'storage':
          return worst ? new Map([[worst, 1]]) : new Map();
        case 'build': {
          const out = new Map<string, number>();
          for (const [, holders] of offeredBy) {
            for (const h of holders) {
              out.set(h, (out.get(h) ?? 0) + 1 / holders.length / services);
            }
          }
          return out;
        }
        // The job's, not any node's. Left empty on purpose.
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
          return 'the whole line — this is the worst node’s spill, and it is the worst node';
        case 'build':
          return 'its share of carrying the services it offers';
        case 'unknown':
          return '';
      }
    });
  }

  /** Every node that carries any of the bill, so the film can say who does not. */
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
      // A bill from an engine with a bucket this one does not have. Its money is
      // left out of a total that has no column for it, rather than added into
      // one of the columns that does.
      if (!IS_BUCKET.has(line.bucket)) continue;
      const sofar = line.amount * this.shapes[i](t);
      buckets[line.bucket] += sofar;
      const cut = focus ? (this.blame[i].get(focus) ?? 0) : 0;
      const mine = sofar * cut;
      mineBuckets[line.bucket] += mine;
      mineFinal += line.amount * cut;
      if (sofar > 0 || mine > 0) lines.push({ line, sofar, mine, why: cut > 0 ? this.why[i] : '' });
    }

    // Theirs first, then by size: pointing at a node should bring its own
    // money to the top rather than leave it to be hunted for down the table.
    lines.sort((a, b) => b.mine - a.mine || b.sofar - a.sofar);
    const cost = buckets.build + buckets.capacity + buckets.consumption;
    const counted: CountedNow[] = this.counted.map((c, i) => ({
      what: c.what,
      unit: c.unit,
      quantity: c.quantity * this.countShapes[i](t),
      total: c.quantity,
      why: c.why,
    }));
    return {
      currency: this.currency,
      buckets,
      cost,
      finalCost: this.finalCost,
      lines,
      counted,
      focus: focus
        ? {
            name: focus,
            cost: mineBuckets.build + mineBuckets.capacity + mineBuckets.consumption,
            finalCost: mineFinal,
            buckets: mineBuckets,
          }
        : null,
    };
  }
}

function zero(): Record<Bucket, number> {
  return { build: 0, capacity: 0, consumption: 0 };
}

function clamp(v: number): number {
  return Math.max(0, Math.min(1, v));
}

/** When the simulation finished, or nothing if it never did. */
function doneAt(trace: Trace): number | null {
  const done = trace.events.find((e) => e.kind === 'done');
  if (done) return Number(done.t ?? 0);
  const run = trace.root;
  return run && run.t1 >= 0 && run.status === 'OK' ? run.t1 : null;
}

/** A cluster-wide running total of one series, as a share of its final value. */
function cumulative(trace: Trace, metric: string): (t: number) => number {
  const times = trace.series(trace.nodes[0]?.name ?? '', metric).t;
  const total = new Array(times.length).fill(0);
  for (const m of trace.nodes) {
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
 * a node that filled its disk at the very end as though it had held it all
 * run.
 */
function integral(trace: Trace, metric: string): (t: number) => number {
  const times = trace.series(trace.nodes[0]?.name ?? '', metric).t;
  const level = new Array(times.length).fill(0);
  for (const m of trace.nodes) {
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

/**
 * An amount of money, written the way money is written.
 *
 * Two decimals, because the smallest thing anyone can be charged is a rappen
 * and a fourth decimal is a fraction of one — the bill closes to the rappen and
 * this is that same unit on the face. Grouped above a thousand, so 4234 is not
 * read as 423 or 42,340 by anybody scanning a column.
 *
 * A line that costs something but rounds to nothing says so rather than
 * printing `0.00`: at a fifth of a rappen, `0.00` and "free" are the same four
 * characters, and a per-node column of them loses the one reading it was there
 * to give.
 */
export function amount(v: number): string {
  const sign = v < 0 ? '-' : '';
  const a = Math.abs(v);
  if (a > 0 && a < 0.005) return `${sign}<0.01`;
  const [whole, frac] = a.toFixed(2).split('.');
  return `${sign}${groupText(whole)}.${frac}`;
}

export function money(v: number, currency: string): string {
  return `${currency} ${amount(v)}`;
}
