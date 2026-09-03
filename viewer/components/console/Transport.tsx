'use client';

/**
 * The clock, in the chrome.
 *
 * It used to live under the film, which made it the film's clock: the ledger
 * could only accrue where the film was, and there was no way to ask what a run
 * had cost by the time something happened, because the only page with a cursor
 * on it was the one showing the picture.
 *
 * So it is up here now, above every view, and it is sticky — a console whose
 * cost report is two screens long is a console where the cursor has to still be
 * reachable at the bottom of it.
 *
 * Everything on this bar was already in `Film`'s playbar and is unchanged in
 * behaviour: the scrubber is still the run's own phases with its own accidents
 * marked on it, `1x` still means the run at its natural pace with the quick
 * parts held long enough to see, and every reading anywhere in the console is at
 * the trace instant this playhead is at.
 */
import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';

import { Scrubber, type Chapter } from '../Scrubber.tsx';
import { HOLD_SECONDS } from '../../lib/pace.ts';
import { Clock, FIT_SECONDS, RATES, refTime } from '../../lib/playback.ts';
import type { Run } from '../../lib/runs.ts';

/** Nothing on screen for less than this, in real seconds. `0` turns the pacing off. */
const HOLDS = [HOLD_SECONDS, 0.5, 0.25, 0] as const;

/** How long this film runs, in the shortest form that is still a duration. */
function fmtFilm(seconds: number): string {
  if (seconds >= 90)
    return `${Math.floor(seconds / 60)}:${String(Math.round(seconds % 60)).padStart(2, '0')}`;
  return `${seconds.toFixed(0)}s`;
}

export function Transport({ run, clock }: { run: Run; clock: Clock }) {
  const { trace, index } = run;
  const t = useSyncExternalStore(clock.subscribe, clock.now, clock.now);
  const playing = useSyncExternalStore(clock.subscribe, clock.isPlaying, () => false);
  const rateLabel = useSyncExternalStore(clock.subscribe, clock.label, () => '1x');
  const filmSeconds = useSyncExternalStore(clock.subscribe, clock.filmSeconds, () => 0);
  const stretch = useSyncExternalStore(clock.subscribe, clock.stretch, () => 1);

  const [hold, setHold] = useState<number>(HOLDS[0]);
  // The pace belongs to the clock, so the toggle sets it there rather than being
  // read by the frame. Re-applied when the clock changes: a new run starts held.
  useEffect(() => {
    clock.setHold(hold);
  }, [clock, hold]);

  const chapters: Chapter[] = useMemo(
    () =>
      trace
        .phases()
        .filter((p) => p.t1 > p.t0)
        .sort((a, b) => a.t0 - b.t0)
        .map((p) => ({ label: p.label, t0: p.t0, t1: p.t1 })),
    [trace],
  );
  const events = useMemo(() => index.events(), [index]);

  // Space, the arrows and the brackets, wherever you are in the console. The
  // film used to own these; a global clock has to answer to them everywhere.
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
        chapters={chapters}
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
                ? 'the film at its own pace — nothing on screen for less than a second'
                : `${r}x that pace. Above 1x the quick messages go back under a second.`
            }
          >
            {r}x
          </button>
        ))}
        <button
          aria-pressed={rateLabel === 'fit'}
          onClick={() => clock.fit()}
          title={
            `the whole film in ${FIT_SECONDS} seconds. It keeps the pacing — the quick parts still `
            + 'get far more than their share of the run — but squeezed to fit, so the one-second '
            + 'floor only holds at 1x.'
          }
        >
          fit
        </button>
      </div>

      <button
        className="btn hold"
        onClick={() => setHold(HOLDS[(HOLDS.indexOf(hold as never) + 1) % HOLDS.length])}
        aria-pressed={hold > 0}
        title={
          hold > 0
            ? `The clock slows down where things are quick, so nothing is on screen for less than ${hold}s. `
              + `This film runs ${fmtFilm(filmSeconds)} — ${stretch.toFixed(0)}x the run itself. `
              + 'Every reading is still at its true instant; only the pace changes. '
              + 'Press for a shorter hold, and a shorter film.'
            : 'One simulated second per real second — where most calls are quicker than a single frame. '
              + 'Press to slow the quick parts down again.'
        }
      >
        {hold > 0 ? '◉' : '○'} hold {hold > 0 ? `${hold}s` : 'off'}
      </button>

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
        .transport .hold { font-size: 12px; }
        .transport .scope { font-size: 11.5px; white-space: nowrap; }
        @media (max-width: 1400px) { .transport .scope { display: none; } }
        @media (max-width: 1080px) { .transport .hold { display: none; } }
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
