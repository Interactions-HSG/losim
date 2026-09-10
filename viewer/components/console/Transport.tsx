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
import * as stylex from '@stylexjs/stylex';

import { Scrubber } from '../Scrubber.tsx';
import { Clock, FIT_SECONDS, RATES, rateSays, refTime } from '../../lib/playback.ts';
import type { Run } from '../../lib/runs.ts';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome } from '../../lib/tokens.stylex.ts';

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
    <div {...stylex.props(sx.transport)}>
      <button
        {...stylex.props(ui.btn, ui.icon, ui.primary)}
        onClick={() => clock.toggle()}
        title={playing ? 'pause (space)' : 'play (space)'}
        aria-label={playing ? 'pause' : 'play'}
      >
        {playing ? <Pause /> : <Play />}
      </button>
      <button {...stylex.props(ui.btn, ui.icon)} onClick={() => clock.step(-1)} title="back one frame (←)">
        ◀
      </button>
      <button {...stylex.props(ui.btn, ui.icon)} onClick={() => clock.step(1)} title="on one frame (→)">
        ▶
      </button>

      <span {...stylex.props(sx.at, ui.mono)}>
        <b {...stylex.props(sx.now)}>{refTime(t)}</b>
        <span {...stylex.props(ui.muted)}> / {refTime(trace.duration)}</span>
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

      <div {...stylex.props(ui.seg)} role="group" aria-label="speed">
        {RATES.map((r) => (
          <button
            key={r}
            {...stylex.props(ui.segButton, rateLabel === `${r}x` && ui.segOn)}
            aria-pressed={rateLabel === `${r}x`}
            onClick={() => clock.setRate(r)}
            title={rateSays(r)}
          >
            {r}x
          </button>
        ))}
        <button
          {...stylex.props(ui.segButton, rateLabel === 'fit' && ui.segOn)}
          aria-pressed={rateLabel === 'fit'}
          onClick={() => clock.fit()}
          title={`the whole run in ${FIT_SECONDS} seconds, whatever it took.`}
        >
          fit
        </button>
      </div>

      <span {...stylex.props(ui.muted, sx.scope)}>
        every panel below is drawn from the events up to here
      </span>
    </div>
  );
}

const sx = stylex.create({
  transport: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    paddingBlock: '10px',
    paddingInline: '24px',
    backgroundColor: chrome.surface,
    borderBottomWidth: '1px',
    borderBottomStyle: 'solid',
    borderBottomColor: chrome.border,
  },
  at: { fontSize: '12.5px', color: chrome.text3, whiteSpace: 'nowrap' },
  /** The instant itself, against the run's length beside it. */
  now: { color: chrome.text, fontWeight: 600 },
  /** The sentence explaining the cursor is the first thing a narrow window loses. */
  scope: {
    fontSize: '11.5px',
    whiteSpace: 'nowrap',
    display: { default: 'inline', '@media (max-width: 1400px)': 'none' },
  },
});

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
