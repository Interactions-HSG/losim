/**
 * What was true at an instant.
 *
 * **Every pixel is a pure function of `(trace, t, selection)`.** Nothing here
 * accumulates, nothing is remembered between frames, and no view knows what it
 * drew last time. That one constraint pays for itself four times over:
 *
 * - **The transience rule enforces itself.** "The picture holds only what is
 *   currently true" was a rule that had to be *remembered* in the renderer that
 *   came before this, and a rule left to discipline decays until the last frame
 *   is the static figure again. Derived from `t`, a call that has finished is
 *   simply not in the frame. There is nothing to remove and so nothing to forget
 *   to remove.
 * - **Scrubbing is free.** Seeking backwards is the same operation as playing.
 * - **Recording is the same code path as playing** (lib/record.ts). The only
 *   difference is who advances the clock.
 * - **The views cannot disagree**, because they are functions of one value.
 */
import { Layout, TIGHT } from './layout.ts';
import type { Moment } from './pace.ts';
import { Trace, digest, entries, liveAt, spanTo, type Span, type TraceEvent } from './trace.ts';

export type MachineState = 'alive' | 'degraded' | 'frozen' | 'dead' | 'reclaiming';

export interface Work {
  span: Span;
  label: string;
  /** The method's bare name — `Sort`, not `mr.ShuffleWorker.Sort`. */
  method: string;
  task: number | null;
  digest: string;
}

export interface FrameMachine {
  name: string;
  instance: string;
  zone: string;
  vcpu: number;
  serves: string[];
  x: number;
  y: number;
  w: number;
  h: number;
  state: MachineState;
  /** What it holds, and — the number anyone is actually reaching for — what is left. */
  heldMb: number;
  capMb: number;
  freeMb: number;
  memShare: number;
  diskMb: number;
  diskCapMb: number;
  diskFreeMb: number;
  diskShare: number;
  busy: number;
  queued: number;
  inflight: number;
  work: Work[];
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
  phase: string | null;
  machines: FrameMachine[];
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
  /** The busiest payload in the run, which every other one is drawn against. */
  private readonly heaviest: number;

  constructor(trace: Trace) {
    this.trace = trace;
    this.layout = new Layout(trace);
    this.duration = trace.duration;
    this.tasks = trace.tasks();

    this.live = trace.spans
      .filter((s) => s.kind === 'rpc' || s.kind === 'handler' || s.kind === 'compute')
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
    for (const m of trace.machines) {
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

  /**
   * Everything the film draws that has to be seen, as intervals in trace time.
   *
   * This is what the paced clock is built from (`lib/pace.ts`), and the reason it
   * is computed **here** rather than from the span tree is that the thing which
   * has to be visible is not a span. A call is drawn as three separate things —
   * an envelope going out, a machine working, an envelope coming back — and
   * pacing the call as a whole would give a ten-millisecond call its second on
   * screen while its one-millisecond outward leg still flickered past in a
   * hundredth of it. So the legs are what is listed, computed by the same
   * arithmetic that draws them.
   */
  moments(): Moment[] {
    const out: Moment[] = [];
    for (const span of this.live) {
      const end = span.t1 >= 0 ? span.t1 : this.duration;
      if (span.kind === 'rpc') {
        const leg = this.legOf(span, end);
        out.push({ t0: span.t0, t1: span.t0 + leg });
        if (end - leg > span.t0 + leg) out.push({ t0: end - leg, t1: end });
      } else {
        // A handler or a local computation: what the machine is visibly doing,
        // and the label that says what it is doing it to.
        if (end > span.t0) out.push({ t0: span.t0, t1: end });
      }
    }
    return out;
  }

  /**
   * How much of a call is the outward flight — the same number `flightOf` draws.
   *
   * Shared rather than duplicated: if these two ever disagreed, the clock would
   * be holding still for a leg that is not the one on screen.
   */
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
    const machines: FrameMachine[] = [];
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

    for (const m of this.trace.machines) {
      const [x, y] = this.layout.point(m.name);
      const [w, h] = this.layout.sizeOf(m.name);
      const held = this.trace.channel(m.name, 'retainMb', t);
      const cap = this.trace.channel(m.name, 'memCapMb', t) || m.capMb;
      const disk = this.trace.channel(m.name, 'diskMb', t);
      const diskCap = this.diskCap.get(m.name) ?? 0;
      machines.push({
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
        heldMb: held,
        capMb: cap,
        freeMb: Math.max(0, cap - held),
        memShare: cap > 0 ? Math.min(1, held / cap) : 0,
        diskMb: disk,
        diskCapMb: diskCap,
        diskFreeMb: Math.max(0, diskCap - disk),
        diskShare: diskCap > 0 ? Math.min(1, disk / diskCap) : 0,
        busy: this.trace.channel(m.name, 'busyPct', t),
        queued: this.trace.channel(m.name, 'queued', t),
        inflight: this.trace.channel(m.name, 'inflight', t),
        work: workOf.get(m.name) ?? [],
      });
    }

    // A window rather than an instant: an event is a moment and a moment is
    // narrower than a frame, so at any speed above a crawl every kill in the run
    // would fall between two frames and never be drawn.
    const window = Math.max(this.duration / 240, 1);
    return {
      t,
      phase: this.trace.phaseAt(t),
      machines,
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

  private stateOf(name: string, t: number): MachineState {
    if (this.trace.channel(name, 'alive', t) < 0.5) return 'dead';
    if (this.trace.channel(name, 'frozen', t) > 0.5) return 'frozen';
    if (this.trace.channel(name, 'degraded', t) > 1.0001) return 'degraded';
    return 'alive';
  }

  /**
   * Where a call's payload is, if it is on the wire at all.
   *
   * A call is not one journey. It goes out, it is worked on, and it comes back —
   * and the middle of that is not the network, it is a machine holding the
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
    };
  }
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
  'retry',
  'rpc_timeout',
  'job_failed',
  'over_horizon',
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
 * Logarithmic, and clamped, for the same reason machine sizes are: a control
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
