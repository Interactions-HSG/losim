'use client';

/**
 * Where you are in the run, and how to get somewhere else.
 *
 * Shaped like a video scrubber because that is the control everybody already
 * knows how to use: drag it, click ahead of it, hover it to see where you would
 * land. What is underneath it is not a video though, and two things follow.
 *
 * **The markers are what went wrong.** Every kill, freeze, out-of-memory,
 * disk-full, timeout and retry, on the bar, at the instant it happened — so the
 * interesting moments in a twelve-second run are reachable without hunting for
 * them. `[` and `]` step between them.
 *
 * The whole bar is in reference milliseconds, the clock the simulation was
 * written in, so a student reading `800 refMs` here is reading the number they
 * typed.
 */
import { useCallback, useRef, useState } from 'react';
import * as stylex from '@stylexjs/stylex';

import * as D from '../lib/design.ts';
import { chrome, font, radius, shadow } from '../lib/tokens.stylex.ts';
import { scrubVars } from './scrubber.stylex.ts';
import { refTime } from '../lib/playback.ts';
import type { TraceEvent } from '../lib/trace.ts';

export interface ScrubberProps {
  t: number;
  duration: number;
  events: TraceEvent[];
  onSeek: (t: number) => void;
}

/** What colour a moment is, on the bar. Meaning, never decoration. */
const MARKER: Record<string, string> = {
  oom: D.ALARM,
  disk_full: D.ALARM,
  failed: D.ALARM,
  kill: D.ALARM,
  rpc_timeout: D.WARN,
  rpc_error: D.WARN,
  retry: D.WARN,
  freeze: D.CHILL,
  thaw: D.CHILL,
  degrade: D.WARN,
  spot_notice: D.WARN,
  partition: D.WARN,
  // The repair reads in the same colour as the break, the way thaw reads in
  // freeze's: a paired event is one interval, and its two ends belong to each
  // other more than either belongs to a severity.
  heal: D.WARN,
  restart: '#4F8A5B',
  over_horizon: D.NARRATE,
  // The lecturer's pen, which is what D.NARRATE is for.
  log: D.NARRATE,
};

export function Scrubber({ t, duration, events, onSeek }: ScrubberProps) {
  const track = useRef<HTMLDivElement>(null);
  const [hoverAt, setHoverAt] = useState<number | null>(null);
  const [dragging, setDragging] = useState(false);

  const timeAt = useCallback(
    (clientX: number): number => {
      const box = track.current!.getBoundingClientRect();
      const share = (clientX - box.left) / Math.max(1, box.width);
      return Math.max(0, Math.min(1, share)) * duration;
    },
    [duration],
  );

  const down = useCallback(
    (e: React.PointerEvent) => {
      e.currentTarget.setPointerCapture(e.pointerId);
      setDragging(true);
      onSeek(timeAt(e.clientX));
    },
    [onSeek, timeAt],
  );

  const move = useCallback(
    (e: React.PointerEvent) => {
      const at = timeAt(e.clientX);
      setHoverAt(at);
      if (dragging) onSeek(at);
    },
    [dragging, onSeek, timeAt],
  );

  const up = useCallback((e: React.PointerEvent) => {
    e.currentTarget.releasePointerCapture(e.pointerId);
    setDragging(false);
  }, []);

  const played = (t / duration) * 100;
  const hovered = hoverAt === null ? null : (hoverAt / duration) * 100;

  return (
    <div {...stylex.props(styles.scrub, dragging && styles.active)}>
      {hoverAt !== null && (
        <div {...stylex.props(styles.tip, styles.at(`${hovered}%`))}>
          <span {...stylex.props(styles.mono)}>{refTime(hoverAt)}</span>
        </div>
      )}

      <div
        ref={track}
        {...stylex.props(styles.track)}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerLeave={() => setHoverAt(null)}
        role="slider"
        aria-label="Run position"
        aria-valuemin={0}
        aria-valuemax={Math.round(duration)}
        aria-valuenow={Math.round(t)}
        aria-valuetext={refTime(t)}
        tabIndex={0}
      >
        <div {...stylex.props(styles.bar)} />

        <div {...stylex.props(styles.played, styles.wide(`${played}%`))} />
        {hovered !== null && <div {...stylex.props(styles.ahead, styles.wide(`${hovered}%`))} />}

        {events.map((e, i) => (
          <button
            key={i}
            {...stylex.props(
              styles.mark,
              styles.at(`${(Number(e.t ?? 0) / duration) * 100}%`),
              styles.paint(MARKER[String(e.kind)] ?? D.PENCIL),
            )}
            title={`${String(e.kind).replaceAll('_', ' ')} on ${e.vm ?? 'the cluster'} at ${refTime(Number(e.t ?? 0))}`}
            aria-label={`${String(e.kind).replaceAll('_', ' ')} on ${e.vm ?? 'the cluster'} at ${refTime(Number(e.t ?? 0))}`}
            onPointerDown={(ev) => {
              ev.stopPropagation();
              onSeek(Number(e.t ?? 0));
            }}
          />
        ))}

        <div {...stylex.props(styles.knob, styles.at(`${played}%`))} />
      </div>
    </div>
  );
}

const styles = stylex.create({
  scrub: {
    position: 'relative',
    flex: 1,
    paddingBlock: '10px',
    minWidth: '120px',
    // What the children read. The bar thickens and the handle appears together,
    // because they are one gesture.
    [scrubVars.trackHeight]: { default: '5px', ':hover': '9px' },
    [scrubVars.knobScale]: { default: '0', ':hover': '1' },
  },
  // A drag keeps them up after the pointer has left the bar, which is the whole
  // point of capturing it.
  active: { [scrubVars.trackHeight]: '9px', [scrubVars.knobScale]: '1' },

  track: {
    position: 'relative',
    height: scrubVars.trackHeight,
    borderRadius: '3px',
    backgroundColor: chrome.surface2,
    cursor: 'pointer',
    touchAction: 'none',
    transitionProperty: 'height',
    transitionDuration: '.12s',
    transitionTimingFunction: 'ease',
    outline: { default: null, ':focus-visible': `2px solid ${chrome.accent}` },
    outlineOffset: '3px',
  },
  bar: {
    position: 'absolute',
    inset: 0,
    backgroundColor: chrome.borderStrong,
    opacity: 0.55,
    borderRadius: '3px',
  },
  played: {
    position: 'absolute',
    insetBlock: 0,
    left: 0,
    backgroundColor: chrome.accent,
    borderRadius: '3px',
    pointerEvents: 'none',
  },
  ahead: {
    position: 'absolute',
    insetBlock: 0,
    left: 0,
    backgroundColor: chrome.text3,
    opacity: 0.28,
    borderRadius: '3px',
    pointerEvents: 'none',
  },
  knob: {
    position: 'absolute',
    top: '50%',
    width: '13px',
    height: '13px',
    marginLeft: '-6.5px',
    borderRadius: '50%',
    backgroundColor: chrome.accent,
    borderWidth: '2px',
    borderStyle: 'solid',
    borderColor: chrome.surface,
    boxShadow: shadow.s2,
    transform: `translateY(-50%) scale(${scrubVars.knobScale})`,
    transformOrigin: 'center',
    transitionProperty: 'transform',
    transitionDuration: '.12s',
    transitionTimingFunction: 'ease',
    pointerEvents: 'none',
  },
  mark: {
    position: 'absolute',
    top: '50%',
    width: { default: '3px', ':hover': '5px' },
    height: { default: '15px', ':hover': '19px' },
    marginLeft: '-1.5px',
    padding: 0,
    borderWidth: 0,
    borderRadius: '2px',
    cursor: 'pointer',
    transform: 'translateY(-50%)',
    boxShadow: `0 0 0 1.5px ${chrome.surface}`,
    transitionProperty: 'height, width',
    transitionDuration: '.12s',
    transitionTimingFunction: 'ease',
  },
  tip: {
    position: 'absolute',
    bottom: '100%',
    transform: 'translateX(-50%)',
    marginBottom: '2px',
    paddingBlock: '4px',
    paddingInline: '8px',
    whiteSpace: 'nowrap',
    display: 'flex',
    gap: '8px',
    alignItems: 'baseline',
    fontSize: '11.5px',
    color: chrome.text,
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.sm,
    boxShadow: shadow.s2,
    pointerEvents: 'none',
    zIndex: 3,
  },
  mono: { fontFamily: font.mono },

  // The three the render decides: a position along the bar, a width, a meaning.
  at: (left: string) => ({ left }),
  wide: (width: string) => ({ width }),
  paint: (backgroundColor: string) => ({ backgroundColor }),
});
