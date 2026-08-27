/**
 * A clock that slows down where something is happening.
 *
 * ## The problem
 *
 * A call that took three reference milliseconds is on screen, at `1x`, for three
 * *thousandths* of a second. Nobody has ever seen one. The messages that carry
 * the most interesting payloads — the small, fast, control ones — are precisely
 * the ones that are never visible, and a film where the interesting things are
 * invisible is a film that teaches nothing.
 *
 * Slowing the whole clock down does not fix it, because the ratio is the
 * problem: a run whose calls span three orders of magnitude has no single rate
 * at which the shortest is visible and the longest is bearable.
 *
 * ## The fix, and why it is honest
 *
 * The clock is **not** linear in trace time. It runs slowly where something
 * short is happening and at full speed where nothing is, so that every visible
 * thing — every leg of every message, every stretch of computation — occupies at
 * least a fixed number of **real seconds** on screen, whatever it actually took.
 *
 * Three properties make this a change of pace rather than a change of facts:
 *
 * - **It is monotone.** Trace time never goes backwards, so nothing is reordered
 *   and nothing overlaps that did not overlap. This is the property that a
 *   naive fix — stretching each short thing where it sits — destroys.
 * - **Nothing but the pace changes.** Every number on screen is read out of the
 *   trace at the trace time the playhead is at. The scrubber is still in
 *   reference milliseconds; a machine's memory at 400 refMs is what it was at
 *   400 refMs. What changes is how long the playhead dwells there.
 * - **It is reversible and exact.** `traceAt` and `displayAt` are inverses, so
 *   seeking, stepping and recording are the same operation they were before.
 *
 * What it does distort is the *impression* of relative speed: two calls that
 * differed by a factor of a hundred will look closer than that. The spans view
 * is where relative duration is read, and the spans view is not paced — it draws
 * a linear axis, which is what it is for. The film answers "what is happening",
 * the waterfall answers "how long did it take", and this is the seam between
 * them.
 */

/** One thing that has to be visible: a leg of a message, a stretch of work. */
export interface Moment {
  t0: number;
  t1: number;
}

/** Reference milliseconds per real second at `1x` — one simulated second, per second. */
export const NORMAL = 1000;

/**
 * How long a thing must stay on screen, in real seconds.
 *
 * One second is a deliberate floor rather than a tuned number: it is about the
 * shortest interval a person can see a shape appear, cross and land in, and
 * anything less is a flicker somebody has to be told was a message.
 */
export const HOLD_SECONDS = 1;

export class Pace {
  /** How long the whole run takes to play at `1x`, in real seconds. */
  readonly total: number;

  /** Breakpoints, in trace ms, and the display second each one falls on. */
  private readonly at: Float64Array;
  private readonly on: Float64Array;

  private constructor(at: Float64Array, on: Float64Array) {
    this.at = at;
    this.on = on;
    this.total = on.length ? on[on.length - 1] : 0;
  }

  /**
   * The pace a run needs so that nothing is quicker than the eye.
   *
   * @param moments  everything that has to be seen, in trace ms
   * @param duration the run's own length, so the film covers all of it
   * @param hold     the least time on screen, in real seconds
   */
  static of(moments: readonly Moment[], duration: number, hold = HOLD_SECONDS): Pace {
    const end = Math.max(1e-6, duration);
    if (hold <= 0 || moments.length === 0) return Pace.linear(end);

    // Breakpoints: everywhere the set of active moments can change.
    const marks = new Set<number>([0, end]);
    for (const m of moments) {
      if (m.t1 <= m.t0) continue;
      if (m.t0 > 0 && m.t0 < end) marks.add(m.t0);
      if (m.t1 > 0 && m.t1 < end) marks.add(m.t1);
    }
    const cuts = [...marks].sort((a, b) => a - b);
    if (cuts.length < 2) return Pace.linear(end);

    // A sweep rather than a scan per segment: at a thousand spans the quadratic
    // version is a visible pause when a run is opened, and this is not.
    const opens = moments
      .filter((m) => m.t1 > m.t0)
      .map((m) => ({ t: m.t0, d: m.t1 - m.t0 }))
      .sort((a, b) => a.t - b.t);
    const closes = moments
      .filter((m) => m.t1 > m.t0)
      .map((m) => ({ t: m.t1, d: m.t1 - m.t0 }))
      .sort((a, b) => a.t - b.t);

    /** How many of each duration are open right now. Small: it is concurrency. */
    const live = new Map<number, number>();
    let o = 0;
    let c = 0;

    const at = new Float64Array(cuts.length);
    const on = new Float64Array(cuts.length);
    let clock = 0;

    for (let i = 0; i < cuts.length; i++) {
      const a = cuts[i];
      at[i] = a;
      on[i] = clock;
      if (i === cuts.length - 1) break;
      const b = cuts[i + 1];

      // Everything that has started by `a` and not yet ended.
      while (o < opens.length && opens[o].t <= a + 1e-9) {
        live.set(opens[o].d, (live.get(opens[o].d) ?? 0) + 1);
        o++;
      }
      while (c < closes.length && closes[c].t <= a + 1e-9) {
        const n = (live.get(closes[c].d) ?? 0) - 1;
        if (n > 0) live.set(closes[c].d, n);
        else live.delete(closes[c].d);
        c++;
      }

      // The slowest pace anything here demands. A moment of duration d that must
      // last `hold` seconds demands d/hold reference ms per second — so the
      // shortest thing open sets the pace, and everything longer gets more than
      // its minimum, which is the right way round.
      let shortest = Infinity;
      for (const d of live.keys()) if (d < shortest) shortest = d;
      const rate =
        shortest === Infinity ? NORMAL : Math.min(NORMAL, Math.max(shortest, 1e-4) / hold);

      clock += (b - a) / rate;
    }

    return new Pace(at, on);
  }

  /** No pacing at all: one simulated second per real second, throughout. */
  static linear(duration: number): Pace {
    const end = Math.max(1e-6, duration);
    return new Pace(Float64Array.from([0, end]), Float64Array.from([0, end / NORMAL]));
  }

  /** Where the playhead is in the trace, given where it is in the film. */
  traceAt(display: number): number {
    const d = Math.max(0, Math.min(this.total, display));
    const i = seek(this.on, d);
    const span = this.on[i + 1] - this.on[i];
    if (span <= 0) return this.at[i];
    return this.at[i] + ((d - this.on[i]) / span) * (this.at[i + 1] - this.at[i]);
  }

  /** Where in the film a given trace instant falls. The inverse of `traceAt`. */
  displayAt(trace: number): number {
    const t = Math.max(this.at[0], Math.min(this.at[this.at.length - 1], trace));
    const i = seek(this.at, t);
    const span = this.at[i + 1] - this.at[i];
    if (span <= 0) return this.on[i];
    return this.on[i] + ((t - this.at[i]) / span) * (this.on[i + 1] - this.on[i]);
  }

  /**
   * How much slower than real time the film is, over the whole run.
   *
   * What the reader is told, rather than the machinery: "3.2x longer than the
   * run" is a fact somebody can hold, and a table of breakpoints is not.
   */
  get stretch(): number {
    const linear = (this.at[this.at.length - 1] - this.at[0]) / NORMAL;
    return linear <= 0 ? 1 : this.total / linear;
  }
}

/** The last index whose value is at or below `x`, for a lerp between i and i+1. */
function seek(xs: Float64Array, x: number): number {
  let lo = 0;
  let hi = xs.length - 1;
  if (hi <= 0) return 0;
  while (lo < hi - 1) {
    const mid = (lo + hi) >> 1;
    if (xs[mid] <= x) lo = mid;
    else hi = mid;
  }
  return lo;
}
