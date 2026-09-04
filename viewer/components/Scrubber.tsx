'use client';

/**
 * Where you are in the run, and how to get somewhere else.
 *
 * Shaped like a video scrubber because that is the control everybody already
 * knows how to use: drag it, click ahead of it, hover it to see where you would
 * land. What is underneath it is not a video though, and two things follow.
 *
 * **The chapters are the run's own phases.** split, map, shuffle, reduce — or,
 * on an iterative job, ten rounds of the same three. They are not decoration:
 * "which stage was this" is the question a viewer asks before any other, and a
 * bare bar makes them count pixels to answer it.
 *
 * **The markers are what went wrong.** Every kill, freeze, out-of-memory,
 * disk-full, timeout and retry, on the bar, at the instant it happened — so the
 * interesting moments in a twelve-second run are reachable without hunting for
 * them. `[` and `]` step between them.
 *
 * The whole bar is in reference milliseconds, the clock the scenario was written
 * in, so a student reading `800 refMs` here is reading the number they typed.
 */
import { useCallback, useRef, useState } from 'react';

import * as D from '../lib/design.ts';
import { refTime } from '../lib/playback.ts';
import type { TraceEvent } from '../lib/trace.ts';

export interface Chapter {
  label: string;
  t0: number;
  t1: number;
}

export interface ScrubberProps {
  t: number;
  duration: number;
  chapters: Chapter[];
  events: TraceEvent[];
  onSeek: (t: number) => void;
}

/** What colour a moment is, on the bar. Meaning, never decoration. */
const MARKER: Record<string, string> = {
  oom: D.ALARM,
  disk_full: D.ALARM,
  job_failed: D.ALARM,
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

export function Scrubber({ t, duration, chapters, events, onSeek }: ScrubberProps) {
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
  const chapterAt = hoverAt === null ? null : chapters.find((c) => hoverAt >= c.t0 && hoverAt <= c.t1);

  return (
    <div className={`scrub${dragging ? ' dragging' : ''}`}>
      {hoverAt !== null && (
        <div className="tip" style={{ left: `${hovered}%` }}>
          <span className="mono">{refTime(hoverAt)}</span>
          {chapterAt && <span className="tipch">{chapterAt.label}</span>}
        </div>
      )}

      <div
        ref={track}
        className="track"
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerLeave={() => setHoverAt(null)}
        role="slider"
        aria-label="position in the run"
        aria-valuemin={0}
        aria-valuemax={Math.round(duration)}
        aria-valuenow={Math.round(t)}
        aria-valuetext={refTime(t)}
        tabIndex={0}
      >
        {/* Chapters as segments with a hairline between, so a stage is a length
            you can see rather than a boundary you have to be told about. */}
        {chapters.length > 0 ? (
          chapters.map((c, i) => (
            <div
              key={i}
              className="chapter"
              style={{
                left: `${(c.t0 / duration) * 100}%`,
                width: `${((c.t1 - c.t0) / duration) * 100}%`,
              }}
              title={c.label}
            />
          ))
        ) : (
          <div className="chapter" style={{ left: 0, width: '100%' }} />
        )}

        <div className="played" style={{ width: `${played}%` }} />
        {hovered !== null && <div className="ahead" style={{ width: `${hovered}%` }} />}

        {events.map((e, i) => (
          <button
            key={i}
            className="mark"
            style={{
              left: `${(Number(e.t ?? 0) / duration) * 100}%`,
              background: MARKER[String(e.kind)] ?? D.PENCIL,
            }}
            title={`${e.kind} · ${e.vm ?? ''} · ${refTime(Number(e.t ?? 0))}`}
            onPointerDown={(ev) => {
              ev.stopPropagation();
              onSeek(Number(e.t ?? 0));
            }}
          />
        ))}

        <div className="knob" style={{ left: `${played}%` }} />
      </div>

      <style>{`
        .scrub { position: relative; flex: 1; padding: 10px 0; min-width: 120px; }
        .track {
          position: relative; height: 5px; border-radius: 3px;
          background: var(--surface-2); cursor: pointer; touch-action: none;
          transition: height .12s ease, transform .12s ease;
        }
        .scrub:hover .track, .scrub.dragging .track { height: 9px; }
        .track:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }

        .chapter {
          position: absolute; top: 0; bottom: 0;
          background: var(--border-strong); opacity: .55;
          border-radius: 3px;
          box-shadow: 2px 0 0 var(--surface) inset, -2px 0 0 var(--surface) inset;
        }
        .played {
          position: absolute; top: 0; bottom: 0; left: 0;
          background: var(--accent); border-radius: 3px; pointer-events: none;
        }
        .ahead {
          position: absolute; top: 0; bottom: 0; left: 0;
          background: var(--text-3); opacity: .28; border-radius: 3px; pointer-events: none;
        }
        .knob {
          position: absolute; top: 50%; width: 13px; height: 13px; margin-left: -6.5px;
          border-radius: 50%; background: var(--accent);
          border: 2px solid var(--surface); box-shadow: var(--shadow-2);
          transform: translateY(-50%) scale(0); transform-origin: center;
          transition: transform .12s ease; pointer-events: none;
        }
        .scrub:hover .knob, .scrub.dragging .knob { transform: translateY(-50%) scale(1); }

        .mark {
          position: absolute; top: 50%; width: 3px; height: 15px; margin-left: -1.5px;
          padding: 0; border: 0; border-radius: 2px; cursor: pointer;
          transform: translateY(-50%); box-shadow: 0 0 0 1.5px var(--surface);
          transition: height .12s ease, width .12s ease;
        }
        .mark:hover { width: 5px; height: 19px; }

        .tip {
          position: absolute; bottom: 100%; transform: translateX(-50%);
          margin-bottom: 2px; padding: 4px 8px; white-space: nowrap;
          display: flex; gap: 8px; align-items: baseline;
          font-size: 11.5px; color: var(--text);
          background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--r-sm); box-shadow: var(--shadow-2);
          pointer-events: none; z-index: 3;
        }
        .tipch { color: var(--text-3); }
      `}</style>
    </div>
  );
}
