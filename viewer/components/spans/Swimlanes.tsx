'use client';

/**
 * Place — the same tree, arranged by *where* instead of by who called whom.
 *
 * One lane per machine, grouped by zone, spans on their own machine's lane, and
 * arrows for the causal jumps between lanes. It answers two questions the
 * waterfall cannot: **what was this machine doing at 2,400**, and **when did the
 * work move** — and it makes a fleet's idle stretches into visible gaps, which
 * is the shape of a badly balanced job.
 *
 * A cross-zone hop is a visibly longer arrow, because the lanes are ordered by
 * zone. That is the same fact the film draws as a fatter wire and the bill
 * charges for; three views of one thing is the point.
 */
import { Fragment } from 'react';

import { SpanBar } from './SpanBar.tsx';
import type { Node, SpanTree } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import type { Trace } from '../../lib/trace.ts';

const LANE = 26;
/** A band above each zone's first lane, so its name is not written over a machine. */
const ZONE_GAP = 17;

export function Swimlanes({
  tree,
  trace,
  x,
  width,
  height,
  theme,
  critical,
  selected,
  onSelect,
  hovered,
  onHoverMachine,
  t,
  onSeek,
}: {
  tree: SpanTree;
  trace: Trace;
  x: (t: number) => number;
  width: number;
  height: number;
  theme: Theme;
  critical: ReadonlySet<number>;
  selected: number | null;
  onSelect: (id: number | null) => void;
  hovered: string | null;
  onHoverMachine: (m: string | null) => void;
  t: number;
  onSeek: (t: number) => void;
}) {
  const lanes = tree.lanes();
  // Zone first, so a hop between zones is a long arrow and a hop inside one is
  // short. The order is the trace's own, which is the film's order too.
  const order = [...trace.machines].sort((a, b) =>
    a.zone === b.zone ? 0 : a.zone < b.zone ? -1 : 1,
  );
  // Laid out with a band before each zone rather than by row index, because the
  // zone's name has to go somewhere and writing it above the first lane wrote it
  // across the machine in the lane above.
  const top = new Map<string, number>();
  const heads: { zone: string; y: number }[] = [];
  let y = 0;
  order.forEach((m, i) => {
    if (i === 0 || order[i - 1].zone !== m.zone) {
      y += ZONE_GAP;
      heads.push({ zone: m.zone, y });
    }
    top.set(m.name, y);
    y += LANE;
  });
  const h = y;

  // The jumps: every call that left one machine for another, as an arrow from
  // the caller's lane to the callee's at the instant it was made.
  const hops: { from: number; to: number; at: number; cross: boolean; id: number }[] = [];
  for (const n of tree.flat) {
    if (n.span.kind !== 'rpc' || !n.to) continue;
    const a = top.get(n.span.vm);
    const b = top.get(n.to);
    if (a === undefined || b === undefined || a === b) continue;
    hops.push({ from: a, to: b, at: n.t0, cross: n.crossZone, id: n.id });
  }

  return (
    <div className="lanes-box" style={{ height: '100%' }}>
      <svg width={width} height={Math.max(h, height)}>
        <defs>
          <pattern id="fray" width="4" height="4" patternUnits="userSpaceOnUse">
            <path d="M0 4 L4 0" stroke={theme.pencil} strokeWidth="1" />
          </pattern>
          <marker id="hop" markerWidth="5" markerHeight="5" refX="4" refY="2.5" orient="auto">
            <path d="M0 0 L5 2.5 L0 5 z" fill={theme.pencil} />
          </marker>
        </defs>

        {heads.map((z) => (
          <Fragment key={z.zone}>
            <line x1={0} x2={width} y1={z.y - ZONE_GAP + 2} y2={z.y - ZONE_GAP + 2} stroke={theme.rule} strokeWidth={1} />
            <text x={8} y={z.y - 5} fontSize={9.5} fill={theme.pencil} letterSpacing="0.06em">
              {z.zone.toUpperCase()}
            </text>
          </Fragment>
        ))}

        {order.map((m, i) => (
          <Fragment key={m.name}>
            <rect
              x={0}
              y={top.get(m.name)}
              width={width}
              height={LANE}
              fill={hovered === m.name ? theme.faint : i % 2 ? 'transparent' : theme.surface}
              onMouseEnter={() => onHoverMachine(m.name)}
              onMouseLeave={() => onHoverMachine(null)}
            />
            <text x={12} y={(top.get(m.name) ?? 0) + LANE / 2 + 4} fontSize={11.5} fill={theme.ink}>
              {m.name}
            </text>
          </Fragment>
        ))}

        {/* Where the work moved. Drawn under the bars, so it explains them
            rather than covering them. */}
        {hops.map((hp, i) => (
          <line
            key={i}
            x1={x(hp.at)}
            x2={x(hp.at)}
            y1={hp.from + LANE / 2}
            y2={hp.to + (hp.to > hp.from ? 4 : LANE - 4)}
            stroke={hp.cross ? '#8FA6BC' : theme.rule}
            strokeWidth={hp.cross ? 1.4 : 0.8}
            markerEnd="url(#hop)"
            opacity={selected === null || selected === hp.id ? 0.6 : 0.15}
          />
        ))}

        {order.map((m) =>
          (lanes.get(m.name) ?? []).map((n: Node) => (
            <g
              key={n.id}
              onClick={() => {
                onSelect(selected === n.id ? null : n.id);
                onSeek(n.t0);
              }}
              onMouseEnter={() => onHoverMachine(m.name)}
              style={{ cursor: 'pointer' }}
            >
              <SpanBar
                n={n}
                x={x}
                y={(top.get(m.name) ?? 0) + 6}
                h={LANE - 12}
                theme={theme}
                critical={critical.has(n.id)}
                dim={selected !== null && selected !== n.id}
              />
            </g>
          )),
        )}

        <line x1={x(t)} x2={x(t)} y1={0} y2={h} stroke={theme.ink} strokeWidth={1} opacity={0.4} />
      </svg>
      <style>{`
        .lanes-box { overflow: auto; }
        .lanes-box text { pointer-events: none; user-select: none; }
      `}</style>
    </div>
  );
}
