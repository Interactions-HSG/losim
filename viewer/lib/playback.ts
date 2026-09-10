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
 * **The scrubber is in reference milliseconds**: the clock the simulation was
 * written in, not the compressed one the host actually ran at. Every reading
 * anywhere in the viewer is at the trace instant the playhead is at.
 *
 * **And the playhead moves through trace time evenly.** `1x` is one simulated
 * second per real second and means that everywhere — in the film, in the
 * console, in a recording. The rate buttons multiply it, so a run whose calls
 * are quicker than a frame is a run to watch at `0.25x`.
 */
import { timer, type Timer } from 'd3-timer';

export const RATES = [0.001, 0.01, 0.1, 0.25, 0.5, 1, 2, 4, 8] as const;
/** Reference milliseconds per real second at `1x` — one simulated second, per second. */
export const NORMAL = 1000;
/** How long the whole run takes at `fit`, in real seconds. */
export const FIT_SECONDS = 30;

export class Clock {
  duration: number;
  /** Where the playhead is in the **film**, in real seconds. */
  private d = 0;
  private rate: number = 1;
  private fitted = false;
  private playing = false;
  private ticker: Timer | null = null;
  private last = 0;
  private listeners = new Set<() => void>();

  constructor(duration: number) {
    this.duration = Math.max(1, duration);
  }

  /** How long the film runs at `1x`, in real seconds. */
  private get total(): number {
    return this.duration / NORMAL;
  }

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  /** The snapshot has to be a primitive, or every subscriber re-renders forever. */
  now = (): number => this.d * NORMAL;
  isPlaying = (): boolean => this.playing;
  isFitted = (): boolean => this.fitted;
  /** What the buttons show: a number, or the word. */
  label = (): string => (this.fitted ? 'fit' : `${this.rate}x`);

  /** How many film seconds pass per real second. */
  speed = (): number => (this.fitted ? Math.max(1e-6, this.total) / FIT_SECONDS : this.rate);

  /** How long the film runs at `1x`, in real seconds. */
  filmSeconds = (): number => this.total;

  private changed(): void {
    for (const fn of this.listeners) fn();
  }

  /**
   * Seek to a point in the **film**, in real seconds.
   *
   * What the recorder steps through, and the same instant `seek` names in
   * reference milliseconds — the two differ by their unit and nothing else.
   */
  seekFilm(seconds: number): void {
    const next = Math.max(0, Math.min(this.total, seconds));
    if (next === this.d) return;
    this.d = next;
    this.changed();
  }

  /** Seek to a **trace** instant: what the scrubber, the markers and a span mean. */
  seek(t: number): void {
    this.seekFilm(Math.max(0, Math.min(this.duration, t)) / NORMAL);
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
    if (this.d >= this.total) this.d = 0;
    this.playing = true;
    this.last = 0;
    this.ticker = timer((elapsed) => {
      const dt = elapsed - this.last;
      this.last = elapsed;
      this.d += (dt / 1000) * this.speed();
      if (this.d >= this.total) {
        this.d = this.total;
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

  /**
   * One frame of the film, at 30 — a frame of what is *being watched*.
   *
   * Scaled by the rate rather than fixed, because the slow rates exist to look
   * at a three-refMs message and a fixed 33-refMs step clears the whole of it in
   * one press. At `1x` this is the 33 refMs it has always been; at `0.001x` it
   * is a thirtieth of a millisecond, which is one frame of what is on screen.
   */
  step(direction: number): void {
    this.pause();
    this.d = Math.max(0, Math.min(this.total, this.d + (direction * this.speed()) / 30));
    this.changed();
  }

  /** Retargets an existing clock at a different run without losing subscribers. */
  reset(duration: number): void {
    this.pause();
    this.duration = Math.max(1, duration);
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

/**
 * What a rate does, in the unit that makes it legible.
 *
 * Below `1x` the useful number is not how often a reference second goes by — at
 * `0.001x` that is sixteen minutes, which tells nobody anything. It is what a
 * single reference **millisecond** is worth on screen, because a three-refMs
 * control message is the thing the slow rates exist for.
 *
 * Written here rather than in the playbar because the console's transport draws
 * the same buttons, and two copies of this sentence would drift.
 */
export function rateSays(rate: number): string {
  if (rate === 1) return 'one reference second per second — the run at the speed it happened';
  if (rate > 1) return `${rate}x that: a reference second every ${(1 / rate).toFixed(2)}s.`;
  const ms = 1 / rate;
  const on = ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms)}ms`;
  return `${rate}x that: one reference millisecond takes ${on} on screen.`;
}

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
