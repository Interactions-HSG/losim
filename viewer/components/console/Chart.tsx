'use client';

/**
 * Charts for the chrome — not for the figure.
 *
 * The film is a figure and is drawn in the language of the whiteboard
 * (`lib/design.ts`). These are the other thing: a cost report and a usage
 * console, which are software, and which are allowed to look like software.
 *
 * ## The axis does not move
 *
 * Every chart here takes a `now` and is drawn only up to it, and the whole point
 * of that is undone if the ruler rescales as the drawing fills. So the axis top
 * comes from the **whole run**, never from the visible window, and what lies
 * past the clock is shaded rather than omitted — the frame stays still, the
 * window fills. A chart whose axis moved while you dragged would be a chart you
 * could not read a trend off, which is the only reason to draw one.
 *
 * The ticks are rounded for the same reason. A fixed ruler is only worth having
 * if you can read it: 0 / 25 / 50 / 75 / 100, not 0 / 23.7 / 47.4 / 71.1 / 94.8.
 */

export interface Series {
  name: string;
  color: string;
  /** `[t, v]`, in reference milliseconds. */
  pts: [number, number][];
}

/**
 * The categorical palette, as tokens.
 *
 * Tokens rather than hex, because these are chrome and chrome follows the
 * viewer's theme: the same ten hues that read on white are muddy on near-black,
 * and `globals.css` defines both sets. Every one of them therefore reaches an
 * SVG through `style`, never through a `fill=` or `stroke=` attribute — a
 * presentation attribute is not a CSS declaration, and Safari will not
 * substitute a `var()` inside one.
 */
export const SERIES_COLOURS = [
  'var(--s1)', 'var(--s2)', 'var(--s3)', 'var(--s4)', 'var(--s5)',
  'var(--s6)', 'var(--s7)', 'var(--s8)', 'var(--s9)', 'var(--s10)',
] as const;

export const colourOf = (i: number): string => SERIES_COLOURS[i % SERIES_COLOURS.length];

/* -------------------------------------------------------------- the ruler */

const NICE = [1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];

/** A round axis top at or above `v`, in `divs` even steps. */
export function niceTop(v: number, divs = 4): number {
  if (!(v > 0)) return divs;
  const raw = v / divs;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  return (NICE.find((m) => m * mag >= raw - 1e-12) ?? 10) * mag * divs;
}

/**
 * Every tick on one axis with the same number of decimals — as many as the step
 * needs, and never fewer than two unless the step is a whole number.
 */
export function axisTicks(max: number, divs: number): string[] {
  const step = max / divs;
  let dp = 4;
  for (let d = 0; d <= 4; d++) {
    if (Math.abs(step * 10 ** d - Math.round(step * 10 ** d)) < 1e-9) {
      dp = d;
      break;
    }
  }
  if (dp > 0) dp = Math.max(2, dp);
  return Array.from({ length: divs + 1 }, (_, i) => (step * i).toFixed(dp));
}

/** Round to something a person would write, for a legend or a table. */
export function short(v: number): string {
  if (v === 0) return '0';
  const a = Math.abs(v);
  if (a >= 1000) return v.toFixed(0);
  if (a >= 100) return v.toFixed(1);
  if (a >= 1) return v.toFixed(2);
  return v.toFixed(4);
}

/* ---------------------------------------------------------------- the charts */

export function LineChart({
  series,
  duration,
  now,
  yMax,
  height = 190,
  divs = 4,
  unit = '',
  area = false,
  label,
}: {
  series: Series[];
  duration: number;
  now: number;
  /** The whole run's maximum. Never the visible window's. */
  yMax: number;
  height?: number;
  divs?: number;
  unit?: string;
  area?: boolean;
  label?: string;
}) {
  const W = 640;
  const P = { l: 54, r: 12, t: 12, b: 24 };
  const iw = W - P.l - P.r;
  const ih = height - P.t - P.b;
  const max = niceTop(Math.max(yMax, 1e-9), divs);
  const ticks = axisTicks(max, divs);
  const X = (t: number) => P.l + (t / Math.max(duration, 1)) * iw;
  const Y = (v: number) => P.t + ih - (v / max) * ih;
  const cursor = X(Math.min(now, duration));

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${height}`} role="img"
         aria-label={`${label ?? series.map((s) => s.name).join(', ')}${unit ? ` in ${unit}` : ''}, drawn to ${Math.round(now)} reference milliseconds`}>
      {ticks.map((tick, i) => {
        const y = P.t + ih - (i / divs) * ih;
        return (
          <g key={tick + i}>
            <line className="grid" x1={P.l} y1={y} x2={W - P.r} y2={y} />
            <text className="tick" x={P.l - 8} y={y + 3.5} textAnchor="end">{tick}</text>
          </g>
        );
      })}
      {[0, 1, 2, 3, 4].map((i) => {
        const t = (duration * i) / 4;
        return (
          <text key={i} className="tick" x={X(t)} y={height - 7} textAnchor="middle">
            {Math.round(t)}
          </text>
        );
      })}

      {series.map((s) => {
        if (!s.pts.length) return null;
        const d = s.pts
          .map((p, j) => `${j ? 'L' : 'M'}${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`)
          .join('');
        const last = s.pts[s.pts.length - 1];
        return (
          <g key={s.name}>
            {area && (
              <path
                className="area"
                style={{ fill: s.color }}
                d={`${d}L${X(last[0]).toFixed(1)},${Y(0)}L${P.l},${Y(0)}Z`}
              />
            )}
            <path className="ln" style={{ stroke: s.color }} d={d} />
            <circle className="dot" style={{ fill: s.color }} cx={X(last[0])} cy={Y(last[1])} r={3} />
          </g>
        );
      })}

      {/* What has not happened yet, shaded rather than cropped: the frame is the
          whole run, and the window fills into it. */}
      {now < duration - 0.5 && (
        <>
          <rect className="future" x={cursor} y={P.t} width={W - P.r - cursor} height={ih} />
          <line className="cursor" x1={cursor} y1={P.t} x2={cursor} y2={P.t + ih} />
        </>
      )}
    </svg>
  );
}

export interface Bar {
  label: string;
  sub?: string;
  parts: Record<string, number>;
  /** Drawn with a ring around it — the run you have open. */
  here?: boolean;
}

export function StackedBars({
  bars,
  keys,
  colour,
  yMax,
  height = 250,
  currency = '',
}: {
  bars: Bar[];
  keys: string[];
  colour: (k: string) => string;
  yMax: number;
  height?: number;
  currency?: string;
}) {
  const W = 640;
  const P = { l: 58, r: 12, t: 12, b: 46 };
  const iw = W - P.l - P.r;
  const ih = height - P.t - P.b;
  const max = niceTop(Math.max(yMax, 1e-9), 4);
  const ticks = axisTicks(max, 4);
  const slot = iw / Math.max(bars.length, 1);
  const bw = Math.min(72, slot * 0.6);

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${height}`} role="img"
         aria-label={`cost by ${keys.join(', ')}${currency ? ` in ${currency}` : ''}`}>
      {ticks.map((tick, i) => {
        const y = P.t + ih - (i / 4) * ih;
        return (
          <g key={tick + i}>
            <line className="grid" x1={P.l} y1={y} x2={W - P.r} y2={y} />
            <text className="tick" x={P.l - 8} y={y + 3.5} textAnchor="end">{tick}</text>
          </g>
        );
      })}
      {bars.map((c, i) => {
        const cx = P.l + (i + 0.5) * slot;
        const total = keys.reduce((a, k) => a + (c.parts[k] ?? 0), 0);
        let acc = 0;
        return (
          <g key={c.label + i}>
            {keys.map((k) => {
              const v = c.parts[k] ?? 0;
              if (v <= 0) return null;
              const y = P.t + ih - ((acc + v) / max) * ih;
              const h = Math.max(1, (v / max) * ih);
              acc += v;
              return (
                <rect key={k} x={cx - bw / 2} y={y} width={bw} height={h} style={{ fill: colour(k) }}>
                  <title>{`${k} ${short(v)}`}</title>
                </rect>
              );
            })}
            {c.here && (
              <rect
                className="here"
                x={cx - bw / 2 - 3}
                y={P.t + ih - (total / max) * ih - 3}
                width={bw + 6}
                height={(total / max) * ih + 6}
                rx={4}
              />
            )}
            <text className="bl" x={cx} y={height - 26} textAnchor="middle">{c.label}</text>
            {c.sub && (
              <text className="tick" x={cx} y={height - 15} textAnchor="middle">{c.sub}</text>
            )}
            <text className="total" x={cx} y={height - 3} textAnchor="middle">{short(total)}</text>
          </g>
        );
      })}
    </svg>
  );
}

export function Donut({
  parts,
  colour,
  middle,
  sub,
}: {
  parts: Record<string, number>;
  colour: (k: string) => string;
  middle: string;
  sub?: string;
}) {
  const R = 62;
  const r = 40;
  const C = 76;
  const total = Object.values(parts).reduce((a, b) => a + b, 0) || 1;
  let a0 = -Math.PI / 2;
  const arcs: { k: string; d: string }[] = [];
  for (const [k, v] of Object.entries(parts)) {
    if (v <= 0) continue;
    const a1 = a0 + (v / total) * Math.PI * 2;
    const big = a1 - a0 > Math.PI ? 1 : 0;
    const p = (rad: number, a: number) =>
      `${(C + rad * Math.cos(a)).toFixed(2)},${(C + rad * Math.sin(a)).toFixed(2)}`;
    arcs.push({
      k,
      d: `M${p(R, a0)}A${R},${R} 0 ${big} 1 ${p(R, a1)}L${p(r, a1)}A${r},${r} 0 ${big} 0 ${p(r, a0)}Z`,
    });
    a0 = a1;
  }
  return (
    <svg viewBox="0 0 152 152" style={{ width: 152, height: 152 }} role="img" aria-label={middle}>
      {arcs.map(({ k, d }) => (
        <path key={k} style={{ fill: colour(k) }} d={d}>
          <title>{`${k} ${short(parts[k])}`}</title>
        </path>
      ))}
      <text className="mid" x={C} y={sub ? C + 1 : C + 5} textAnchor="middle">{middle}</text>
      {sub && <text className="tick" x={C} y={C + 16} textAnchor="middle">{sub}</text>}
    </svg>
  );
}

export function Legend({ keys, colour }: { keys: string[]; colour: (k: string) => string }) {
  return (
    <div className="legend">
      {keys.map((k) => (
        <span key={k}>
          <i style={{ background: colour(k) }} />
          {k}
        </span>
      ))}
    </div>
  );
}

/** A shape, not a number — for a table cell that has to be read at a glance. */
export function Spark({ pts, colour, max }: { pts: [number, number][]; colour: string; max: number }) {
  if (!pts.length) return <svg className="spark" viewBox="0 0 100 24" />;
  const span = Math.max(pts[pts.length - 1][0], 1);
  const top = Math.max(max, 1e-9);
  const d = pts
    .map((p, i) => `${i ? 'L' : 'M'}${((p[0] / span) * 100).toFixed(1)},${(23 - (p[1] / top) * 22).toFixed(1)}`)
    .join('');
  return (
    <svg className="spark" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden>
      <path
        d={d}
        style={{ fill: 'none', stroke: colour }}
        strokeWidth={1.6}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}
