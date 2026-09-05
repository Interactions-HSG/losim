'use client';

/**
 * What a message is carrying, for somebody who has paused and pointed at one.
 *
 * The envelope on screen can only ever say a little — a method, a route, a
 * digest of two or three fields — because it is moving and it is small. The
 * question anybody asks next is *what is actually in it*, and losim records
 * exactly that: a real system would never keep every argument and every result,
 * and this one does deliberately, because watching a computation happen is the
 * whole point of the film.
 *
 * So this is a panel and not a tooltip, for the same reason the machine panel is
 * (see `MachinePanel`): the native SVG `<title>` a packet carries waits a
 * second to appear, cannot be scrolled, cannot be selected, and truncates the
 * one thing worth reading. A student following a word from a mapper's split
 * into a reducer's total needs to be able to *look* at the list.
 *
 * It follows the pointer and stays inside the stage, because a panel that opens
 * off the edge of the window is a panel nobody can read.
 */
import { digest, entries } from '../lib/trace.ts';
import type { Flight } from '../lib/frame.ts';
import { refTime } from '../lib/playback.ts';
import { Payload } from './Payload.tsx';

export function MessagePanel({
  f,
  at,
  within,
  pinned = false,
  onClose,
}: {
  f: Flight;
  /** Where the pointer is, in pixels within the stage. */
  at: [number, number];
  /** How big the stage is, so the panel can stay inside it. */
  within: [number, number];
  /**
   * Whether this was clicked rather than merely pointed at.
   *
   * It decides whether the panel takes the pointer. Hovering, it must not: the
   * moment it did, moving towards it would leave the packet and close it. Pinned,
   * it must — because a payload of a thousand entries has to be scrollable, and
   * that is the case this panel exists for.
   */
  pinned?: boolean;
  onClose?: () => void;
}) {
  const W = 320;
  const [px, py] = at;
  const [sw, sh] = within;
  // Flip to the other side of the pointer rather than being clipped by the edge.
  const left = px + W + 24 < sw ? px + 16 : Math.max(8, px - W - 16);
  const top = Math.max(8, Math.min(py + 12, sh - 280));

  const from = f.returning ? f.to : f.from;
  const to = f.returning ? f.from : f.to;
  const [, total] = entries(f.body);

  return (
    <div
      className={`msg${pinned ? ' pinned' : ''}`}
      style={{ left, top, width: W }}
      role="dialog"
      aria-label="this message"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="head">
        <strong>{f.method}</strong>
        <span className="muted mono">
          {from} → {to}
        </span>
        {pinned && (
          <button className="x" onClick={onClose} aria-label="close">
            ×
          </button>
        )}
      </div>
      <div className="sub muted">
        {f.returning ? 'the answer, coming back' : 'the request, going out'}
      </div>

      <dl>
        <dt>carries</dt>
        <dd className="mono">
          {f.bytes.toLocaleString()} bytes{total > 0 && ` · ${total.toLocaleString()} entries`}
        </dd>
        <dt>on the wire</dt>
        <dd className="mono">{refTime(f.netRefMs)}</dd>
        <dt>the whole call</dt>
        <dd className="mono">
          {refTime(f.t0)} → {refTime(f.t1)} · {refTime(f.t1 - f.t0)}
        </dd>
        {f.crossZone && (
          <>
            <dt>zone</dt>
            <dd className="warn">crossed one: billed, and slower</dd>
          </>
        )}
        {f.failed && (
          <>
            <dt>failed</dt>
            <dd className="bad">{f.status || 'no status recorded'}</dd>
          </>
        )}
      </dl>

      <div className="body">
        {f.body === undefined || f.body === null ? (
          <span className="muted">
            {f.failed ? 'nothing came back' : 'no payload recorded on this leg'}
          </span>
        ) : (
          <Payload
            detail={f.returning ? { result: f.body } : { arg: f.body }}
            open
            label={f.returning ? 'what came back' : 'what was sent'}
          />
        )}
      </div>

      {total > 12 && (
        // The trace bounds a collection at twelve and records the real count in
        // the marker it appends. Saying so is the difference between a reader
        // believing a reducer folded thirteen keys and knowing it folded 1,118.
        <div className="muted note">
          Showing the first entries of {total.toLocaleString()} — the trace keeps a bounded sample
          and the true count, never the whole of a large collection.
        </div>
      )}

      <style>{`
        .msg {
          position: absolute; z-index: 30; pointer-events: none;
          background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--r-md); padding: 10px 12px;
          box-shadow: 0 10px 30px rgba(0,0,0,0.18); font-size: 12px;
          max-height: 270px; overflow: auto;
        }
        .msg.pinned {
          pointer-events: auto;
          border-color: var(--accent);
          box-shadow: 0 12px 36px rgba(0,0,0,0.24);
        }
        .msg .head { display: flex; gap: 8px; align-items: baseline; }
        .msg .x {
          margin-left: auto; border: 0; background: none; cursor: pointer;
          color: var(--muted); font-size: 15px; line-height: 1; padding: 0 2px;
        }
        .msg .x:hover { color: var(--text); }
        .msg .head strong { font-size: 13px; }
        .msg .sub { margin: 1px 0 7px; }
        .msg dl {
          display: grid; grid-template-columns: auto 1fr; gap: 2px 10px;
          margin: 0 0 8px; align-items: baseline;
        }
        .msg dt { color: var(--muted); }
        .msg dd { margin: 0; }
        .msg .warn { color: var(--warn); }
        .msg .bad { color: var(--alarm); }
        .msg .body { border-top: 1px solid var(--border); padding-top: 7px; }
        .msg .note { margin-top: 7px; font-size: 11px; line-height: 1.4; }
      `}</style>
    </div>
  );
}

/** The one line the envelope itself can carry, kept beside the panel that replaced it. */
export function brief(f: Flight): string {
  return `${f.method}: ${digest(f.body)}`;
}
