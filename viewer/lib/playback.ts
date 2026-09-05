'use client';

/**
 * The clock, deliberately outside React.
 *
 * At 60 fps a clock held in `useState` is sixty re-renders of the whole tree per
 * second, and the scrubber, the panel and the views do not all need to re-render
 * at that rate. So `t` lives in a plain object that a `d3.timer` advances, and
 * components subscribe to it through `useSyncExternalStore` — each one deciding
 * for itself how much of it it needs.
 *
 * **The scrubber is in reference milliseconds**: the clock the scenario was
 * written in, not the compressed one the host actually ran at. Every reading
 * anywhere in the viewer is at the trace instant the playhead is at.
 *
 * **But the playhead does not move through trace time evenly.** It moves through
 * *film* time, and `lib/pace.ts` maps that to trace time so that every message
 * and every stretch of work is on screen for at least a second, however briefly
 * it really happened. Without that, at `1x`, a three-millisecond call is three
 * thousandths of a second of film and nobody has ever seen one.
 *
 * So `1x` means the run at its natural pace, with the quick parts held long
 * enough to read — not literally one simulated second per real second. The rate
 * buttons multiply that. `linear()` turns the pacing off for anybody who wants
 * the literal reading, and the transport says which it is in.
 */
import { timer, type Timer } from 'd3-timer';

import { HOLD_SECONDS, NORMAL, Pace } from './pace.ts';

export const RATES = [0.25, 0.5, 1, 2, 4, 8] as const;
/** How long the whole run takes at `fit`, in real seconds. */
export const FIT_SECONDS = 30;

export class Clock {
  duration: number;
  /** Where the playhead is in the **film**, in real seconds. */
  private d = 0;
  private pace: Pace;
  private hold = HOLD_SECONDS;
  private rate: number = 1;
  private fitted = false;
  private playing = false;
  private ticker: Timer | null = null;
  private last = 0;
  private listeners = new Set<() => void>();

  constructor(duration: number, moments: readonly { t0: number; t1: number }[] = []) {
    this.duration = Math.max(1, duration);
    this.pace = Pace.of(moments, this.duration, this.hold);
    this.moments = moments;
  }

  private moments: readonly { t0: number; t1: number }[];

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  /** The snapshot has to be a primitive, or every subscriber re-renders forever. */
  now = (): number => this.pace.traceAt(this.d);
  isPlaying = (): boolean => this.playing;
  isFitted = (): boolean => this.fitted;
  /** What the buttons show: a number, or the word. */
  label = (): string => (this.fitted ? 'fit' : `${this.rate}x`);

  /** How many film seconds pass per real second. */
  speed = (): number =>
    this.fitted ? Math.max(1e-6, this.pace.total) / FIT_SECONDS : this.rate;

  /** How long the film runs at `1x`, in real seconds. */
  filmSeconds = (): number => this.pace.total;

  /** How much longer the film is than the run itself. 1 when nothing is paced. */
  stretch = (): number => this.pace.stretch;

  /** The least time anything stays on screen, in real seconds. 0 when off. */
  holding = (): number => this.hold;

  /**
   * Change how long things are held, or turn the pacing off with 0.
   *
   * The playhead keeps its place in the *trace*, not in the film: somebody who
   * has scrubbed to the moment a machine died and then slows the film down is
   * still looking at that moment.
   */
  setHold(seconds: number): void {
    const where = this.now();
    this.hold = Math.max(0, seconds);
    this.pace = this.hold > 0 ? Pace.of(this.moments, this.duration, this.hold)
                              : Pace.linear(this.duration);
    this.d = this.pace.displayAt(where);
    this.changed();
  }

  private changed(): void {
    for (const fn of this.listeners) fn();
  }

  /**
   * Seek to a point in the **film**, in real seconds.
   *
   * What the recorder steps through. Recording in trace time would undo the
   * whole of the pacing: the video would spend its frames evenly across the run
   * and the quick messages would be back to one frame each — which is precisely
   * the film a lecture cannot use.
   */
  seekFilm(seconds: number): void {
    const next = Math.max(0, Math.min(this.pace.total, seconds));
    if (next === this.d) return;
    this.d = next;
    this.changed();
  }

  /** Seek to a **trace** instant: what the scrubber, the markers and a span mean. */
  seek(t: number): void {
    const next = this.pace.displayAt(Math.max(0, Math.min(this.duration, t)));
    if (next === this.d) return;
    this.d = next;
    this.changed();
  }

  setRate(rate: number): void {
    this.fitted = false;
    this.rate = rate;
    this.changed();
  }

  fit(): void {
    this.fitted = true;
    this.changed();
  }

  play(): void {
    if (this.playing) return;
    // Starting from the end is a replay, not a no-op: nobody presses play on a
    // finished film meaning "do nothing".
    if (this.d >= this.pace.total) this.d = 0;
    this.playing = true;
    this.last = 0;
    this.ticker = timer((elapsed) => {
      const dt = elapsed - this.last;
      this.last = elapsed;
      // Film seconds, not trace milliseconds: how far that advances the trace is
      // the pace's business and varies with what is happening.
      this.d += (dt / 1000) * this.speed();
      if (this.d >= this.pace.total) {
        this.d = this.pace.total;
        this.pause();
      }
      this.changed();
    });
    this.changed();
  }

  pause(): void {
    this.ticker?.stop();
    this.ticker = null;
    if (!this.playing) return;
    this.playing = false;
    this.changed();
  }

  toggle(): void {
    if (this.playing) this.pause();
    else this.play();
  }

  /** One frame of the film, at 30 — a frame of *film*, so it is a visible step. */
  step(direction: number): void {
    this.pause();
    this.d = Math.max(0, Math.min(this.pace.total, this.d + direction / 30));
    this.changed();
  }

  /** Retargets an existing clock at a different run without losing subscribers. */
  reset(duration: number, moments: readonly { t0: number; t1: number }[] = []): void {
    this.pause();
    this.duration = Math.max(1, duration);
    this.moments = moments;
    this.pace = this.hold > 0 ? Pace.of(moments, this.duration, this.hold)
                              : Pace.linear(this.duration);
    this.d = 0;
    this.changed();
  }

  dispose(): void {
    this.ticker?.stop();
    this.ticker = null;
    this.listeners.clear();
  }
}

/** How many frames the whole run is, when it is recorded at `fit`. */
export function frames(duration: number, fps = 30): number {
  return Math.max(1, Math.round(FIT_SECONDS * fps));
}

/** The rate the film would run at with no pacing, for the readout. */
export const LINEAR_RATE = NORMAL;

/** A reference-time reading, in the shortest form that still says which one it is. */
export function refTime(ms: number): string {
  if (ms >= 60_000) {
    const m = Math.floor(ms / 60_000);
    const s = Math.floor((ms % 60_000) / 1000);
    return `${m}:${String(s).padStart(2, '0')}`;
  }
  if (ms >= 10_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`;
  return `${Math.round(ms)}ms`;
}
