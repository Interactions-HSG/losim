'use client';

/**
 * What the simulation is made of.
 *
 * Total and self time gathered by method, by node, by zone or by task —
 * "where does this design actually spend itself", answered in one screen, which
 * is the question a design argument turns on.
 *
 * **Self time is the column that decides things.** Total time double-counts:
 * every second one node spends waiting is also a second some other node spends
 * working, so the totals add up to far more than the simulation and the largest
 * one is always the root. Self time is the whole of it, divided up exactly once.
 */
import type { Rollup as Row, SpanTree, SpanNode } from '../../lib/spans.ts';
import { ms } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import { taskColour } from '../../lib/theme.ts';
import { RUN, type Trace } from '../../lib/trace.ts';
import { Table, Td, Th } from '../../lib/text.tsx';
import * as stylex from '@stylexjs/stylex';

import { chrome } from '../../lib/tokens.stylex.ts';
import { ui } from '../../lib/ui.stylex.ts';
import { rollupVars } from './rollup.stylex.ts';

export type By = 'method' | 'node' | 'zone' | 'task';

export const BYS: By[] = ['method', 'node', 'zone', 'task'];

export function Rollup({
  tree,
  trace,
  by,
  theme,
  height,
  onHoverNode,
}: {
  tree: SpanTree;
  trace: Trace;
  by: By;
  theme: Theme;
  height: number;
  onHoverNode: (m: string | null) => void;
}) {
  const zoneOf = new Map(trace.nodes.map((m) => [m.name, m.zone]));
  const key = (n: SpanNode): string | null => {
    // Only leaves of the *call* structure carry work; a phase is a bracket over
    // other people's time and would otherwise appear as the busiest thing here.
    if (n.span.label === RUN) return null;
    switch (by) {
      case 'method':
        return n.method;
      case 'node':
        return n.span.vm;
      case 'zone':
        return zoneOf.get(n.span.vm) ?? 'Unknown zone';
      case 'task':
        return n.task === null ? 'No task' : `Task ${n.task}`;
    }
  };
  const rows = tree.rollup(key);
  const most = Math.max(1e-9, ...rows.map((r) => r.self));
  const all = rows.reduce((a, r) => a + r.self, 0);

  return (
    <div {...stylex.props(sx.rollup)}>
      <Table style={sx.table}>
        <thead>
          <tr>
            <Th>{by[0].toUpperCase() + by.slice(1)}</Th>
            <Th style={sx.right}>Self time</Th>
            <Th style={sx.right}>Share</Th>
            <Th></Th>
            <Th style={sx.right}>Total time</Th>
            <Th style={sx.right}>Calls</Th>
            <Th style={sx.right}>Failed</Th>
            <Th style={sx.right}>Bytes</Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r: Row) => (
            <tr
              key={r.key}
              {...stylex.props(sx.row)}
              onMouseEnter={() => by === 'node' && onHoverNode(r.key)}
              onMouseLeave={() => by === 'node' && onHoverNode(null)}
            >
              <Td style={sx.cell}>
                {by === 'task' && r.key !== 'No task' && (
                  <span
                    {...stylex.props(sx.tk)}
                    style={{ background: taskColour(theme, Number(r.key.replace('Task ', ''))) }}
                  />
                )}
                {r.key}
              </Td>
              <Td num style={[sx.right, sx.cell]}>{ms(r.self)}</Td>
              <Td num style={[sx.right, sx.cell, ui.muted, sx.pct]}>{((r.self / (all || 1)) * 100).toFixed(1)}%</Td>
              <Td style={[sx.cell, sx.barCell]}>
                <span {...stylex.props(sx.bar)} style={{ width: `${(r.self / most) * 100}%` }} />
              </Td>
              <Td num style={[sx.right, sx.cell, ui.muted]}>{ms(r.total)}</Td>
              <Td num style={[sx.right, sx.cell]}>{r.calls}</Td>
              <Td num style={[sx.right, sx.cell]}>{r.failed > 0 ? <b {...stylex.props(sx.bad)}>{r.failed}</b> : <span {...stylex.props(ui.muted)}>-</span>}</Td>
              <Td num style={[sx.right, sx.cell, ui.muted]}>{r.bytes ? `${(r.bytes / 1024).toFixed(1)} KB` : '-'}</Td>
            </tr>
          ))}
        </tbody>
      </Table>
    </div>
  );
}

const sx = stylex.create({
  rollup: { overflow: 'auto', position: 'relative', height: '100%' },
  table: { fontSize: '12.5px' },
  right: { textAlign: 'right' },
  /** Tighter than the shared cell: this table is dense on purpose. */
  cell: {
    paddingBlock: '4px',
    paddingInline: '8px',
    backgroundColor: rollupVars.cellBg,
  },
  row: { [rollupVars.cellBg]: { default: 'transparent', ':hover': chrome.surface2 } },
  pct: { width: '52px' },
  barCell: { width: '26%' },
  bar: {
    display: 'block',
    height: '8px',
    borderRadius: '999px',
    backgroundColor: chrome.accent,
    opacity: 0.75,
    minWidth: '1px',
  },
  tk: {
    width: '7px',
    height: '7px',
    borderRadius: '50%',
    display: 'inline-block',
    marginRight: '6px',
  },
  bad: { color: chrome.danger },
});
