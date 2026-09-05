/**
 * The geometry of the language, as SVG path data.
 *
 * These shapes are authored in SVG's own convention — origin at the glyph's
 * centre, x right, **y down** — matching how a browser draws. Manim, the
 * renderer these glyphs were ported from, is y-up, so feeding it the same path
 * data needs a mirror on y first; feeding a browser does not. The path strings
 * cross verbatim, which is why the port can be checked by string equality
 * rather than by looking at two pictures and arguing about them.
 */
import { DOC_FOLD } from './design.ts';

/**
 * Enough precision to be exact on screen, not enough to be noise in a diff.
 *
 * Written to match Python's `f"{x:.4f}".rstrip("0").rstrip(".")` character for
 * character, because that is what the parity check compares. The two languages
 * disagree in exactly one place: on a value that falls *exactly* halfway at the
 * fourth decimal, Python rounds half to even and JavaScript's `toFixed` rounds
 * towards positive infinity, so 0.15625 formats as "0.1562" in one and "0.1563"
 * in the other.
 *
 * Detecting that tie is the subtle part, and the obvious test is wrong. Asking
 * whether `x * 1e4` lands on a half is asking a question of a product that has
 * itself been rounded: 0.21675 is not representable, the double nearest it sits
 * a little *below* the tie so Python rounds it down — but multiplied by ten
 * thousand it lands on exactly 2167.5 and the naive test calls it a tie. That
 * false positive is worth 1 in the last digit on a fifth of the glyphs, which is
 * exactly the kind of difference that is invisible in a picture.
 *
 * A double is exactly halfway at the fourth decimal only if it is an odd
 * multiple of 1/32 — five fractional bits give exactly five decimal digits, and
 * an odd numerator makes the last of them a 5. Multiplying by 32 is exact, so
 * that test cannot lie.
 */
export function n(x: number): string {
  if (Object.is(x, -0)) return '-0'; // Python keeps the sign; toFixed drops it
  const s = trim(fixed4(x));
  return s;
}

function fixed4(x: number): string {
  const q = x * 32; // exact: a power of two
  if (Number.isInteger(q) && Math.abs(q % 2) === 1) {
    // x * 1e4 is exactly k + 0.5, with k below it. Python takes whichever of the
    // two neighbours is even.
    const k = (q * 625 - 1) / 2;
    return decimal4(k % 2 === 0 ? k : k + 1);
  }
  return x.toFixed(4);
}

/** An integer count of ten-thousandths, written out as a decimal. */
function decimal4(k: number): string {
  const sign = k < 0 ? '-' : '';
  const a = Math.abs(k);
  const whole = Math.floor(a / 1e4);
  const frac = a - whole * 1e4;
  return `${sign}${whole}.${String(frac).padStart(4, '0')}`;
}

/**
 * `rstrip("0")` then `rstrip(".")`, in that order. The dot stops the first
 * strip, which is why "1000.0000" comes out "1000" and not "1".
 */
function trim(s: string): string {
  let end = s.length;
  while (end > 0 && s[end - 1] === '0') end--;
  if (end > 0 && s[end - 1] === '.') end--;
  return s.slice(0, end);
}

/** A machine. Two arcs, because SVG has no ellipse in path data. */
export function ellipse(w: number, h: number): string {
  const rx = w / 2;
  const ry = h / 2;
  return (
    `M ${n(-rx)} 0 ` +
    `A ${n(rx)} ${n(ry)} 0 0 1 ${n(rx)} 0 ` +
    `A ${n(rx)} ${n(ry)} 0 0 1 ${n(-rx)} 0 Z`
  );
}

/**
 * What a machine is holding: the part of its ellipse below the water line.
 *
 * Computed analytically rather than by intersecting two shapes. A boolean op
 * needs a path library the browser does not have, and the closed form is four
 * lines: the chord at the surface, then the lower arc back to where it started.
 */
export function liquid(w: number, h: number, share: number): string {
  const s = Math.max(0.0, Math.min(1.0, share));
  if (s <= 0) return '';
  if (s >= 1.0) return ellipse(w, h);
  const rx = w / 2;
  const ry = h / 2;
  const y = ry - s * h; // y grows downward, so the surface is up here
  const x = rx * Math.sqrt(Math.max(0.0, 1.0 - (y / ry) ** 2));
  // The arc has to take the way round that passes the *bottom* of the ellipse,
  // and which way that is depends on where the surface sits: below the middle
  // it is the short way, above the middle it is the long way. Getting this flag
  // wrong draws a lens floating in the centre rather than a machine half full.
  const large = y < 0 ? 1 : 0;
  return `M ${n(-x)} ${n(y)} L ${n(x)} ${n(y)} A ${n(rx)} ${n(ry)} 0 ${large} 1 ${n(-x)} ${n(y)} Z`;
}

/**
 * Data at rest: a page with its top-left corner turned down.
 *
 * Returns the sheet and the turned corner separately, because the corner is
 * filled with the *paper* colour — it is the underside of the sheet, and a
 * single path could only make it a notch, which reads as damage.
 */
export function document(w: number, h: number, fold?: number): [string, string] {
  const f = fold ?? DOC_FOLD * w;
  const x = w / 2;
  const y = h / 2;
  const sheet =
    `M ${n(-x)} ${n(-y + f)} L ${n(-x + f)} ${n(-y)} ` +
    `L ${n(x)} ${n(-y)} L ${n(x)} ${n(y)} L ${n(-x)} ${n(y)} Z`;
  const corner =
    `M ${n(-x)} ${n(-y + f)} L ${n(-x + f)} ${n(-y + f)} ` + `L ${n(-x + f)} ${n(-y)} Z`;
  return [sheet, corner];
}

/** The ruled lines on a page — enough to read as text, never enough to read. */
export function ruleLines(w: number, h: number, count: number, fold?: number): string[] {
  const f = fold ?? DOC_FOLD * w;
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    const y = -h / 2 + f + 0.14 + i * 0.2;
    if (y > h / 2 - 0.12) break;
    out.push(`M ${n(-w / 2 + 0.14)} ${n(y)} L ${n(w / 2 - 0.16)} ${n(y)}`);
  }
  return out;
}

/** Bulk moving through the system: the corpus in, the answer out. */
export function blockArrow(length: number, thickness: number): string {
  const t = thickness;
  const head = t * 1.05;
  const body = Math.max(0.05, length - head);
  const x0 = -length / 2;
  return (
    `M ${n(x0)} ${n(-t / 2)} L ${n(x0 + body)} ${n(-t / 2)} ` +
    `L ${n(x0 + body)} ${n(-t)} L ${n(x0 + length)} 0 ` +
    `L ${n(x0 + body)} ${n(t)} L ${n(x0 + body)} ${n(t / 2)} ` +
    `L ${n(x0)} ${n(t / 2)} Z`
  );
}

/** Four strokes reaching in from the rim, leaving the name in the middle clear. */
export function struck(w: number, h: number, gap = 0.42): string[] {
  const arm = w * 0.32;
  const rise = h * 0.32;
  const out: string[] = [];
  for (const sx of [-1, 1]) {
    for (const sy of [-1, 1]) {
      out.push(
        `M ${n(sx * arm)} ${n(sy * rise)} ` + `L ${n(sx * arm * gap)} ${n(sy * rise * gap)}`,
      );
    }
  }
  return out;
}

/** More than fits: an arc riding proud of the rim it has exceeded. */
export function overflow(w: number, h: number): string {
  const rx = (w / 2) * 0.92;
  const ry = h * 0.17;
  return (
    `M ${n(-rx)} ${n(-h / 2 - ry * 0.2)} ` +
    `A ${n(rx)} ${n(ry)} 0 0 1 ${n(rx)} ${n(-h / 2 - ry * 0.2)}`
  );
}

/**
 * Diagonal rule clipped to an ellipse: the same work, running slower.
 *
 * Clipped by arithmetic rather than by a boolean operation — each bar is
 * shortened to the chord it would cut, which needs no path library.
 */
export function hatch(
  w: number,
  h: number,
  spacing?: number,
  weight?: number,
): [string[], number] {
  const sp = spacing || 0.115;
  const wt = weight || 0.024;
  const rx = w / 2;
  const ry = h / 2;
  const out: string[] = [];
  let x = -w;
  while (x < w) {
    // A 45-degree bar through (x, 0); find where it leaves the ellipse.
    const pts = chord(rx, ry, x);
    if (pts) {
      const [[x1, y1], [x2, y2]] = pts;
      out.push(`M ${n(x1)} ${n(y1)} L ${n(x2)} ${n(y2)}`);
    }
    x += sp;
  }
  return [out, wt];
}

/** Where the line y = x - offset crosses the ellipse, or null. */
function chord(rx: number, ry: number, offset: number): [[number, number], [number, number]] | null {
  const a = 1 / (rx * rx) + 1 / (ry * ry);
  const b = (-2 * offset) / (ry * ry);
  const c = (offset * offset) / (ry * ry) - 1;
  const disc = b * b - 4 * a * c;
  if (disc <= 0) return null;
  const root = Math.sqrt(disc);
  const x1 = (-b - root) / (2 * a);
  const x2 = (-b + root) / (2 * a);
  return [
    [x1, x1 - offset],
    [x2, x2 - offset],
  ];
}
