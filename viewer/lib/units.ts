/**
 * The unit a number is written in.
 *
 * DISSALy records every size in megabytes and every duration in reference
 * milliseconds, and it is right to: one unit throughout is what makes a law for
 * disk comparable with a law for wire, and what stops a projection meaning
 * something different from the measurement it came from.
 *
 * It is the wrong unit to *read*. A scaled run that moves 1,240 MB across the
 * wire has moved more than a gigabyte, and a usage page that renders it as
 * `0.04` with nothing after it is not showing a quantity at all — that was the
 * first question anybody asked of this page, and both halves of it are answered
 * here.
 *
 * **Rescaling is not computing.** Nothing in this file produces a value the
 * trace does not already hold: `1270 MB` and `1.24 GB` are one number said
 * twice, exactly as `refTime` says 90,000 as `1:30`. Every simulated value comes
 * from the engine (D9) — anything that would change *which* number appears on
 * the screen belongs in `dissaly.scale`, not here.
 */

/** A unit, and how many of the trace's own units go into one of it. */
export interface Unit {
  unit: string;
  per: number;
}

export const MB: Unit = { unit: 'MB', per: 1 };

const SIZES: Unit[] = [
  MB,
  { unit: 'GB', per: 1024 },
  { unit: 'TB', per: 1024 * 1024 },
  { unit: 'PB', per: 1024 * 1024 * 1024 },
];

/**
 * The unit a quantity of this size is written in.
 *
 * Chosen from the **top of the axis**, not from each value, so that one chart is
 * drawn in one unit and its ticks can be compared with each other. A ruler whose
 * labels changed unit partway up is not a ruler.
 */
export function sizeUnit(topMb: number): Unit {
  const top = Math.abs(topMb);
  for (let i = SIZES.length - 1; i > 0; i--) if (top >= SIZES[i].per) return SIZES[i];
  return MB;
}

/**
 * A reading in the unit a person would write it in.
 *
 * `dp` is what the quantity is worth reading to *in its own unit*. Once a value
 * has been rescaled it sits between 1 and 1024 by construction, so it is worth
 * the same two decimals whatever it started as.
 */
export function size(mb: number, dp = 2): string {
  const u = sizeUnit(mb);
  return `${(mb / u.per).toFixed(u.per === 1 ? dp : 2)} ${u.unit}`;
}

/** A count with its unit attached the way that unit is written. */
export function reading(v: number, unit: string, dp: number): string {
  const n = v.toFixed(dp);
  if (unit === '%') return `${n}%`;
  return unit ? `${n} ${unit}` : n;
}

/**
 * A multiplicative error bar, as the engine states it.
 *
 * The band is `value / x` to `value * x`, so `1` is no band at all rather than a
 * band of one — and printing `±x1.00` beside every exact number would be noise
 * that trains people to stop reading the ones that are not.
 */
export function band(errorBar: number): string | null {
  if (!(errorBar > 1.0005)) return null;
  return `×÷${errorBar.toFixed(2)}`;
}
