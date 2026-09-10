/**
 * Reading a trace.
 *
 * The trace is the interchange format (D9), so this file is the only place that
 * knows its shape. Everything downstream asks questions — *what was true at t?*
 * — rather than reading fields.
 *
 * It reads a **raw DISSALy trace**, with no baking step in front of it. That is
 * what lets a student drop their own run onto the page; it is also why the
 * decoding below is not optional. The dense channels arrive encoded three
 * different ways, and payloads arrive truncated with the real count hidden in
 * the truncation marker.
 */

export interface Node {
  name: string;
  instance: string;
  zone: string;
  vcpu: number;
  serves: string[];
  capMb: number;
  alive: boolean;
  /**
   * The record as the trace wrote it, kept whole.
   *
   * Everything above is what the layout and the film need, named the way they
   * name it. What is left is the run's totals — allocated, bytes out, how much
   * of that crossed a zone, what DISSALy's own bookkeeping cost — which only the
   * panel reads, and which would otherwise have to be re-derived from the
   * series by something that would eventually disagree with the bill.
   */
  raw: Record<string, number | string | boolean>;
}

export interface Span {
  id: number;
  parent: number;
  vm: string;
  kind: string;
  label: string;
  t0: number;
  t1: number;
  status: string;
  detail: Record<string, unknown>;
}

/**
 * What DISSALy's own call into the system is called in a span label.
 *
 * gRPC writes `dissaly.Job/Run`; the trace writes every method dotted, so this is
 * the dotted form. The one string in the viewer that knows a name from DISSALy's
 * own schema — everything else here is the assignment's.
 */
export const RUN = 'dissaly.Job.Run';

export interface TraceEvent {
  kind?: string;
  t?: number;
  vm?: string;
  detail?: Record<string, unknown>;
  [k: string]: unknown;
}

/** Where a span was addressed, when it was an RPC. */
export function spanTo(s: Span): string | undefined {
  const to = s.detail['to'];
  return typeof to === 'string' ? to : undefined;
}

export function liveAt(s: Span, t: number): boolean {
  return s.t0 <= t && t <= (s.t1 >= 0 ? s.t1 : t);
}

// ----------------------------------------------------------------- payloads
//
// The trace carries every argument and every result, which no real system would
// do and DISSALy does deliberately: watching a computation happen is the point, and
// a film of nodes exchanging opaque byte counts teaches nothing. What it does
// not carry is a way to *show* them — a reducer's result is three thousand
// key-value pairs, and three thousand of anything is not a thing anybody reads at
// a glance.
//
// So a payload is digested rather than rendered: the two or three fields that say
// what this message is, with anything repeated counted rather than listed.

/**
 * Fields that identify the work rather than describe it. Shown first when
 * present, because "which task is this" is the question a viewer asks before
 * any other.
 */
export const KEYS = ['task', 'part', 'iteration'] as const;

/**
 * Fields the picture is already showing. A capacity reply that says "node m0,
 * instance c5.large, zone eu-central-1a" has said nothing at all next to a
 * drawing of m0, labelled c5.large, sitting inside a box labelled eu-central-1a
 * — and it has said it in forty characters that then run across the node next
 * door.
 *
 * The rule this encodes: a reading is worth its space only if it carries
 * something the shape does not.
 */
export const ON_SCREEN = ['node', 'node', 'worker', 'instance', 'zone', 'holder', 'holders', 'from', 'to'];

const MORE = /\+([\d,]+) more/;

/** How many entries the trace kept out of a collection it had to bound. */
function overflowOf(marker: unknown): number {
  if (typeof marker !== 'string') return 0;
  const found = MORE.exec(marker);
  return found ? parseInt(found[1].replace(/,/g, ''), 10) : 0;
}

export type Entry = [string | null, unknown];

/**
 * A bounded collection, as (its visible entries, how many there really are).
 *
 * The trace records payloads and bounds them at twelve entries, appending
 * "+1106 more" to say what it dropped. Reading the length of what survived and
 * calling it a count is therefore not an approximation, it is a **wrong
 * number** — a reducer that folded 1,118 keys would be reported as folding 13.
 * The marker is the whole reason the real total is recoverable, so it is read
 * rather than filtered out and forgotten.
 */
export function entries(value: unknown): [Entry[], number] {
  if (Array.isArray(value)) {
    const kept: Entry[] = [];
    let extra = 0;
    for (const item of value) {
      if (typeof item === 'string') {
        extra += overflowOf(item);
      } else if (item && typeof item === 'object' && 'key' in item) {
        const o = item as Record<string, unknown>;
        kept.push([o['key'] as string, o['value']]);
      } else {
        kept.push([null, item]);
      }
    }
    return [kept, kept.length + extra];
  }
  if (value && typeof value === 'object') {
    const o = value as Record<string, unknown>;
    const kept: Entry[] = Object.keys(o).filter((k) => k !== '…').map((k) => [k, o[k]]);
    return [kept, kept.length + overflowOf(o['…'])];
  }
  return [[], 0];
}

/**
 * What is actually *in* a message: the words, and how many there are.
 *
 * "1,118 keys" says how much; "the 1,729 · cat 402 · +1,116 more" says what,
 * and what is the reason DISSALy records payloads at all. A film of nodes
 * passing each other counts teaches nothing that a bar chart would not.
 *
 * The sample is the largest values among those the trace kept, because a word
 * count's interesting keys are its frequent ones and a sample taken in key
 * order is a sample of the alphabet.
 */
export function contents(
  payload: Record<string, unknown>,
  show = 3,
): [string | null, string | null, number] {
  for (const key of Object.keys(payload)) {
    const value = payload[key];
    const isCollection = Array.isArray(value) || (value !== null && typeof value === 'object');
    if (!isCollection || ON_SCREEN.includes(key)) continue;
    const [kept, total] = entries(value);
    const worded = kept.filter(
      ([k, v]) => typeof k === 'string' && typeof v === 'number',
    ) as [string, number][];
    if (!worded.length) continue;
    worded.sort((a, b) => b[1] - a[1]);
    const head = worded.slice(0, show);
    const bits = head.map(([k, v]) => `${k} ${g(v)}`);
    if (total > head.length) bits.push(`+${group(total - head.length)} more`);
    return [bits.join('  '), key, total];
  }
  return [null, null, 0];
}

/** What a message contains, in a few words. */
export function digest(payload: unknown, limit = 2, words = true): string {
  if (payload === null || payload === undefined) return '';
  if (typeof payload !== 'object') {
    return String(payload).slice(0, 22) || 'empty';
  }
  if (Array.isArray(payload)) {
    const [, total] = entries(payload);
    return total ? `${group(total)} items` : String(payload).slice(0, 22) || 'empty';
  }
  const body = payload as Record<string, unknown>;
  if (!Object.keys(body).length) return 'empty';

  if (words) {
    const [said] = contents(body);
    if (said) return said;
  }

  const bits: string[] = [];
  for (const key of KEYS) {
    if (key in body && typeof body[key] === 'number') {
      bits.push(`${key} ${g(body[key] as number)}`);
    }
  }

  const counted: string[] = [];
  const numbers: string[] = [];
  for (const key of Object.keys(body)) {
    const value = body[key];
    if ((KEYS as readonly string[]).includes(key) || ON_SCREEN.includes(key)) continue;
    if (Array.isArray(value) || (value !== null && typeof value === 'object')) {
      const [, total] = entries(value);
      counted.push(`${group(total)} ${key}`);
    } else if (typeof value === 'boolean') {
      numbers.push(value ? key : `no ${key}`);
    } else if (typeof value === 'number') {
      numbers.push(`${key} ${g(value)}`);
    } else if (typeof value === 'string' && value.length > 24) {
      // A field of text is the workload itself. Its length is the only thing
      // about it that fits — the words are in the corpus, not here. The trace
      // already counted it before it truncated it, and says so in the tail it
      // left behind: "… (64349 chars)". Reading the length of what survived
      // would report a hundred and twenty every time.
      const found = /\((\d+) chars\)/.exec(value);
      const n = found ? parseInt(found[1], 10) : value.length;
      numbers.push(`${group(n)} chars`);
    }
  }

  for (const bit of counted.concat(numbers)) {
    if (bits.length >= limit) break;
    bits.push(bit);
  }
  return bits.join('  ');
}

/**
 * Which unit of work a call belongs to, if the payload says.
 *
 * Read off the message rather than reconstructed from the span tree, because
 * the message is where the cluster itself keeps it: a map task carries its task
 * number and a shuffle carries its partition, and those are the identifiers the
 * nodes are using to talk about the work. Anything inferred would be a second
 * naming of the same thing, and the two would disagree the moment a task was
 * re-run somewhere else — which is exactly when it matters.
 */
export function taskOf(span: Span): number | null {
  for (const side of ['arg', 'result']) {
    const body = span.detail[side];
    if (!body || typeof body !== 'object' || Array.isArray(body)) continue;
    for (const key of KEYS) {
      const value = (body as Record<string, unknown>)[key];
      if (typeof value === 'number') return Math.trunc(value);
    }
  }
  return null;
}

/**
 * What a `reveal()` can actually have put in the trace.
 *
 * The Java side offers `int`, `long`, `double`, `boolean` and `String`, and its
 * `Object` fallback goes through `String.valueOf` before it is written — so
 * three JSON types is the whole range, and anything else in a `state` event came
 * from a hand-edited file rather than from a run.
 */
export type RevealValue = number | string | boolean;

/**
 * One revealed value, or nothing, narrowed once so nothing downstream re-asks.
 *
 * Events are the one part of the trace that is never normalised on the way in
 * (`this.events` is the raw array), so `detail.value` arrives as `unknown` and
 * the narrowing has to happen somewhere. Doing it here, once, is what keeps a
 * `typeof` ladder out of the panel and out of the SVG — and exporting it is what
 * lets `checks/stops.ts` filter by exactly the same rule the film filters by,
 * rather than by a second opinion about what counts.
 *
 * NaN is not a value a reader can compare, so it is not one here either.
 */
export function asReveal(v: unknown): RevealValue | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' || typeof v === 'boolean') return v;
  return null;
}

// ------------------------------------------------------------------- numbers
//
// Two formatters, matching Python's `f"{v:,g}"` and `f"{n:,}"`, because the
// parity check compares the rendered digest string and a thousands separator in
// the wrong place is a difference.

/** Python's `f"{n:,}"` — an integer with thousands separators. */
export function group(n: number): string {
  const neg = n < 0;
  const digits = String(Math.abs(Math.trunc(n)));
  const out = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return neg ? `-${out}` : out;
}

/**
 * Python's `f"{v:,g}"` — six significant digits, an exponent only when the
 * number is too large or too small to write plainly, trailing zeros dropped,
 * and thousands separators when it stayed plain.
 */
export function g(v: number): string {
  if (!Number.isFinite(v)) return String(v);
  if (v === 0) return '0';
  const exp = Math.floor(Math.log10(Math.abs(v)));
  if (exp < -4 || exp >= 6) {
    // %g's exponential form: 6 significant digits, then trailing zeros dropped,
    // with a two-digit exponent. No grouping — there is no integer part to group.
    let [mantissa, e] = v.toExponential(5).split('e');
    if (mantissa.includes('.')) mantissa = mantissa.replace(/0+$/, '').replace(/\.$/, '');
    const sign = e[0] === '-' ? '-' : '+';
    const mag = e.replace(/^[+-]/, '').padStart(2, '0');
    return `${mantissa}e${sign}${mag}`;
  }
  let s = v.toFixed(Math.max(0, 5 - exp));
  if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
  const [whole, frac] = s.split('.');
  return frac ? `${groupText(whole)}.${frac}` : groupText(whole);
}

function groupText(whole: string): string {
  const neg = whole.startsWith('-');
  const digits = neg ? whole.slice(1) : whole;
  const out = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return neg ? `-${out}` : out;
}

// -------------------------------------------------------------------- scaled
//
// A scaled run executes a workload small enough to fit on the machine in front
// of you and reports the one it stands for. So *every* figure a reader wants
// from it — bytes out, memory held, how long it takes — is a projection, and
// none of it is on the charts, which draw what the probe actually did.
//
// Everything below is read, never derived. The laws are fitted, evaluated,
// aggregated across machines and error-barred in `dissaly.scale`, and arrive
// here as answers. This once had a sibling that recomputed a projection from
// the coefficients, agreed with the engine to three digits, and disagreed on
// the fourth — two programs with an opinion about one number, which is one
// program too many (D9).

/** One resource, at the size that ran and at the size it stands for. */
export interface Projection {
  resource: string;
  /** What the probe run measured, in the resource's own unit. */
  observed: number;
  /** What the law says at full size. `null` where the engine would not say. */
  projected: number | null;
  /** Multiplicative: the band is `projected / errorBar` to `projected * errorBar`. */
  errorBar: number;
  /** What the law is a function of — `units`, `calls`, or something revealed. */
  of: string;
  /** `machines` where the cluster figure was assembled per node, else `law`. */
  from: string;
  /** The condition the number was produced under, where there was one. */
  assumed: string | null;
  /** Why there is no number, on the one shape of ladder that admits of none. */
  refused: string | null;
}

/** What a scaled run is a model of. */
export interface Scaled {
  /** The workload that actually executed. */
  units: number;
  /** The workload it stands for. */
  fullUnits: number;
  factor: number;
  /** How many probe runs the laws were fitted from. */
  gridRuns: number;
  /** What the engine wants said about this plan, in its own words. */
  notes: string[];
  /** Per node: the memory and disk the probe was given, in MB. */
  caps: Record<string, number[]>;
  /** Per node: the memory and disk it would really have, in MB. */
  fullCaps: Record<string, number[]>;
  /** The cluster, resource by resource. */
  projections: Projection[];
  /** The same question asked of one node at a time. */
  perNode: Map<string, Projection[]>;
}

function readProjection(p: Record<string, unknown>): Projection {
  return {
    resource: String(p['resource'] ?? ''),
    observed: Number(p['observed'] ?? 0),
    // Distinguished from zero on purpose: a resource the engine declined to
    // project and a resource it projects at nothing are different claims, and
    // rendering the first as `0.00` would put a number in a reader's hands that
    // nothing in the run supports.
    projected: p['projected'] === undefined ? null : Number(p['projected']),
    errorBar: Number(p['errorBar'] ?? 1),
    of: String(p['of'] ?? ''),
    from: String(p['from'] ?? 'law'),
    assumed: p['assumed'] === undefined ? null : String(p['assumed']),
    refused: p['refused'] === undefined ? null : String(p['refused']),
  };
}

function readScaled(meta: Record<string, unknown>): Scaled | null {
  const s = meta['scale'] as Record<string, unknown> | undefined;
  if (!s) return null;
  const list = (v: unknown): Projection[] =>
    ((v as Record<string, unknown>[]) ?? []).map(readProjection);
  const perNode = new Map<string, Projection[]>();
  for (const [name, ps] of Object.entries(
    (meta['nodeProjections'] as Record<string, unknown>) ?? {},
  )) {
    perNode.set(name, list(ps));
  }
  return {
    units: Number(s['units'] ?? 0),
    fullUnits: Number(s['fullUnits'] ?? 0),
    factor: Number(s['factor'] ?? 1),
    gridRuns: Number(s['gridRuns'] ?? 0),
    notes: (s['notes'] as string[]) ?? [],
    caps: (s['caps'] as Record<string, number[]>) ?? {},
    fullCaps: (s['fullCaps'] as Record<string, number[]>) ?? {},
    projections: list(meta['projections']),
    perNode,
  };
}

// --------------------------------------------------------------------- trace

export class Trace {
  meta: Record<string, unknown>;
  events: TraceEvent[];
  spans: Span[];
  nodes: Node[];
  byName: Map<string, Node>;
  private times: number[];
  private channels: Map<string, number[]>;

  constructor(raw: Record<string, unknown>) {
    this.meta = (raw['meta'] as Record<string, unknown>) ?? {};
    this.events = (raw['events'] as TraceEvent[]) ?? [];
    this.spans = ((raw['spans'] as Record<string, unknown>[]) ?? []).map((s) => ({
      id: (s['id'] as number) ?? 0,
      parent: (s['parent'] as number) ?? 0,
      vm: (s['vm'] as string) ?? '',
      kind: (s['kind'] as string) ?? '',
      label: (s['label'] as string) ?? '',
      t0: Number(s['t0'] ?? 0),
      t1: Number(s['t1'] ?? -1),
      status: (s['status'] as string) || 'OK',
      detail: (s['detail'] as Record<string, unknown>) ?? {},
    }));
    this.nodes = ((raw['nodes'] as Record<string, unknown>[]) ?? []).map((m) => ({
      name: (m['name'] as string) ?? '',
      instance: (m['instance'] as string) ?? '',
      zone: (m['zone'] as string) ?? '',
      vcpu: Math.trunc(Number(m['vcpu'] ?? 2)),
      serves: (m['serves'] as string[]) ?? [],
      capMb: Number(m['memCapMb'] ?? 0) || 0,
      alive: m['alive'] === undefined ? true : Boolean(m['alive']),
      raw: m as Record<string, number | string | boolean>,
    }));
    this.byName = new Map(this.nodes.map((m) => [m.name, m]));
    const [times, channels] = decode((raw['series'] as Record<string, unknown>) ?? {});
    this.times = times;
    this.channels = channels;
  }

  static parse(text: string): Trace {
    return new Trace(JSON.parse(text));
  }

  // ------------------------------------------------------------- the clock

  /**
   * How long the film runs.
   *
   * **Not the job's duration**, which is what `durationRefMs` records: the clock
   * has to cover everything the trace actually contains, and a trace routinely
   * contains events *after* its job ended. The heap walk is too expensive to run
   * every tick, so an out-of-memory is detected up to eight ticks late (D12) —
   * which puts the very event that killed the run past the moment the run is
   * said to have stopped. Clipped there, the scrubber cannot reach the OOM, `]`
   * cannot jump to it, and the incident never arrives on the bill.
   *
   * Computed once and cached: read per frame, and spreading over every span on
   * every read would throw on a trace large enough to matter.
   */
  get duration(): number {
    if (this.span === null) {
      let end = Math.max(1.0, Number(this.meta['durationRefMs'] ?? 0));
      for (const s of this.spans) if (s.t1 > end) end = s.t1;
      for (const e of this.events) {
        const at = Number(e.t ?? 0);
        if (at > end) end = at;
      }
      if (this.times.length) end = Math.max(end, this.times[this.times.length - 1]);
      this.span = end;
    }
    return this.span;
  }
  private span: number | null = null;

  /**
   * How long the *simulation* took — which is what the bill was priced against.
   *
   * Distinct from `duration`, and the distinction matters wherever money is
   * involved: capacity is billed for the period `dissaly.Job/Run` was open, not
   * for the extra moment the trace happens to carry after it.
   */
  get billedRefMs(): number {
    return Math.max(1.0, Number(this.meta['durationRefMs'] ?? 0));
  }

  /** The node DISSALy entered the system through, which is where `Run` was served. */
  get entry(): string {
    return (this.meta['entry'] as string) ?? '';
  }

  /**
   * The model, where the run was one — and `null` where the run was itself.
   *
   * Cached, because every panel that shows a projection asks for this and
   * rebuilding the per-node map on each of sixty frames a second would cost a
   * cluster large enough to matter.
   */
  get scaled(): Scaled | null {
    if (this.model === undefined) this.model = readScaled(this.meta);
    return this.model;
  }
  private model: Scaled | null | undefined = undefined;

  /**
   * The span the whole simulation is.
   *
   * There is no span kind for it, and that is the point: what starts the work is
   * an ordinary handler on an ordinary node, and everything else nests beneath it
   * through the parent header. The trace says so rather than the manual asking to
   * be believed.
   *
   * Cached: read per frame by the layout, and scanning every span each time would
   * cost a trace large enough to matter.
   */
  get root(): Span | null {
    if (this.rootSpan === undefined) {
      this.rootSpan = this.spans.find((s) => s.kind === 'handler' && s.label === RUN) ?? null;
    }
    return this.rootSpan;
  }
  private rootSpan: Span | null | undefined = undefined;

  // ------------------------------------------------------- dense channels

  /** What one node's one number was at an instant, held between ticks. */
  channel(vm: string, metric: string, t: number): number {
    const values = this.channels.get(`${vm}.${metric}`);
    if (!values || !values.length || !this.times.length) return 0.0;
    if (t <= this.times[0]) return values[0];
    for (let i = this.times.length - 1; i >= 0; i--) {
      if (this.times[i] <= t) return values[Math.min(i, values.length - 1)];
    }
    return values[values.length - 1];
  }

  /** Every channel name in the trace, for the panel's sparklines. */
  channelNames(): string[] {
    return [...this.channels.keys()];
  }

  /** One node's whole history of one metric, for a sparkline. */
  series(vm: string, metric: string): { t: number[]; v: number[] } {
    const v = this.channels.get(`${vm}.${metric}`) ?? [];
    return { t: this.times, v };
  }

  // --------------------------------------------------- what was true at t

  spansAt(t: number, kind: string): Span[] {
    return this.spans.filter((s) => s.kind === kind && liveAt(s, t));
  }

  /**
   * The newest value of every key a node revealed, up to t.
   *
   * Newest, not all: a state badge is rewritten so the number visibly moves.
   * A stack of every value ever reported is a log, and a log is the thing this
   * exists to replace.
   *
   * **The film does not read this.** It reads the index `RunIndex` builds once
   * per run, because a scan of every event on every frame is the cost `RunIndex`
   * exists to avoid. What this is, is the slow and obvious statement of the
   * question that index answers — kept deliberately naive so that
   * `checks/stops.ts` can hold the two against each other and have a real second
   * opinion rather than a rephrasing of the first. Rewriting this in terms of
   * the index would leave that check comparing something with itself.
   */
  revealedAt(t: number): Map<string, unknown> {
    const out = new Map<string, unknown>();
    for (const e of this.events) {
      if (e.kind !== 'state' || Number(e.t ?? 0) > t) continue;
      const d = e.detail ?? {};
      out.set(`${e.vm}\u0000${String(d['key'])}`, d['value']);
    }
    return out;
  }

  /**
   * Which unit of work each call belongs to, by span id.
   *
   * The payload says, when it can. It often cannot: protobuf omits a field that
   * holds its type's default, so task 0 and partition 0 are simply not in the
   * message — and "the first one" is exactly the case a reader wants coloured
   * like the others rather than like nothing.
   *
   * So the fallback is where the call sits among its siblings. A master
   * fanning out eight map tasks makes eight calls under one parent, and their
   * order is the order it dealt them out; that is the same number the payload
   * would have carried, arrived at from the shape of the call graph instead of
   * from its contents.
   */
  tasks(): Map<number, number> {
    const out = new Map<number, number>();
    const siblings = new Map<number, number>();
    const ordered = [...this.spans].sort(
      (a, b) => a.parent - b.parent || a.t0 - b.t0 || a.id - b.id,
    );
    for (const span of ordered) {
      if (span.kind !== 'rpc') continue;
      const explicit = taskOf(span);
      if (explicit !== null) {
        out.set(span.id, explicit);
        continue;
      }
      const n = siblings.get(span.parent) ?? 0;
      siblings.set(span.parent, n + 1);
      out.set(span.id, n);
    }
    return out;
  }

  eventsBetween(kind: string, a: number, b: number): TraceEvent[] {
    return this.events.filter(
      (e) => e.kind === kind && a < Number(e.t ?? 0) && Number(e.t ?? 0) <= b,
    );
  }
}

/**
 * Undo the constant/runs/raw encoding, once, into plain arrays.
 *
 * Most channels barely move — a node is alive for the whole simulation, idle for
 * most of it, and its cap never changes at all — so DISSALy writes whichever of
 * the three forms is smallest (D8). That is worth about two orders of magnitude
 * on the wire and nothing at all here, where every form becomes the same array.
 */
function decode(series: Record<string, unknown>): [number[], Map<string, number[]>] {
  const times = ((series['t'] as unknown[]) ?? []).map(Number);
  const channels = new Map<string, number[]>();
  const raw = (series['channels'] as Record<string, Record<string, unknown>>) ?? {};
  for (const key of Object.keys(raw)) {
    const ch = raw[key];
    const form = ch['form'];
    const ticks = Math.trunc(Number(ch['ticks'] ?? 0));
    let values: number[];
    if (form === 'constant') {
      values = new Array(Math.max(0, ticks)).fill(Number(ch['value'] ?? 0));
    } else if (form === 'runs') {
      values = [];
      for (const [value, count] of (ch['runs'] as [number, number][]) ?? []) {
        for (let i = 0; i < Math.trunc(count); i++) values.push(Number(value));
      }
    } else {
      values = ((ch['raw'] as unknown[]) ?? []).map(Number);
    }
    channels.set(key, values);
  }
  return [times, channels];
}
