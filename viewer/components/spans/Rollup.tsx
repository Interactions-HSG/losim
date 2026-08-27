'use client';

/**
 * What the job is made of.
 *
 * Total and self time gathered by method, by machine, by zone or by task —
 * "where does this job actually spend itself", answered in one screen, which is
 * the question a design argument turns on.
 *
 * **Self time is the column that decides things.** Total time double-counts:
 * every second a coordinator spends waiting is also a second some worker spends
 * working, so the totals add up to far more than the run and the largest one is
 * always the root. Self time is the run, divided up exactly once.
 */
import type { Rollup as Row, SpanTree, Node } from '../../lib/spans.ts';
import { ms } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import { taskColour } from '../../lib/theme.ts';
import type { Trace } from '../../lib/trace.ts';

export type By = 'method' | 'machine' | 'zone' | 'task';

export const BYS: By[] = ['method', 'machine', 'zone', 'task'];

export function Rollup({
  tree,
  trace,
  by,
  theme,
  height,
  onHoverMachine,
}: {
  tree: SpanTree;
  trace: Trace;
  by: By;
  theme: Theme;
  height: number;
  onHoverMachine: (m: string | null) => void;
}) {
  const zoneOf = new Map(trace.machines.map((m) => [m.name, m.zone]));
  const key = (n: Node): string | null => {
    // Only leaves of the *call* structure carry work; a phase is a bracket over
    // other people's time and would otherwise appear as the busiest thing here.
    if (n.span.kind === 'phase' || n.span.kind === 'job') return null;
    switch (by) {
      case 'method':
        return n.method;
      case 'machine':
        return n.span.vm;
      case 'zone':
        return zoneOf.get(n.span.vm) ?? '—';
      case 'task':
        return n.task === null ? 'no task' : `task ${n.task}`;
    }
  };
  const rows = tree.rollup(key);
  const most = Math.max(1e-9, ...rows.map((r) => r.self));
  const all = rows.reduce((a, r) => a + r.self, 0);

  return (
    <div className="rollup" style={{ height: '100%' }}>
      <table>
        <thead>
          <tr>
            <th>{by}</th>
            <th className="r">its own time</th>
            <th className="r">share</th>
            <th></th>
            <th className="r">total</th>
            <th className="r">calls</th>
            <th className="r">failed</th>
            <th className="r">bytes</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r: Row) => (
            <tr
              key={r.key}
              onMouseEnter={() => by === 'machine' && onHoverMachine(r.key)}
              onMouseLeave={() => by === 'machine' && onHoverMachine(null)}
            >
              <td>
                {by === 'task' && r.key !== 'no task' && (
                  <span
                    className="tk"
                    style={{ background: taskColour(theme, Number(r.key.replace('task ', ''))) }}
                  />
                )}
                {r.key}
              </td>
              <td className="r n">{ms(r.self)}</td>
              <td className="r n muted pct">{((r.self / (all || 1)) * 100).toFixed(1)}%</td>
              <td className="bar">
                <span style={{ width: `${(r.self / most) * 100}%` }} />
              </td>
              <td className="r n muted">{ms(r.total)}</td>
              <td className="r n">{r.calls}</td>
              <td className="r n">{r.failed > 0 ? <b>{r.failed}</b> : <span className="muted">—</span>}</td>
              <td className="r n muted">{r.bytes ? `${(r.bytes / 1024).toFixed(1)} KB` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <style>{`
        .rollup { overflow: auto; }
        .rollup table { width: 100%; font-size: 12.5px; }
        .rollup th.r, .rollup td.r { text-align: right; }
        .rollup td { padding: 4px 8px; border-bottom: 1px solid var(--border); }
        .rollup tr:hover td { background: var(--surface-2); }
        .rollup .pct { width: 52px; }
        .rollup .bar { width: 26%; }
        .rollup .bar span {
          display: block; height: 8px; border-radius: 999px; background: var(--accent, #5C8F70);
          opacity: .75; min-width: 1px;
        }
        .rollup .tk {
          width: 7px; height: 7px; border-radius: 50%; display: inline-block; margin-right: 6px;
        }
        .rollup b { color: var(--danger); }
      `}</style>
    </div>
  );
}
