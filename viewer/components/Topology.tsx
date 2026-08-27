'use client';

/**
 * Who called whom, over the whole run.
 *
 * The film answers *what is true right now* and forgets the moment it passes;
 * this answers the question the film structurally cannot — **what is the shape of
 * this system** — by keeping nothing but the shape.
 *
 * **The same layout as the film**, deliberately: a machine is in the same place
 * in both, so switching between them is a change of question and not of map.
 * That is also why nothing here reaches for a graph-layout library — the fleet
 * already has a derived shape, roles and zones, and a second engine would only
 * be a second opinion about where `m0` lives.
 *
 * Edges are weighted by **bytes**, not by call count, because a system's shape is
 * where its data goes: a coordinator that asks forty machines a one-word question
 * has forty thin edges, and the one shuffle that moved a megabyte is the fat one.
 * Cross-zone edges are tinted, because those are the ones that are billed and
 * slow — the same fact the ledger charges for.
 *
 * The clock still runs: edges thicken as the calls that make them happen, so
 * scrubbing shows the shape *arriving*. At the end of the run it is the whole
 * picture, which is the figure anyone would draw on a whiteboard.
 */
import { useMemo } from 'react';

import * as D from '../lib/design.ts';
import type { Layout } from '../lib/layout.ts';
import type { Theme } from '../lib/theme.ts';
import type { Trace } from '../lib/trace.ts';

const MARGIN = 0.4;
const CAPTION = 0.62;

interface Edge {
  from: string;
  to: string;
  calls: number;
  bytes: number;
  crossZone: boolean;
  failed: number;
  methods: Set<string>;
  /** When each call was made, so the picture can arrive rather than appear. */
  at: number[];
}

export function Topology({
  trace,
  layout,
  theme,
  t,
  hovered,
  onHover,
}: {
  trace: Trace;
  layout: Layout;
  theme: Theme;
  t: number;
  hovered: string | null;
  onHover?: (name: string | null) => void;
}) {
  const edges = useMemo(() => {
    const zoneOf = new Map(trace.machines.map((m) => [m.name, m.zone]));
    const out = new Map<string, Edge>();
    for (const s of trace.spans) {
      if (s.kind !== 'rpc') continue;
      const to = typeof s.detail['to'] === 'string' ? (s.detail['to'] as string) : null;
      if (!to || to === s.vm) continue;
      const key = `${s.vm} ${to}`;
      const e = out.get(key) ?? {
        from: s.vm,
        to,
        calls: 0,
        bytes: 0,
        crossZone: zoneOf.get(s.vm) !== zoneOf.get(to),
        failed: 0,
        methods: new Set<string>(),
        at: [],
      };
      e.calls++;
      e.bytes += Number(s.detail['bytes'] ?? 0);
      if (s.status !== 'OK' && s.status !== '') e.failed++;
      e.methods.add(s.label.replace(/^.*\./, ''));
      e.at.push(s.t0);
      out.set(key, e);
    }
    for (const e of out.values()) e.at.sort((a, b) => a - b);
    return [...out.values()];
  }, [trace]);

  const heaviest = Math.max(1, ...edges.map((e) => e.bytes));

  const halfW = layout.width / 2 + 0.35;
  const halfH = layout.height / 2;
  const minX = -halfW - MARGIN;
  const minY = -halfH - MARGIN;
  const w = 2 * (halfW + MARGIN);
  const h = 2 * halfH + 2 * MARGIN + CAPTION;

  return (
    <div className="topo card">
      <svg viewBox={`${minX} ${minY} ${w} ${h}`} style={{ display: 'block', background: theme.surface }}>
        <defs>
          <marker id="tip" markerWidth="4" markerHeight="4" refX="3.4" refY="2" orient="auto">
            <path d="M0 0 L4 2 L0 4 z" fill={theme.pencil} />
          </marker>
        </defs>

        {layout.zones.map((zone, i) => {
          const [l, r, top, bottom] = layout.zoneRect(zone);
          return (
            <g key={zone}>
              <rect
                x={l}
                y={-top}
                width={r - l}
                height={top - bottom}
                rx={0.14}
                fill={theme.zones[i % theme.zones.length]}
                stroke={theme.zoneEdge}
                strokeWidth={0.01}
              />
              <text x={l + 0.2} y={-top + 0.27} fontSize={0.13} fill={theme.zoneLabel} letterSpacing="0.03">
                {zone.toUpperCase()}
              </text>
            </g>
          );
        })}

        {edges.map((e, i) => {
          const made = e.at.filter((x) => x <= t).length;
          if (!made) return null;
          const [ax, ay] = layout.point(e.from);
          const [bx, by] = layout.point(e.to);
          const share = made / e.calls;
          const bytes = e.bytes * share;
          // Logarithmic, for the same reason machine sizes are: what has to
          // survive is that one of these is visibly enormous.
          const weight = 0.012 + 0.085 * (Math.log10(1 + bytes) / Math.log10(1 + heaviest));
          const dim = !!hovered && hovered !== e.from && hovered !== e.to;
          const [x1, y1, x2, y2] = shrink(ax, -ay, bx, -by, 0.42);
          const mx = (x1 + x2) / 2;
          // Bowed so parallel edges are distinguishable, and **capped**, because
          // a bow proportional to the span sent the longest edge — master to the
          // far reducer — arcing clean out of the frame, taking its label with it.
          const bow = Math.min(0.45, Math.abs(x2 - x1) * 0.1) + 0.06;
          const my = Math.max(minY + 0.3, (y1 + y2) / 2 - bow);
          const says = [
            `${e.from} to ${e.to}`,
            [...e.methods].join(', '),
            `${made} calls, ${fmt(bytes)}`,
            e.crossZone ? 'crossed a zone: billed, and slower' : '',
            e.failed ? `${e.failed} failed` : '',
          ]
            .filter(Boolean)
            .join(' — ');
          return (
            <g key={i} opacity={dim ? 0.12 : 1}>
              <path
                d={`M${x1} ${y1} Q${mx} ${my} ${x2} ${y2}`}
                fill="none"
                stroke={e.failed ? D.ALARM : e.crossZone ? '#8FA6BC' : theme.dataEdge}
                strokeWidth={weight}
                strokeLinecap="round"
                opacity={e.crossZone ? 0.95 : 0.6}
                markerEnd="url(#tip)"
              >
                <title>{says}</title>
              </path>
              {!dim && bytes > heaviest / 12 && (
                <text
                  x={mx}
                  y={my - 0.04}
                  fontSize={0.1}
                  textAnchor="middle"
                  fill={theme.pencil}
                  stroke={theme.surface}
                  strokeWidth={0.05}
                  paintOrder="stroke"
                >
                  {fmt(bytes)}
                </text>
              )}
            </g>
          );
        })}

        {trace.machines.map((m) => {
          const [cx, cy] = layout.point(m.name);
          const [mw, mh] = layout.sizeOf(m.name);
          const on = hovered === m.name;
          return (
            <g
              key={m.name}
              onMouseEnter={() => onHover?.(m.name)}
              onMouseLeave={() => onHover?.(null)}
              style={{ cursor: 'pointer' }}
            >
              <ellipse
                cx={cx}
                cy={-cy}
                rx={mw / 2}
                ry={mh / 2}
                fill={theme.machine}
                stroke={on ? theme.ink : theme.rule}
                strokeWidth={on ? 0.022 : 0.012}
              />
              <text x={cx} y={-cy + 0.045} fontSize={0.14} textAnchor="middle" fill={theme.ink}>
                {m.name}
              </text>
              <text x={cx} y={-cy + 0.2} fontSize={0.085} textAnchor="middle" fill={theme.pencil}>
                {m.serves.join(' · ') || 'no service'}
              </text>
            </g>
          );
        })}

        {layout.columns.map((_, i) => (
          <text
            key={i}
            x={layout.columnCentre(i)}
            y={-layout.columnFloor() + 0.42}
            fontSize={0.11}
            textAnchor="middle"
            fill={theme.pencil}
            letterSpacing="0.05"
          >
            {layout.columnLabel(i).toUpperCase()}
          </text>
        ))}
      </svg>
      <style>{`
        .topo { flex: 1; min-height: 0; padding: 0; overflow: hidden; }
        .topo svg { width: 100%; height: 100%; }
      `}</style>
    </div>
  );
}

/** Edge to edge, so an arrow does not land on top of the thing it points at. */
function shrink(
  ax: number,
  ay: number,
  bx: number,
  by: number,
  r: number,
): [number, number, number, number] {
  const dx = bx - ax;
  const dy = by - ay;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len;
  const uy = dy / len;
  return [ax + ux * r, ay + uy * r, bx - ux * r, by - uy * r];
}

function fmt(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${Math.round(bytes)} B`;
}
