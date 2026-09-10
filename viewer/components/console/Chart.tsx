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

import * as stylex from '@stylexjs/stylex';

import { chrome, font, series } from '../../lib/tokens.stylex.ts';
import { groupText } from '../../lib/trace.ts';

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
 * and `lib/tokens.stylex.ts` declares both sets together. Every one of them
 * reaches an SVG through `style`, never through a `fill=` or `stroke=`
 * attribute — a presentation attribute is not a CSS declaration, and Safari
 * will not substitute a `var()` inside one.
 */
export const SERIES_COLOURS = [
  series.s1, series.s2, series.s3, series.s4, series.s5,
  series.s6, series.s7, series.s8, series.s9, series.s10,
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
  const places = a >= 1000 ? 0 : a >= 100 ? 1 : a >= 1 ? 2 : 4;
  const [whole, frac] = v.toFixed(places).split('.');
  // Grouped above a thousand for the same reason every other number in the
  // viewer is: 48000 read at a glance is 4800 as often as it is 48,000.
  return frac ? `${groupText(whole)}.${frac}` : groupText(whole);
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
  per = 1,
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
  /**
   * How many of the data's own units go into one of `unit`.
   *
   * The trace records sizes in megabytes and a chart of a scaled run is drawn in
   * gigabytes, so the axis is divided once, here, and the ticks come out round
   * in the unit they are labelled with. A rescale of one axis, not a second
   * opinion about a value: `per` never changes which number is plotted, only
   * which unit the ruler beside it is written in.
   */
  per?: number;
  area?: boolean;
  label?: string;
}) {
  const W = 640;
  // Room above the plot for the unit, where there is one. Nothing is drawn up
  // there otherwise, and a chart with a fixed head would sit a percentage's
  // grid line lower than an identical chart beside it.
  const P = { l: 54, r: 12, t: unit ? 22 : 12, b: 24 };
  const iw = W - P.l - P.r;
  const ih = height - P.t - P.b;
  const max = niceTop(Math.max(yMax / per, 1e-9), divs);
  const ticks = axisTicks(max, divs);
  const X = (t: number) => P.l + (t / Math.max(duration, 1)) * iw;
  const Y = (v: number) => P.t + ih - (v / per / max) * ih;
  const cursor = X(Math.min(now, duration));

  return (
    <svg {...stylex.props(styles.chart)} viewBox={`0 0 ${W} ${height}`} role="img"
         aria-label={`${label ?? series.map((s) => s.name).join(', ')}${unit ? `; values in ${unit}` : ''}; data through ${Math.round(now)} reference milliseconds`}>
      {ticks.map((tick, i) => {
        const y = P.t + ih - (i / divs) * ih;
        return (
          <g key={tick + i}>
            <line {...stylex.props(styles.grid)} x1={P.l} y1={y} x2={W - P.r} y2={y} />
            <text
              {...stylex.props(styles.tick)}
              // The ruler, and checks/console.ts finds it by this. A class
              // name would not survive StyleX, which generates its own.
              data-ruler=""
              x={P.l - 8}
              y={y + 3.5}
              textAnchor="end"
            >
              {tick}
            </text>
          </g>
        );
      })}
      {/* What the numbers up the side are counted in. It reached only the
          `aria-label` for a release, so the answer to "0.04 of what?" was
          available to a screen reader and to nobody else. Deliberately without
          `data-ruler`: it is a heading for the ruler, not a mark on it. */}
      {unit && (
        <text {...stylex.props(styles.axisUnit)} x={P.l - 8} y={P.t - 8} textAnchor="end">
          {unit}
        </text>
      )}
      {[0, 1, 2, 3, 4].map((i) => {
        const t = (duration * i) / 4;
        return (
          <text key={i} {...stylex.props(styles.tick)} x={X(t)} y={height - 7} textAnchor="middle">
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
                {...stylex.props(styles.area)}
                style={{ fill: s.color }}
                d={`${d}L${X(last[0]).toFixed(1)},${Y(0)}L${P.l},${Y(0)}Z`}
              />
            )}
            <path {...stylex.props(styles.ln)} style={{ stroke: s.color }} d={d} />
            <circle {...stylex.props(styles.dot)} style={{ fill: s.color }} cx={X(last[0])} cy={Y(last[1])} r={3} />
          </g>
        );
      })}

      {/* What has not happened yet, shaded rather than cropped: the frame is the
          whole run, and the window fills into it. */}
      {now < duration - 0.5 && (
        <>
          <rect {...stylex.props(styles.future)} x={cursor} y={P.t} width={W - P.r - cursor} height={ih} />
          <line {...stylex.props(styles.cursor)} x1={cursor} y1={P.t} x2={cursor} y2={P.t + ih} />
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
    <svg {...stylex.props(styles.chart)} viewBox={`0 0 ${W} ${height}`} role="img"
         aria-label={`Cost by ${keys.join(', ')}${currency ? `; ${currency}` : ''}`}>
      {ticks.map((tick, i) => {
        const y = P.t + ih - (i / 4) * ih;
        return (
          <g key={tick + i}>
            <line {...stylex.props(styles.grid)} x1={P.l} y1={y} x2={W - P.r} y2={y} />
            <text
              {...stylex.props(styles.tick)}
              // The ruler, and checks/console.ts finds it by this. A class
              // name would not survive StyleX, which generates its own.
              data-ruler=""
              x={P.l - 8}
              y={y + 3.5}
              textAnchor="end"
            >
              {tick}
            </text>
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
                {...stylex.props(styles.here)}
                x={cx - bw / 2 - 3}
                y={P.t + ih - (total / max) * ih - 3}
                width={bw + 6}
                height={(total / max) * ih + 6}
                rx={4}
              />
            )}
            <text {...stylex.props(styles.bl)} x={cx} y={height - 26} textAnchor="middle">{c.label}</text>
            {c.sub && (
              <text {...stylex.props(styles.tick)} x={cx} y={height - 15} textAnchor="middle">{c.sub}</text>
            )}
            <text {...stylex.props(styles.total)} x={cx} y={height - 3} textAnchor="middle">{short(total)}</text>
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
      <text {...stylex.props(styles.mid)} x={C} y={sub ? C + 1 : C + 5} textAnchor="middle">{middle}</text>
      {sub && <text {...stylex.props(styles.tick)} x={C} y={C + 16} textAnchor="middle">{sub}</text>}
    </svg>
  );
}

export function Legend({ keys, colour }: { keys: string[]; colour: (k: string) => string }) {
  return (
    <div {...stylex.props(styles.legend)}>
      {keys.map((k) => (
        <span key={k} {...stylex.props(styles.item)}>
          <i {...stylex.props(styles.swatch)} style={{ background: colour(k) }} />
          {k}
        </span>
      ))}
    </div>
  );
}

/** A shape, not a number — for a table cell that has to be read at a glance. */
export function Spark({ pts, colour, max }: { pts: [number, number][]; colour: string; max: number }) {
  if (!pts.length) return <svg {...stylex.props(styles.spark)} viewBox="0 0 100 24" />;
  const span = Math.max(pts[pts.length - 1][0], 1);
  const top = Math.max(max, 1e-9);
  const d = pts
    .map((p, i) => `${i ? 'L' : 'M'}${((p[0] / span) * 100).toFixed(1)},${(23 - (p[1] / top) * 22).toFixed(1)}`)
    .join('');
  return (
    <svg {...stylex.props(styles.spark)} viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden>
      <path
        d={d}
        style={{ fill: 'none', stroke: colour }}
        strokeWidth={1.6}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}

/**
 * The console's charts, drawn in the chrome's language rather than the figure's.
 *
 * These were `.chart .grid`, `.chart .tick` and so on — a parent styling the SVG
 * elements inside it by name. Every one of those children is rendered in this
 * file, so each carries its own style now and nothing selects at a distance.
 */
const styles = stylex.create({
  chart: { display: 'block', width: '100%', height: 'auto' },
  grid: { stroke: chrome.border, strokeWidth: 1 },
  tick: {
    fill: chrome.text3,
    font: `400 10.5px ${font.mono}`,
    fontVariantNumeric: 'tabular-nums',
  },
  /** The unit at the head of the y axis: the same ink as a tick, said once. */
  axisUnit: { fill: chrome.text3, font: `500 10.5px ${font.sans}` },
  bl: { fill: chrome.text2, font: `400 11px ${font.sans}` },
  total: { fill: chrome.text, font: `500 11.5px ${font.sans}`, fontVariantNumeric: 'tabular-nums' },
  ln: { fill: 'none', strokeWidth: 2, strokeLinejoin: 'round', strokeLinecap: 'round' },
  area: { opacity: 0.14 },
  dot: { stroke: chrome.surface, strokeWidth: 1.5 },
  /** The part of the run that has not happened yet: shaded, never cropped. */
  future: { fill: chrome.text, opacity: 0.05 },
  cursor: { stroke: chrome.danger, strokeWidth: 1, strokeDasharray: '3 3', opacity: 0.75 },
  here: { fill: 'none', stroke: chrome.accent, strokeWidth: 1.5 },
  mid: { fill: chrome.text, font: `500 15px ${font.sans}`, fontVariantNumeric: 'tabular-nums' },

  legend: { display: 'flex', flexWrap: 'wrap', gap: '6px 18px', fontSize: '12px', color: chrome.text2 },
  item: { display: 'flex', alignItems: 'center', gap: '7px' },
  swatch: { width: '10px', height: '10px', borderRadius: '2px', flex: 'none' },

  /** A shape, not a number — for a table cell read at a glance. */
  spark: { display: 'block', width: '100%', height: '24px' },
});
