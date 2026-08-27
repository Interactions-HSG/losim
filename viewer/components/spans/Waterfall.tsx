'use client';

/**
 * Causality — the primary view.
 *
 * One row per span in tree order, indented by depth, bars on a shared time axis.
 * Every distributed-tracing tool converged on this shape because it is right;
 * what losim adds is underneath — segmented bars (SpanBar), self time drawn, the
 * critical path outlined, and **the payload on the span**, which a production
 * tracer cannot record and this one does deliberately (D8 rule 4).
 *
 * Rows are a fixed height, so windowing is a `slice` and a fifteen-thousand-span
 * trace scrolls without a virtualisation library.
 */
import { useEffect, useRef, useState } from 'react';

import { SpanBar } from './SpanBar.tsx';
import { ms, type Node } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import { taskColour } from '../../lib/theme.ts';
import { digest } from '../../lib/trace.ts';

export const ROW = 22;
const GUTTER = 356;

export function Waterfall({
  rows,
  x,
  width,
  height,
  theme,
  critical,
  collapsed,
  onToggle,
  selected,
  onSelect,
  onHoverMachine,
  t,
  onSeek,
}: {
  rows: Node[];
  x: (t: number) => number;
  width: number;
  height: number;
  theme: Theme;
  critical: ReadonlySet<number>;
  collapsed: ReadonlySet<number>;
  onToggle: (id: number) => void;
  selected: number | null;
  onSelect: (id: number | null) => void;
  onHoverMachine: (m: string | null) => void;
  t: number;
  onSeek: (t: number) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [scroll, setScroll] = useState(0);
  // Measured here rather than taken on trust from the parent: the window of rows
  // to draw *is* the height, and a height that arrives a frame late leaves the
  // bottom of the list simply missing rather than merely misaligned.
  const [tall, setTall] = useState(height);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setTall(el.clientHeight));
    ro.observe(el);
    setTall(el.clientHeight);
    return () => ro.disconnect();
  }, []);

  const view = Math.max(tall, height);
  const first = Math.max(0, Math.floor(scroll / ROW) - 6);
  const last = Math.min(rows.length, Math.ceil((scroll + view) / ROW) + 6);
  const shown = rows.slice(first, last);

  return (
    <div
      className="fall"
      ref={box}
      onScroll={(e) => setScroll((e.target as HTMLDivElement).scrollTop)}
      style={{ height: '100%' }}
    >
      <div style={{ height: rows.length * ROW, position: 'relative' }}>
        <svg
          width={width}
          height={rows.length * ROW}
          style={{ position: 'absolute', inset: 0 }}
          onDoubleClick={(e) => {
            const rect = box.current?.getBoundingClientRect();
            if (!rect) return;
            const at = e.clientX - rect.left + (box.current?.scrollLeft ?? 0);
            if (at > GUTTER) onSeek(invert(x, at));
          }}
        >
          <defs>
            <pattern id="fray" width="4" height="4" patternUnits="userSpaceOnUse">
              <path d="M0 4 L4 0" stroke={theme.pencil} strokeWidth="1" />
            </pattern>
          </defs>

          {/* The film's instant, on the same axis. */}
          <line x1={x(t)} x2={x(t)} y1={0} y2={rows.length * ROW} stroke={theme.ink} strokeWidth={1} opacity={0.35} />

          {shown.map((n, i) => {
            const y = (first + i) * ROW;
            const on = selected === n.id;
            return (
              <g
                key={n.id}
                onMouseEnter={() => onHoverMachine(n.span.vm)}
                onMouseLeave={() => onHoverMachine(null)}
                onClick={() => {
                  onSelect(on ? null : n.id);
                  onSeek(n.t0);
                }}
                style={{ cursor: 'pointer' }}
              >
                <rect x={0} y={y} width={width} height={ROW} fill={on ? theme.faint : 'transparent'} />
                <SpanBar
                  n={n}
                  x={x}
                  y={y + 5}
                  h={ROW - 10}
                  theme={theme}
                  critical={critical.has(n.id)}
                  dim={false}
                />
              </g>
            );
          })}
        </svg>

        {/* The tree itself is HTML, over the bars, because it is text with a
            disclosure control and SVG is a poor place to keep either. */}
        <div className="tree" style={{ width: GUTTER }}>
          {shown.map((n, i) => {
            const y = (first + i) * ROW;
            const on = selected === n.id;
            return (
              <div
                key={n.id}
                className={`row${on ? ' on' : ''}${critical.has(n.id) ? ' crit' : ''}`}
                style={{ top: y, height: ROW, paddingLeft: 6 + n.depth * 11 }}
                onMouseEnter={() => onHoverMachine(n.span.vm)}
                onMouseLeave={() => onHoverMachine(null)}
                onClick={() => {
                  onSelect(on ? null : n.id);
                  onSeek(n.t0);
                }}
              >
                <button
                  className="tw"
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggle(n.id);
                  }}
                  style={{ visibility: n.children.length ? 'visible' : 'hidden' }}
                  aria-label={collapsed.has(n.id) ? 'expand' : 'collapse'}
                >
                  {collapsed.has(n.id) ? '▸' : '▾'}
                </button>
                {n.task !== null && (
                  <span className="tk" style={{ background: taskColour(theme, n.task) }} />
                )}
                <span className="nm">{n.method}</span>
                <span className="vm">{n.span.vm}</span>
                {n.to && <span className="to">→ {n.to}</span>}
                {n.crossZone && <span className="xz" title="crossed a zone: billed, and slower">⇄</span>}
                {!n.ok && <span className="bad">{String(n.span.status)}</span>}
                {collapsed.has(n.id) && n.hidden > 0 && <span className="hid">+{n.hidden}</span>}
                <span className="dur">{ms(n.t1 - n.t0)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {selected !== null && <Detail n={rows.find((r) => r.id === selected)} theme={theme} />}

      <style>{`
        .fall { overflow: auto; position: relative; }
        .tree { position: absolute; inset: 0 auto 0 0; }
        .tree .row {
          position: absolute; left: 0; right: 0; display: flex; align-items: center; gap: 6px;
          font-size: 12px; white-space: nowrap; cursor: pointer;
          background: linear-gradient(90deg, var(--surface) 78%, transparent);
        }
        .tree .row:hover { background: linear-gradient(90deg, var(--surface-2) 78%, transparent); }
        .tree .row.on { background: linear-gradient(90deg, var(--surface-2) 78%, transparent); font-weight: 600; }
        .tree .row.crit .nm { text-decoration: underline; text-underline-offset: 2px; }
        .tw {
          border: 0; background: none; color: var(--text-3); font: inherit; cursor: pointer;
          width: 12px; padding: 0; line-height: 1;
        }
        .tk { width: 7px; height: 7px; border-radius: 50%; flex: none; }
        /* The method never gives up its room. A status is long — DEADLINE_EXCEEDED
           is seventeen characters — and letting flexbox settle it crushed the one
           word the row is about down to a single letter. */
        .nm { flex: none; }
        .vm { color: var(--text-3); font-size: 11px; flex: none; }
        .to { color: var(--text-3); font-size: 11px; flex: none; }
        .xz { color: #8FA6BC; flex: none; }
        .bad {
          color: var(--danger); font-size: 11px; font-weight: 600;
          min-width: 0; overflow: hidden; text-overflow: ellipsis;
        }
        .hid { color: var(--text-3); font-size: 11px; }
        .dur {
          margin-left: auto; padding-right: 10px; color: var(--text-3);
          font-variant-numeric: tabular-nums; font-size: 11px;
        }
      `}</style>
    </div>
  );
}

/** What a bar expands to: the words, and the reason it failed. */
function Detail({ n, theme }: { n: Node | undefined; theme: Theme }) {
  if (!n) return null;
  const d = n.span.detail;
  return (
    <div className="detail">
      <div className="dh">
        <strong>{n.method}</strong>
        <span className="muted">{n.span.vm}</span>
        {n.to && <span className="muted">→ {n.to}</span>}
        <span className="muted mono">
          {ms(n.t1 - n.t0)} total · {ms(n.selfMs)} its own
        </span>
        {!n.ok && <span style={{ color: '#C4342A' }}>{String(n.span.status)}</span>}
      </div>
      {typeof d['error'] === 'string' && <p className="err">{d['error'] as string}</p>}
      <div className="sides">
        {d['arg'] !== undefined && (
          <div>
            <span className="muted">in</span> {digest(d['arg'], 8) || <em>empty</em>}
          </div>
        )}
        {d['result'] !== undefined && (
          <div>
            <span className="muted">out</span> {digest(d['result'], 8) || <em>empty</em>}
          </div>
        )}
      </div>
      <style>{`
        .detail {
          position: sticky; bottom: 0; margin-top: 4px; padding: 8px 12px;
          background: ${theme.dark ? 'rgba(20,24,30,.94)' : 'rgba(255,255,255,.94)'};
          border-top: 1px solid var(--border); backdrop-filter: blur(6px);
          font-size: 12px;
        }
        .dh { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
        .sides { margin-top: 4px; display: grid; gap: 2px; }
        .err { margin: 4px 0 0; color: #C4342A; }
      `}</style>
    </div>
  );
}

/** Undo a linear time scale, which is all the axis ever is. */
function invert(x: (t: number) => number, px: number): number {
  const a = x(0);
  const b = x(1000);
  return b === a ? 0 : ((px - a) / (b - a)) * 1000;
}
