/**
 * do the glyph paths survive the port?
 *
 *   node viewer/checks/glyphs.ts
 *
 * Builds a grid of every glyph at every size and fill level, and diffs it
 * against a **frozen oracle** — string for string. Nothing is rendered and
 * nothing is looked at: identical path data is identical geometry, and a check
 * that compares pictures is a check somebody has to have an opinion about.
 *
 * The oracle is `fixtures/glyphs.json.gz`, frozen output from the Python
 * renderer these glyphs were ported from. The Python renderer is not part of
 * this repository — only its answer is, kept here because a port with nothing
 * to compare against would drift silently and nobody would notice.
 *
 * The grid is chosen for the two things that can actually go wrong. `liquid`'s
 * large-arc flag turns over at exactly half — get it wrong and the fill draws as
 * a lens floating in the middle of the machine, which is what it did the first
 * time it was written — so the fill levels crowd the middle and both ends. And
 * the number formatter has one genuine divergence between the languages, on an
 * exact rounding tie, so the sizes include dyadic rationals that land on one.
 *
 * String equality settles whether the port is *faithful*. Whether the geometry it
 * is faithful to is right is a separate, visual question that this check does
 * not answer — that is a matter of looking at what the viewer actually draws
 * with these paths.
 */
import { gunzipSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as D from '../lib/design.ts';
import * as G from '../lib/glyphs.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');

const SCALES = [D.SIZE_MIN, 0.8, 0.9, 1.0, 1.1, 1.25, D.SIZE_MAX];
const SIZES: [number, number][] = [];
for (const sx of SCALES) {
  for (const sy of SCALES) {
    SIZES.push([round6(D.MACHINE_W * sx), round6(D.MACHINE_H * sy)]);
  }
}
SIZES.push([0.3125, 0.15625], [2.5, 1.25], [0.5, 0.5], [1.0, 1.0], [3.125, 0.625]);

const SHARES = [
  ...Array.from({ length: 11 }, (_, i) => i / 10),
  -0.2,
  0.001,
  0.4999,
  0.5001,
  0.999,
  1.2,
];

/** Python's `round(x, 6)`; both sides round the same way before formatting. */
function round6(x: number): number {
  return Number(x.toFixed(6));
}

/** Python's `repr` of a float, for the grid keys only — 1 prints as "1.0". */
function num(x: number): string {
  if (Object.is(x, -0)) return '-0.0';
  return Number.isInteger(x) ? `${x}.0` : String(x);
}

/**
 * JSON with object keys in a fixed order, so the diff can only report geometry.
 * Python's `sort_keys` and JavaScript's insertion order disagree about how to
 * write down a pair of paths, and that disagreement is not a fact about shapes.
 */
function canonical(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v) ?? 'undefined';
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  const o = v as Record<string, unknown>;
  return `{${Object.keys(o)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonical(o[k])}`)
    .join(',')}}`;
}

function dump(): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [w, h] of SIZES) {
    const key = `${num(w)}x${num(h)}`;
    out[`ellipse ${key}`] = G.ellipse(w, h);
    out[`overflow ${key}`] = G.overflow(w, h);
    out[`struck ${key}`] = G.struck(w, h);
    out[`struck-gap ${key}`] = G.struck(w, h, 0.25);
    const [bars, weight] = G.hatch(w, h);
    out[`hatch ${key}`] = { bars, weight };
    const [sheet, corner] = G.document(w, h);
    out[`document ${key}`] = { sheet, corner };
    out[`rule-lines ${key}`] = G.ruleLines(w, h, 6);
    out[`block-arrow ${key}`] = G.blockArrow(w, h);
    for (const share of SHARES) {
      out[`liquid ${key} @${num(share)}`] = G.liquid(w, h, share);
    }
  }
  return out;
}

// ------------------------------------------------------------------ the check

const mine = dump();
const theirs = JSON.parse(
  gunzipSync(readFileSync(join(HERE, 'fixtures', 'glyphs.json.gz'))).toString('utf8'),
);

const keys = new Set([...Object.keys(mine), ...Object.keys(theirs)]);
const bad: string[] = [];
for (const k of [...keys].sort()) {
  const a = canonical(mine[k]);
  const b = canonical(theirs[k]);
  if (a !== b) bad.push(`  ${k}\n    was ${b}\n    now ${a}`);
}

console.log(`S1  ${keys.size} glyphs over ${SIZES.length} sizes`);
if (bad.length) {
  console.log(`\n${bad.length} differ:\n${bad.slice(0, 20).join('\n')}`);
  if (bad.length > 20) console.log(`  ... and ${bad.length - 20} more`);
  process.exitCode = 1;
} else {
  console.log('    every path string identical — the port is faithful');
}
