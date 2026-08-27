'use client';

/**
 * One call, drawn as what it was made of.
 *
 * The segments are the point. A single block says "this took 1.5 seconds", which
 * is the least interesting true thing about it; four segments say the wire took
 * 2 ms, a core was not free for 40, the handler worked for 1,450, and the answer
 * took 21 ms to come back — and every one of those is a different thing to go and
 * fix.
 *
 * **Self time is drawn lighter inside the working segment.** A handler that
 * spends its life waiting on four machines below it should not look like a
 * handler that spent its life computing, and "why was this stretch idle" stops
 * being a question you reconstruct: idle is drawn.
 */
import { memo } from 'react';

import type { Node, Part } from '../../lib/spans.ts';
import type { Theme } from '../../lib/theme.ts';
import { taskColour } from '../../lib/theme.ts';

const PART: Record<Part, { fill: string; label: string }> = {
  out: { fill: '#8FA6BC', label: 'on the wire, outbound' },
  queue: { fill: '#E8A33D', label: 'landed, waiting for a core' },
  working: { fill: '', label: 'the handler, working' },
  back: { fill: '#8FA6BC', label: 'the answer, coming back' },
};

export const SpanBar = memo(function SpanBar({
  n,
  x,
  y,
  h,
  theme,
  critical,
  dim,
}: {
  n: Node;
  x: (t: number) => number;
  y: number;
  h: number;
  theme: Theme;
  critical: boolean;
  dim: boolean;
}) {
  const colour = n.task !== null ? taskColour(theme, n.task) : kindColour(n, theme);
  const x0 = x(n.t0);
  const x1 = Math.max(x(n.t1), x0 + 1.5);
  const failed = !n.ok;

  // A phase or a job is a bracket over other things, not a thing itself: drawn
  // as an outline so it frames its children rather than burying them.
  const bracket = n.span.kind === 'phase' || n.span.kind === 'job';

  return (
    <g opacity={dim ? 0.25 : 1}>
      {bracket ? (
        <rect
          x={x0}
          y={y + h * 0.18}
          width={x1 - x0}
          height={h * 0.64}
          rx={2}
          fill={theme.faint}
          stroke={theme.rule}
          strokeWidth={1}
        />
      ) : n.segments.length ? (
        n.segments.map((s, i) => {
          const a = x(s.t0);
          const b = Math.max(x(s.t1), a + (s.part === 'working' ? 1.5 : 0.8));
          const fill = s.part === 'working' ? colour : PART[s.part].fill;
          return (
            <g key={i}>
              <rect x={a} y={y} width={b - a} height={h} rx={1.5} fill={fill} opacity={s.part === 'working' ? 1 : 0.85}>
                <title>{`${PART[s.part].label} — ${(s.t1 - s.t0).toFixed(1)}ms`}</title>
              </rect>
              {/* What the callee was itself waiting on, inside its own block. */}
              {s.part === 'working' && n.children.length > 0 && (
                <rect
                  x={a}
                  y={y + h * 0.28}
                  width={Math.max(0, (b - a) * (1 - selfShare(n)))}
                  height={h * 0.44}
                  fill={theme.surface}
                  opacity={0.5}
                />
              )}
            </g>
          );
        })
      ) : (
        <rect x={x0} y={y} width={x1 - x0} height={h} rx={1.5} fill={colour}>
          <title>{`${n.method} — ${(n.t1 - n.t0).toFixed(1)}ms`}</title>
        </rect>
      )}

      {/* Never closed. A dangling span is a telemetry bug, not a finding (D8
          rule 3), so it is drawn as a frayed edge rather than as a long call. */}
      {n.dangling && (
        <rect x={x1 - 6} y={y} width={6} height={h} fill={`url(#fray)`} />
      )}
      {failed && <rect x={x0} y={y} width={Math.max(2, x1 - x0)} height={h} fill="none" stroke="#C4342A" strokeWidth={1.4} rx={1.5} />}
      {critical && <rect x={x0} y={y - 1.5} width={x1 - x0} height={h + 3} fill="none" stroke={theme.ink} strokeWidth={1} rx={2.5} opacity={0.55} />}
    </g>
  );
});

function selfShare(n: Node): number {
  const span = n.t1 - n.t0;
  return span > 0 ? Math.max(0, Math.min(1, n.selfMs / span)) : 1;
}

function kindColour(n: Node, theme: Theme): string {
  if (n.span.kind === 'compute') return theme.dark ? '#6b7f96' : '#9fb0c2';
  return theme.dark ? '#4a6f5c' : '#8fae9b';
}
