'use client';

/**
 * The clock, in the chrome.
 *
 * It lives above every view rather than under the film, so a cursor on the
 * run is never confined to wherever the film happens to be open: there is
 * always a way to ask what a run had cost by the time something happened,
 * on whichever page is showing.
 *
 * It sits up here, sticky, because a console whose cost report is two
 * screens long is a console where the cursor has to still be reachable at
 * the bottom of it.
 *
 * Its behaviour matches `Film`'s own playbar exactly: the scrubber is the
 * run's own phases with its own accidents marked on it, `1x` is one reference
 * second per second, and every reading anywhere in the console is at the trace
 * instant this playhead is at.
 */
import { useEffect, useMemo, useSyncExternalStore } from 'react';

import { Scrubber } from '../Scrubber.tsx';
import { Clock, FIT_SECONDS, RATES, refTime } from '../../lib/playback.ts';
import type { Run } from '../../lib/runs.ts';

export function Transport({ run, clock }: { run: Run; clock: Clock }) {
  const { trace, index } = run;
  const t = useSyncExternalStore(clock.subscribe, clock.now, clock.now);
  const playing = useSyncExternalStore(clock.subscribe, clock.isPlaying, () => false);
  const rateLabel = useSyncExternalStore(clock.subscribe, clock.label, () => '1x');

  const events = useMemo(() => index.events(), [index]);

  // Space, the arrows and the brackets, wherever you are in the console. A
  // global clock has to answer to them everywhere, not only where the film is open.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) return;
      if (e.key === ' ') {
        e.preventDefault();
        clock.toggle();
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        clock.step(e.shiftKey ? 10 : 1);
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        clock.step(e.shiftKey ? -10 : -1);
      } else if (e.key === '[' || e.key === ']') {
        const now = clock.now();
        const ts = events.map((x) => Number(x.t ?? 0)).sort((a, b) => a - b);
        const next =
          e.key === ']' ? ts.find((x) => x > now + 1) : [...ts].reverse().find((x) => x < now - 1);
        if (next !== undefined) {
          clock.pause();
          clock.seek(next);
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [clock, events]);

  return (
    <div className="transport">
      <button
        className="btn icon primary"
        onClick={() => clock.toggle()}
        title={playing ? 'pause (space)' : 'play (space)'}
        aria-label={playing ? 'pause' : 'play'}
      >
        {playing ? <Pause /> : <Play />}
      </button>
      <button className="btn icon" onClick={() => clock.step(-1)} title="back one frame (←)">
        ◀
      </button>
      <button className="btn icon" onClick={() => clock.step(1)} title="on one frame (→)">
        ▶
      </button>

      <span className="at mono">
        <b>{refTime(t)}</b>
        <span className="muted"> / {refTime(trace.duration)}</span>
      </span>

      <Scrubber
        t={t}
        duration={trace.duration}
        events={events}
        onSeek={(to) => {
          clock.pause();
          clock.seek(to);
        }}
      />

      <div className="seg" role="group" aria-label="speed">
        {RATES.map((r) => (
          <button
            key={r}
            aria-pressed={rateLabel === `${r}x`}
            onClick={() => clock.setRate(r)}
            title={
              r === 1
                ? 'one reference second per second — the run at the speed it happened'
                : `${r}x that: a reference second every ${(1 / r).toFixed(2)}s.`
            }
          >
            {r}x
          </button>
        ))}
        <button
          aria-pressed={rateLabel === 'fit'}
          onClick={() => clock.fit()}
          title={`the whole run in ${FIT_SECONDS} seconds, whatever it took.`}
        >
          fit
        </button>
      </div>

      <span className="scope muted">
        every panel below is drawn from the events up to here
      </span>

      <style>{`
        .transport {
          display: flex; align-items: center; gap: 10px;
          padding: 10px 24px;
          background: var(--surface);
          border-bottom: 1px solid var(--border);
        }
        .transport .at {
          font-size: 12.5px; color: var(--text-3); white-space: nowrap;
          font-variant-numeric: tabular-nums;
        }
        .transport .at b { color: var(--text); font-weight: 600; }
        .transport .scope { font-size: 11.5px; white-space: nowrap; }
        @media (max-width: 1400px) { .transport .scope { display: none; } }
      `}</style>
    </div>
  );
}

function Play() {
  return (
    <svg width="12" height="13" viewBox="0 0 12 13" aria-hidden>
      <path d="M1.5 1.2 10.6 6.5 1.5 11.8Z" fill="currentColor" />
    </svg>
  );
}

function Pause() {
  return (
    <svg width="11" height="13" viewBox="0 0 11 13" aria-hidden>
      <rect x="0.6" y="1" width="3.3" height="11" rx="1" fill="currentColor" />
      <rect x="7.1" y="1" width="3.3" height="11" rx="1" fill="currentColor" />
    </svg>
  );
}
