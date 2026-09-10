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
import * as stylex from '@stylexjs/stylex';

import { SpanBar } from './SpanBar.tsx';
import { ms, type SpanNode } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import { taskColour } from '../../lib/theme.ts';
import { digest } from '../../lib/trace.ts';
import { P } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome } from '../../lib/tokens.stylex.ts';

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
  onHoverNode,
  t,
  onSeek,
}: {
  rows: SpanNode[];
  x: (t: number) => number;
  width: number;
  height: number;
  theme: Theme;
  critical: ReadonlySet<number>;
  collapsed: ReadonlySet<number>;
  onToggle: (id: number) => void;
  selected: number | null;
  onSelect: (id: number | null) => void;
  onHoverNode: (m: string | null) => void;
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
      {...stylex.props(sx.fall)}
      ref={box}
      onScroll={(e) => setScroll((e.target as HTMLDivElement).scrollTop)}
    >
      <div {...stylex.props(sx.body)} style={{ height: rows.length * ROW }}>
        <svg
          width={width}
          height={rows.length * ROW}
          {...stylex.props(sx.bars)}
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
                onMouseEnter={() => onHoverNode(n.span.vm)}
                onMouseLeave={() => onHoverNode(null)}
                onClick={() => {
                  onSelect(on ? null : n.id);
                  onSeek(n.t0);
                }}
                {...stylex.props(sx.hit)}
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
        <div {...stylex.props(sx.tree)} style={{ width: GUTTER }}>
          {shown.map((n, i) => {
            const y = (first + i) * ROW;
            const on = selected === n.id;
            const crit = critical.has(n.id);
            return (
              <div
                key={n.id}
                {...stylex.props(sx.row, on && sx.on)}
                style={{ top: y, height: ROW, paddingLeft: 6 + n.depth * 11 }}
                onMouseEnter={() => onHoverNode(n.span.vm)}
                onMouseLeave={() => onHoverNode(null)}
                onClick={() => {
                  onSelect(on ? null : n.id);
                  onSeek(n.t0);
                }}
              >
                <button
                  {...stylex.props(sx.tw, !n.children.length && sx.hidden)}
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggle(n.id);
                  }}
                  aria-label={collapsed.has(n.id) ? 'expand' : 'collapse'}
                >
                  {collapsed.has(n.id) ? '▸' : '▾'}
                </button>
                {n.task !== null && (
                  <span {...stylex.props(sx.tk)} style={{ background: taskColour(theme, n.task) }} />
                )}
                {/* The critical path is underlined on the method, not on the row:
                    a rule under a whole row of a table of rows reads as a border. */}
                <span {...stylex.props(sx.nm, crit && sx.critical)}>{n.method}</span>
                <span {...stylex.props(sx.vm)}>{n.span.vm}</span>
                {n.to && <span {...stylex.props(sx.vm)}>→ {n.to}</span>}
                {n.crossZone && (
                  <span {...stylex.props(sx.xz)} title="crossed a zone: billed, and slower">⇄</span>
                )}
                {!n.ok && <span {...stylex.props(sx.bad)}>{String(n.span.status)}</span>}
                {collapsed.has(n.id) && n.hidden > 0 && (
                  <span {...stylex.props(sx.hid)}>+{n.hidden}</span>
                )}
                <span {...stylex.props(sx.dur)}>{ms(n.t1 - n.t0)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {selected !== null && <Detail n={rows.find((r) => r.id === selected)} theme={theme} />}
    </div>
  );
}

/** What a bar expands to: the words, and the reason it failed. */
function Detail({ n, theme }: { n: SpanNode | undefined; theme: Theme }) {
  if (!n) return null;
  const d = n.span.detail;
  return (
    <div {...stylex.props(sx.detail, theme.dark ? sx.detailDark : sx.detailLight)}>
      <div {...stylex.props(sx.dh)}>
        <strong>{n.method}</strong>
        <span {...stylex.props(ui.muted)}>{n.span.vm}</span>
        {n.to && <span {...stylex.props(ui.muted)}>→ {n.to}</span>}
        <span {...stylex.props(ui.muted, ui.mono)}>
          {ms(n.t1 - n.t0)} total · {ms(n.selfMs)} its own
        </span>
        {!n.ok && <span {...stylex.props(sx.danger)}>{String(n.span.status)}</span>}
      </div>
      {typeof d['error'] === 'string' && <P style={sx.err}>{d['error'] as string}</P>}
      <div {...stylex.props(sx.sides)}>
        {d['arg'] !== undefined && (
          <div>
            <span {...stylex.props(ui.muted)}>in</span> {digest(d['arg'], 8) || <em>empty</em>}
          </div>
        )}
        {d['result'] !== undefined && (
          <div>
            <span {...stylex.props(ui.muted)}>out</span> {digest(d['result'], 8) || <em>empty</em>}
          </div>
        )}
      </div>
    </div>
  );
}

const sx = stylex.create({
  fall: { overflow: 'auto', position: 'relative', height: '100%' },
  body: { position: 'relative' },
  bars: { position: 'absolute', inset: 0 },
  hit: { cursor: 'pointer' },
  tree: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 'auto' },
  row: {
    position: 'absolute',
    left: 0,
    right: 0,
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    fontSize: '12px',
    whiteSpace: 'nowrap',
    cursor: 'pointer',
    /* The row fades out under the bars, so the text stays readable over the
       drawing and the drawing stays visible past it. Written out three times —
       resting, hovered, selected — because a gradient has no shorthand for the
       one colour in it that changes, and StyleX will not take a helper that
       builds one: values are read at build time, not called. */
    backgroundImage: {
      default: `linear-gradient(90deg, ${chrome.surface} 78%, transparent)`,
      ':hover': `linear-gradient(90deg, ${chrome.surface2} 78%, transparent)`,
    },
  },
  on: {
    backgroundImage: `linear-gradient(90deg, ${chrome.surface2} 78%, transparent)`,
    fontWeight: 600,
  },
  tw: {
    borderWidth: 0,
    borderStyle: 'none',
    background: 'none',
    color: chrome.text3,
    font: 'inherit',
    cursor: 'pointer',
    width: '12px',
    padding: 0,
    lineHeight: 1,
  },
  /** A leaf keeps the twisty's width and loses the arrow, so the names line up. */
  hidden: { visibility: 'hidden' },
  tk: { width: '7px', height: '7px', borderRadius: '50%', flexGrow: 0, flexShrink: 0 },
  /* The method never gives up its room. A status is long — DEADLINE_EXCEEDED
     is seventeen characters — and letting flexbox settle it crushed the one
     word the row is about down to a single letter. */
  nm: { flexGrow: 0, flexShrink: 0 },
  critical: { textDecorationLine: 'underline', textUnderlineOffset: '2px' },
  vm: { color: chrome.text3, fontSize: '11px', flexGrow: 0, flexShrink: 0 },
  xz: { color: '#8FA6BC', flexGrow: 0, flexShrink: 0 },
  bad: {
    color: chrome.danger,
    fontSize: '11px',
    fontWeight: 600,
    minWidth: 0,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  hid: { color: chrome.text3, fontSize: '11px' },
  dur: {
    marginLeft: 'auto',
    paddingRight: '10px',
    color: chrome.text3,
    fontVariantNumeric: 'tabular-nums',
    fontSize: '11px',
  },
  detail: {
    position: 'sticky',
    bottom: 0,
    marginTop: '4px',
    paddingBlock: '8px',
    paddingInline: '12px',
    borderTopWidth: '1px',
    borderTopStyle: 'solid',
    borderTopColor: chrome.border,
    backdropFilter: 'blur(6px)',
    fontSize: '12px',
  },
  /* Not a token and not the scheme's: the panel sits over the drawing and is
     deliberately translucent, and it follows the *trace's* theme rather than the
     system's, because that is the theme the bars underneath were drawn in. */
  detailLight: { backgroundColor: 'rgba(255, 255, 255, 0.94)' },
  detailDark: { backgroundColor: 'rgba(20, 24, 30, 0.94)' },
  dh: { display: 'flex', gap: '10px', alignItems: 'baseline', flexWrap: 'wrap' },
  sides: { marginTop: '4px', display: 'grid', gap: '2px' },
  danger: { color: chrome.danger },
  err: { marginTop: '4px', marginRight: 0, marginBottom: 0, marginLeft: 0, color: chrome.danger },
});

/** Undo a linear time scale, which is all the axis ever is. */
function invert(x: (t: number) => number, px: number): number {
  const a = x(0);
  const b = x(1000);
  return b === a ? 0 : ((px - a) / (b - a)) * 1000;
}
