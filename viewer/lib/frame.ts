/**
 * What was true at an instant.
 *
 * **Every pixel is a pure function of `(trace, t, selection)`.** Nothing here
 * accumulates, nothing is remembered between frames, and no view knows what it
 * drew last time. That one constraint pays for itself four times over:
 *
 * - **The transience rule enforces itself.** "The picture holds only what is
 *   currently true" is not a rule that has to be *remembered* — a renderer that
 *   accumulates state must remember to remove what has finished, and discipline
 *   enforced by memory decays until the last frame is the static figure again.
 *   Derived from `t`, a call that has finished is simply not in the frame here.
 *   There is nothing to remove and so nothing to forget to remove.
 * - **Scrubbing is free.** Seeking backwards is the same operation as playing.
 * - **Recording is the same code path as playing** (lib/record.ts). The only
 *   difference is who advances the clock.
 * - **The views cannot disagree**, because they are functions of one value.
 */
import { Layout, TIGHT } from './layout.ts';
import {
  Trace,
  asReveal,
  digest,
  entries,
  g,
  liveAt,
  spanTo,
  type RevealValue,
  type Span,
  type TraceEvent,
} from './trace.ts';

export type NodeState = 'alive' | 'degraded' | 'frozen' | 'dead' | 'reclaiming';

export interface Work {
  span: Span;
  label: string;
  /** The method's bare name — `Sort`, not `mr.ShuffleWorker.Sort`. */
  method: string;
  task: number | null;
  digest: string;
}

/** One key a node reported with `reveal()`, and where that key had got to at `t`. */
export interface Reveal {
  key: string;
  /** The newest value at or before `t`, or null when it has not said yet. */
  value: RevealValue | null;
  /** When that value was reported, or -1 when there is none. */
  at: number;
}

/**
 * A revealed value, written the way the rest of the viewer writes values.
 *
 * `g()` rather than a fresh `toLocaleString`, because the picture already has
 * one number convention and a second one would put 1,048,576 on the face and
 * 1.04858e+06 in the digest beside it.
 */
export function revealText(v: RevealValue): string {
  if (typeof v === 'number') return g(v);
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  return v;
}

export interface FrameNode {
  name: string;
  instance: string;
  zone: string;
  vcpu: number;
  serves: string[];
  x: number;
  y: number;
  w: number;
  h: number;
  state: NodeState;
  /** What it holds on disk, and — the number anyone is actually reaching for — what is left. */
  diskMb: number;
  diskCapMb: number;
  diskFreeMb: number;
  diskShare: number;
  busy: number;
  queued: number;
  inflight: number;
  work: Work[];
  /**
   * What this node reported with `reveal()`, newest value per key at `t`.
   *
   * Every key this node reveals *anywhere in the run* is here, ordered by when
   * the run first saw each key — so which rows exist is a fact about the run,
   * like `serves` and `diskCapMb` above, and what is a fact about the instant is
   * the value, with `null` for "has not said yet".
   *
   * That split is the one place this file bends "the picture holds only what is
   * currently true", and it buys two things worth the bend. Panel rows fill in
   * rather than appear, so a reader's eye keeps its place while the film plays.
   * And the face can tell *has not computed it yet* from *never computes it* —
   * which is the whole signal when two nodes are supposed to agree and one of
   * them is late.
   */
  revealed: readonly Reveal[];
}

export interface Flight {
  id: number;
  from: string;
  to: string;
  /** 0 at the caller, 1 at the callee. */
  progress: number;
  returning: boolean;
  crossZone: boolean;
  method: string;
  task: number | null;
  digest: string;
  bytes: number;
  /** How big to draw the envelope: 1 is an ordinary message for this run. */
  size: number;
  /** How much is actually in it — entries where it carries a collection. */
  items: number;
  /**
   * What is actually in it.
   *
   * The digest is what fits on a moving envelope; this is what somebody who has
   * paused the film and pointed at one wants to read. Carried by reference — it
   * is the trace's own object and nothing copies or mutates it.
   */
  body: unknown;
  /** How long this call is on the wire, in refMs — the two legs together. */
  netRefMs: number;
  /** What came back, when it was not OK. */
  status: string;
  /** Where the call sits on the run's own clock, for the reader's bearings. */
  t0: number;
  t1: number;
  failed: boolean;
  /** True when this message is being held on screen longer than it really took. */
  held: boolean;
  /**
   * The trace says this one never arrived: `delivered: false` on the span.
   *
   * It has no return leg. Drawn with one — which is what the film did until
   * this — a call to a node that was killed before the question reached it
   * comes back carrying an answer, and the frame shows a dead worker serving
   * reads. Nothing about the run says that; the drawing said it.
   */
  lost: boolean;
  /** Why, in the engine's own words: `unreachable`, `partitioned`, `lost`, `dropped by w2`. */
  why: string;
  /** How far it got before it died: 0 at the caller, 1 at the callee's door. */
  diedAt: number;
  /** 0 while it is still whole, 1 when it has finished dying. */
  dying: number;
}

export interface FrameOptions {
  /**
   * The shortest a message may stay on screen, in reference milliseconds.
   *
   * A call that took three refMs is on screen for a tenth of a frame, and at 8x
   * for a fortieth of one — so the messages that carry the most interesting
   * payloads, the small fast control ones, are precisely the ones nobody ever
   * sees. Held open, they can be read.
   *
   * This makes the picture say something slightly untrue about *timing*, which is
   * why it is a mode rather than the default and why a held message says so.
   * Nothing about the payload, the route or the ordering changes: only how long
   * the envelope lingers.
   */
  dwellRefMs?: number;
}

export interface Frame {
  t: number;
  nodes: FrameNode[];
  flights: Flight[];
  /** Anything that happened in the moment just gone — a kill, an OOM, a disk full. */
  just: TraceEvent[];
}

/**
 * Everything a frame needs, computed once.
 *
 * Without it every frame is O(all spans) and a two-thousand-span trace at 30 fps
 * is not a viewer. What is precomputed is only what does not depend on `t`.
 */
export class RunIndex {
  readonly trace: Trace;
  /** Not readonly: it is re-searched when the shape of the screen changes. */
  layout: Layout;
  private fitted = '';
  readonly duration: number;
  readonly tasks: Map<number, number>;
  /** Spans that can be on screen, sorted by start, with a running max of their ends. */
  private readonly live: Span[];
  private readonly maxEnd: number[];
  private readonly diskCap = new Map<string, number>();
  private readonly notable: TraceEvent[];
  /**
   * Per node, per key: the times it reported, ascending, and the values beside
   * them. Two parallel arrays rather than a list of pairs, so the search reads
   * one contiguous run of numbers.
   */
  private readonly reveals = new Map<string, RevealTrack[]>();
  /** Every key any node revealed, in the order the run first revealed each. */
  private readonly revealKeys: string[] = [];
  /** The busiest payload in the run, which every other one is drawn against. */
  private readonly heaviest: number;

  constructor(trace: Trace) {
    this.trace = trace;
    this.layout = new Layout(trace);
    this.duration = trace.duration;
    this.tasks = trace.tasks();

    this.live = trace.spans
      .filter((s) => s.kind === 'rpc' || s.kind === 'handler')
      .sort((a, b) => a.t0 - b.t0);
    this.maxEnd = new Array(this.live.length);
    let running = -Infinity;
    for (let i = 0; i < this.live.length; i++) {
      const end = this.live[i].t1 >= 0 ? this.live[i].t1 : Infinity;
      running = Math.max(running, end);
      this.maxEnd[i] = running;
    }

    // The trace records disk *used* and the percentage of the cap it is, so the
    // cap is recoverable but not written down. Taken from the largest sample
    // rather than any one of them, because at nought percent of nothing the
    // division says nothing at all.
    for (const m of trace.nodes) {
      const used = trace.series(m.name, 'diskMb').v;
      const pct = trace.series(m.name, 'diskPct').v;
      let cap = 0;
      for (let i = 0; i < used.length; i++) {
        if (pct[i] > 0.5) cap = Math.max(cap, (used[i] / pct[i]) * 100);
      }
      this.diskCap.set(m.name, cap);
    }

    this.notable = trace.events
      .filter((e) => NOTABLE.has(String(e.kind)))
      .sort((a, b) => Number(a.t ?? 0) - Number(b.t ?? 0));

    // Sorted rather than trusted: events are the one part of the trace that is
    // read in raw, so nothing here guarantees the order the file happens to be
    // in — and a track out of order would be searched wrongly for the whole run.
    const said = trace.events
      .filter((e) => e.kind === 'state')
      .sort((a, b) => Number(a.t ?? 0) - Number(b.t ?? 0));
    for (const e of said) {
      const vm = String(e.vm ?? '');
      if (!trace.byName.has(vm)) continue;
      const d = e.detail ?? {};
      const key = String(d['key'] ?? '');
      const value = asReveal(d['value']);
      if (!key || value === null) continue;
      if (!this.revealKeys.includes(key)) this.revealKeys.push(key);
      const tracks = this.reveals.get(vm) ?? [];
      if (!this.reveals.has(vm)) this.reveals.set(vm, tracks);
      let track = tracks.find((x) => x.key === key);
      if (!track) {
        track = { key, t: [], v: [] };
        tracks.push(track);
        // Every node lists its keys in the run's order, not in its own arrival
        // order, so two nodes' panels line up row for row and the reader can
        // compare down a column instead of hunting for the matching label.
        tracks.sort((a, b) => this.revealKeys.indexOf(a.key) - this.revealKeys.indexOf(b.key));
      }
      track.t.push(Number(e.t ?? 0));
      track.v.push(value);
    }

    let heaviest = 1;
    for (const s of this.live) {
      if (s.kind !== 'rpc') continue;
      heaviest = Math.max(heaviest, weigh(s.detail['arg']), weigh(s.detail['result']));
    }
    this.heaviest = heaviest;
  }

  /** The instants worth being able to jump to: every kill, freeze, OOM and disk-full. */
  events(): TraceEvent[] {
    return this.notable;
  }

  /** Every key the run reveals, in the order it first revealed each — for the picker. */
  revealedKeys(): readonly string[] {
    return this.revealKeys;
  }

  /**
   * What one node had reported by `t`, one entry per key it ever reports.
   *
   * Searched rather than walked forward from the last frame: seeking backwards
   * has to cost what playing costs, and a cursor would remember where the film
   * had got to — which is the one thing nothing in this file is allowed to do.
   */
  private revealedOf(name: string, t: number): readonly Reveal[] {
    const tracks = this.reveals.get(name);
    if (!tracks) return NOTHING_REVEALED;
    return tracks.map((track) => {
      const i = lastAtOrBefore(track.t, t);
      return i < 0
        ? { key: track.key, value: null, at: -1 }
        : { key: track.key, value: track.v[i], at: track.t[i] };
    });
  }

  /** How much of a call is the outward flight, and how much the return. */
  private legOf(span: Span, end: number): number {
    const total = Math.max(1e-6, end - span.t0);
    const net = Number(span.detail['netRefMs'] ?? 0);
    return Math.max(Math.min(net / 2, total / 2), total * 0.12);
  }

  /**
   * Re-search the arrangement for the shape it is actually being drawn into.
   *
   * A cache keyed on the frame, so calling it every render costs a string
   * comparison and calling it on a resize costs one search. It returns the
   * layout rather than nothing so a caller can key its own memo on the identity
   * of what came back and recompute only when the arrangement really moved.
   */
  refit(frame: [number, number]): Layout {
    const key = `${frame[0].toFixed(2)}x${frame[1].toFixed(2)}`;
    if (key !== this.fitted) {
      this.layout = new Layout(this.trace, 3.05, 1.62, TIGHT, frame);
      this.fitted = key;
    }
    return this.layout;
  }

  frameAt(t: number, opts: FrameOptions = {}): Frame {
    const nodes: FrameNode[] = [];
    const workOf = new Map<string, Work[]>();
    const flights: Flight[] = [];

    for (const span of this.spansAt(t, opts.dwellRefMs ?? 0)) {
      if (span.kind === 'rpc') {
        const flight = this.flightOf(span, t, opts.dwellRefMs ?? 0);
        if (flight) flights.push(flight);
      } else {
        const list = workOf.get(span.vm) ?? [];
        list.push({
          span,
          label: span.label,
          method: bare(span.label),
          task: this.tasks.get(span.parent) ?? null,
          digest: digest(span.detail['arg'] ?? span.detail['result']),
        });
        workOf.set(span.vm, list);
      }
    }

    for (const m of this.trace.nodes) {
      const [x, y] = this.layout.point(m.name);
      const [w, h] = this.layout.sizeOf(m.name);
      const disk = this.trace.channel(m.name, 'diskMb', t);
      const diskCap = this.diskCap.get(m.name) ?? 0;
      nodes.push({
        name: m.name,
        instance: m.instance,
        zone: m.zone,
        vcpu: m.vcpu,
        serves: m.serves,
        x,
        y,
        w,
        h,
        state: this.stateOf(m.name, t),
        diskMb: disk,
        diskCapMb: diskCap,
        diskFreeMb: Math.max(0, diskCap - disk),
        diskShare: diskCap > 0 ? Math.min(1, disk / diskCap) : 0,
        busy: this.trace.channel(m.name, 'busyPct', t),
        queued: this.trace.channel(m.name, 'queued', t),
        inflight: this.trace.channel(m.name, 'inflight', t),
        work: workOf.get(m.name) ?? [],
        revealed: this.revealedOf(m.name, t),
      });
    }

    // A window rather than an instant: an event is a moment and a moment is
    // narrower than a frame, so at any speed above a crawl every kill in the run
    // would fall between two frames and never be drawn.
    const window = Math.max(this.duration / 240, 1);
    return {
      t,
      nodes,
      flights,
      just: this.notable.filter((e) => {
        const at = Number(e.t ?? 0);
        return at <= t && t - at < window;
      }),
    };
  }

  /**
   * Every span open at `t`.
   *
   * Binary-searched on the start and stopped early on the running maximum of the
   * ends: once every span from here on began after `t`, there is nothing more to
   * find, and the prefix maximum says when nothing before here can still be open.
   */
  private spansAt(t: number, dwell: number): Span[] {
    const out: Span[] = [];
    let hi = this.live.length;
    let lo = 0;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.live[mid].t0 <= t) lo = mid + 1;
      else hi = mid;
    }
    for (let i = lo - 1; i >= 0; i--) {
      // The dwell widens the window a span counts as open in, so the early exit
      // has to widen with it or a message being held is dropped before it is drawn.
      if (this.maxEnd[i] < t - dwell) break;
      const s = this.live[i];
      if (liveAt(s, t)) out.push(s);
      else if (dwell > 0 && s.kind === 'rpc' && s.t1 >= 0 && t < s.t0 + dwell) out.push(s);
    }
    return out;
  }

  private stateOf(name: string, t: number): NodeState {
    if (this.trace.channel(name, 'alive', t) < 0.5) return 'dead';
    if (this.trace.channel(name, 'frozen', t) > 0.5) return 'frozen';
    if (this.trace.channel(name, 'degraded', t) > 1.0001) return 'degraded';
    return 'alive';
  }

  /**
   * Where a call's payload is, if it is on the wire at all.
   *
   * A call is not one journey. It goes out, it is worked on, and it comes back —
   * and the middle of that is not the network, it is a node holding the
   * argument while it computes. Drawing one packet sliding steadily across for
   * the whole duration would say the opposite: that the time went into the wire.
   *
   * So the packet flies out over the network's own share of the call, is not
   * drawn while the callee is working, and flies back at the end. The trace
   * records `netRefMs`, so this is measured rather than styled.
   */
  private flightOf(span: Span, t: number, dwell: number): Flight | null {
    const to = spanTo(span);
    if (!to) return null;
    const from = span.vm;
    const real = span.t1 >= 0 ? span.t1 : this.duration;
    // Held open when the call was quicker than the eye. The journey is the same
    // journey, drawn more slowly.
    const end = Math.max(real, span.t0 + dwell);
    const held = end > real + 1e-9;
    // Floored so a call too quick to see still shows a packet leaving: at a
    // hundredth of a second the network is real, it is just not visible.
    const leg = this.legOf(span, end);

    const out = (t - span.t0) / leg;

    // A call the engine never delivered has one leg and no answer. What is left
    // of the span after it dies is the caller waiting out its own deadline, and
    // an empty wire is what that looks like.
    if (span.detail['delivered'] === false) {
      const why = String(span.detail['why'] ?? '');
      const far = diedAt(why);
      if (out > far + DYING) return null;
      const dying = Math.min(1, Math.max(0, (out - far) / DYING));
      return this.flight(span, from, to, Math.min(far, Math.max(0, out)), false, held, {
        why,
        diedAt: far,
        dying,
      });
    }

    if (out <= 1) {
      return this.flight(span, from, to, Math.max(0, out), false, held);
    }
    const back = (t - (end - leg)) / leg;
    if (back >= 0) {
      return this.flight(span, from, to, Math.min(1, back), true, held);
    }
    return null;
  }

  private flight(
    span: Span,
    from: string,
    to: string,
    progress: number,
    returning: boolean,
    held: boolean,
    dead: { why: string; diedAt: number; dying: number } | null = null,
  ): Flight {
    const a = this.trace.byName.get(from);
    const b = this.trace.byName.get(to);
    const body = span.detail[returning ? 'result' : 'arg'];
    const items = weigh(body);
    return {
      id: span.id,
      from,
      to,
      progress,
      returning,
      crossZone: !!a && !!b && a.zone !== b.zone,
      method: bare(span.label),
      task: this.tasks.get(span.id) ?? null,
      digest: digest(body),
      body,
      netRefMs: Number(span.detail['netRefMs'] ?? 0),
      status: span.status,
      t0: span.t0,
      t1: span.t1 >= 0 ? span.t1 : this.duration,
      bytes: Number(span.detail['bytes'] ?? 0),
      items,
      size: envelope(items, this.heaviest),
      held,
      failed: span.status !== 'OK',
      lost: dead !== null,
      why: dead?.why ?? '',
      diedAt: dead?.diedAt ?? 1,
      dying: dead?.dying ?? 0,
    };
  }
}

interface RevealTrack {
  key: string;
  t: number[];
  v: RevealValue[];
}

/**
 * One shared empty list for the nodes that reveal nothing, which is most of them.
 *
 * Frozen so that sharing it cannot become a way for one node's frame to be
 * written into another's.
 */
const NOTHING_REVEALED: readonly Reveal[] = Object.freeze([]);

/**
 * How long a message takes to die, as a share of the leg it was flying.
 *
 * Long enough to be seen at 1x and short enough that a run losing forty calls
 * to the same dead node is not forty marks stacked on one wire: the mark goes,
 * and the caller goes on waiting for its deadline with nothing on the wire,
 * which is the true picture of that wait.
 */
const DYING = 0.6;

/**
 * How far a lost message gets, read off the reason the engine recorded.
 *
 * `ClientSide` writes the reason beside `delivered: false`, and all this does is
 * decide where to stop drawing. A call the network lost or a partition swallowed
 * died between the two nodes. One addressed to a node that was already dead was
 * still sent, and still crossed the wire — it died at the far end, where nothing
 * was listening — and so did one the callee refused on arrival.
 */
function diedAt(why: string): number {
  return why === 'lost' || why === 'partitioned' ? 0.5 : 1;
}

/** The last index whose time is at or before `t`, or -1 when none is. */
function lastAtOrBefore(times: number[], t: number): number {
  let lo = 0;
  let hi = times.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (times[mid] <= t) lo = mid + 1;
    else hi = mid;
  }
  return lo - 1;
}

const NOTABLE = new Set([
  'kill',
  'freeze',
  'thaw',
  'restart',
  'degrade',
  'oom',
  'disk_full',
  'spot_notice',
  'partition',
  // The repair, and not only the break. Both paired events have both ends here:
  // `thaw` beside `freeze`, and `heal` beside `partition`. Without `heal`, `]`
  // would walk into a partition and straight past the moment the network comes
  // back, which is the instant a partition is interesting for: it is not the
  // same instant as the one where the data agrees again, and on a real trace
  // those sit 350 refMs apart. Being unable to park on either end of that gap
  // would hide the whole of what it teaches.
  'heal',
  'retry',
  // Both ends again, for the same reason `thaw` sits beside `freeze` and `heal`
  // beside `partition`. `]` walking into a retry and straight past the attempt
  // that worked leaves the reader parked on the question with the answer off
  // screen — and a retry that eventually succeeded is a different finding from
  // one that ran out of attempts, which is precisely the distinction this event
  // carries in its `status`.
  'retry_done',
  'rpc_timeout',
  // The other half of the same ternary. `Dropped.java:78` and
  // `ClientSide.java:139` both read `? "rpc_timeout" : "rpc_error"` — one call
  // that did not come back, labelled by why — so both outcomes of that ternary
  // have to be reachable here, not only one. A RESOURCE_EXHAUSTED on the call
  // that then cascaded is the moment somebody is looking for, and it has to be
  // a moment they can step to.
  'rpc_error',
  // One rpc going wrong on one node and not its peers, which is the case a
  // design handles worst and the reason `failures:` is written inside `runs:`.
  // It is not a `rpc_error`: the caller of a `status` failure gets a code the
  // handler never saw, and a `slow` one gets a correct answer late — so the
  // moment worth stepping to is the node deciding, not the call ending.
  'rpc_failure',
  'failed',
  'over_horizon',
  // Narration, which is an instant somebody chose. `write/telemetry.mdx` offers
  // log() as "the sentence a reader needs and no key-value pair captures" — a
  // promise that depends on it turning up where a reader is looking, not merely
  // reaching the terminal and the trace, so it belongs among the reachable stops
  // rather than being left to reveal(), which is the opposite of what the two
  // are for. A log line is sparse by intent, and it is the one marker here the
  // author placed deliberately rather than a failures: line placing it for them.
  'log',
]);

/**
 * How much is in a message.
 *
 * Its collections' **true** totals, which is the whole reason the truncation
 * marker is parsed rather than discarded: a reducer's answer that the trace kept
 * twelve of is a message carrying 1,118 things, not twelve, and an envelope drawn
 * from what survived would be the same size as a task number.
 */
function weigh(body: unknown): number {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return 0;
  let most = 0;
  const row = body as Record<string, unknown>;
  for (const k of Object.keys(row)) {
    const v = row[k];
    if (Array.isArray(v) || (v !== null && typeof v === 'object')) most = Math.max(most, entries(v)[1]);
  }
  return most;
}

/**
 * How big to draw an envelope holding `items`, against the run's heaviest.
 *
 * Logarithmic, and clamped, for the same reason node sizes are: a control
 * message carries one field and a shuffle response carries eleven hundred, and
 * drawn to scale the control message would be a dot. What has to survive is the
 * *ordering* — that one of these is visibly enormous and the other visibly is not.
 */
function envelope(items: number, heaviest: number): number {
  if (heaviest <= 1) return 1;
  const share = Math.log10(1 + Math.max(0, items)) / Math.log10(1 + heaviest);
  return 0.78 + 1.25 * Math.max(0, Math.min(1, share));
}

/** `mr.ShuffleWorker.Sort` -> `Sort`. The package is on screen already; the verb is not. */
export function bare(method: string): string {
  const cut = method.lastIndexOf('.');
  return cut < 0 ? method : method.slice(cut + 1);
}
