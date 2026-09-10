'use client';

/**
 * What a message is carrying, for somebody who has paused and pointed at one.
 *
 * The envelope on screen can only ever say a little — a method, a route, a
 * digest of two or three fields — because it is moving and it is small. The
 * question anybody asks next is *what is actually in it*, and DISSALy records
 * exactly that: a real system would never keep every argument and every result,
 * and this one does deliberately, because watching a computation happen is the
 * whole point of the film.
 *
 * So this is a panel and not a tooltip, for the same reason the node panel is
 * (see `NodePanel`): the native SVG `<title>` a packet carries waits a
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
import * as stylex from '@stylexjs/stylex';

import { chrome, radius } from '../lib/tokens.stylex.ts';
import { ui } from '../lib/ui.stylex.ts';

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
      {...stylex.props(msg.msg, pinned && msg.pinned)}
      style={{ left, top, width: W }}
      role="dialog"
      aria-label="this message"
      onClick={(e) => e.stopPropagation()}
    >
      <div {...stylex.props(msg.head)}>
        <strong {...stylex.props(msg.method)}>{f.method}</strong>
        <span {...stylex.props(ui.muted, ui.mono)}>
          {from} → {to}
        </span>
        {pinned && (
          <button {...stylex.props(msg.close)} onClick={onClose} aria-label="close">
            ×
          </button>
        )}
      </div>
      <div {...stylex.props(msg.sub, ui.muted)}>
        {f.returning ? 'the answer, coming back' : 'the request, going out'}
      </div>

      <dl {...stylex.props(msg.dl)}>
        <dt {...stylex.props(msg.dt)}>carries</dt>
        <dd {...stylex.props(msg.dd)} {...stylex.props(ui.mono)}>
          {f.bytes.toLocaleString()} bytes{total > 0 && ` · ${total.toLocaleString()} entries`}
        </dd>
        <dt {...stylex.props(msg.dt)}>on the wire</dt>
        <dd {...stylex.props(msg.dd)} {...stylex.props(ui.mono)}>{refTime(f.netRefMs)}</dd>
        <dt {...stylex.props(msg.dt)}>the whole call</dt>
        <dd {...stylex.props(msg.dd)} {...stylex.props(ui.mono)}>
          {refTime(f.t0)} → {refTime(f.t1)} · {refTime(f.t1 - f.t0)}
        </dd>
        {f.crossZone && (
          <>
            <dt {...stylex.props(msg.dt)}>zone</dt>
            <dd {...stylex.props(msg.dd)} {...stylex.props(msg.warn)}>crossed one: billed, and slower</dd>
          </>
        )}
        {f.failed && (
          <>
            <dt {...stylex.props(msg.dt)}>failed</dt>
            <dd {...stylex.props(msg.dd)} {...stylex.props(msg.bad)}>{f.status || 'no status recorded'}</dd>
          </>
        )}
      </dl>

      <div {...stylex.props(msg.body)}>
        {f.body === undefined || f.body === null ? (
          <span {...stylex.props(ui.muted)}>
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
        <div {...stylex.props(ui.muted, msg.note)}>
          Showing the first entries of {total.toLocaleString()} — the trace keeps a bounded sample
          and the true count, never the whole of a large collection.
        </div>
      )}

    </div>
  );
}

/** The one line the envelope itself can carry, kept beside the panel that replaced it. */
export function brief(f: Flight): string {
  return `${f.method}: ${digest(f.body)}`;
}

const msg = stylex.create({
  msg: {
    position: 'absolute',
    zIndex: 30,
    pointerEvents: 'none',
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    // `--r-md` was never defined, so this had no radius at all. `--r` is the
    // one it meant.
    borderColor: chrome.border,
    borderRadius: radius.base,
    paddingBlock: '10px',
    paddingInline: '12px',
    boxShadow: '0 10px 30px rgba(0,0,0,0.18)',
    fontSize: '12px',
    maxHeight: '270px',
    overflow: 'auto',
  },
  /** Pinned: it stops being a peek and starts taking clicks. */
  pinned: {
    pointerEvents: 'auto',
    borderColor: chrome.accent,
    boxShadow: '0 12px 36px rgba(0,0,0,0.24)',
  },
  head: { display: 'flex', gap: '8px', alignItems: 'baseline' },
  method: { fontSize: '13px' },
  close: {
    marginLeft: 'auto',
    borderWidth: 0,
    background: 'none',
    cursor: 'pointer',
    // `--muted` was never defined either; the token is `--text-2`.
    color: { default: chrome.text2, ':hover': chrome.text },
    fontSize: '15px',
    lineHeight: 1,
    paddingBlock: 0,
    paddingInline: '2px',
  },
  sub: { margin: '1px 0 7px' },
  dl: {
    display: 'grid',
    gridTemplateColumns: 'auto 1fr',
    gap: '2px 10px',
    margin: '0 0 8px',
    alignItems: 'baseline',
  },
  dt: { color: chrome.text2 },
  dd: { margin: 0 },
  warn: { color: chrome.warn },
  /** `--alarm` was never defined; `--danger` is the chrome's word for it. */
  bad: { color: chrome.danger },
  body: {
    borderTopWidth: '1px',
    borderTopStyle: 'solid',
    borderTopColor: chrome.border,
    paddingTop: '7px',
  },
  note: { marginTop: '7px', fontSize: '11px', lineHeight: 1.4 },
});
