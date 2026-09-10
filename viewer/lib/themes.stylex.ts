/**
 * The University of St.Gallen's colours, as a StyleX theme.
 *
 * `createTheme` is what `defineVars` exists for: it takes a variable set and an
 * override map and returns a style. Put that style on one element and every
 * descendant reads the new values — so this file re-skins the whole console
 * without a single component knowing it happened. It is applied once, on
 * `<body>` in app/layout.tsx.
 *
 * **Chrome only, and that is deliberate.** The figure — nodes, zones, packets,
 * spans — keeps the whiteboard palette in lib/design.ts and the `figure` tokens
 * beside this one. Those colours are load-bearing rather than decorative: amber
 * means a node near its cap and red is the lecturer's pen, and viewer/checks/
 * parity.ts compares committed traces against re-rendered ones, so a film
 * recorded before a rebrand has to be identical to one recorded after it. The
 * ten categorical chart hues are left alone for the same kind of reason: they
 * are chosen to stay apart from each other and off the four bucket colours in
 * both schemes, which a green-derived set would have to be re-proved to do.
 *
 * **Where these values come from.** The greens, the greys, the red and the
 * typefaces are read from the university's own live stylesheet, where they are
 * declared as `--green`, `--primary-dark`, `--gray-dark`, `--gray`, `--light`,
 * `--red` and the Gill Sans stack. Three values are not HSG's and are marked
 * below: a pale green for tinted surfaces, and the two greens the dark scheme
 * needs, because `#00802f` on a near-black ground fails contrast and the
 * university publishes no dark scheme to copy.
 */
import * as stylex from '@stylexjs/stylex';

import { chrome, font } from './tokens.stylex.ts';

const DARK = '@media (prefers-color-scheme: dark)';

/** HSG's own, verbatim. */
const GREEN = '#00802f';
const GREEN_DARK = '#0a5e2d';
const GREY_DARK = '#6f706f';
const GREY = '#c2c2c2';
const LIGHT = '#f5f5f5';
const RED = '#e31c3d';

/**
 * Derived, not published: a tint of GREEN for the surfaces that used to carry
 * the accent at low opacity, and the two greens a dark ground needs. Replace
 * them if the brand portal names better ones.
 */
const GREEN_PALE = '#e6f2ea';
const GREEN_LIT = '#4caf72';
const GREEN_TINT_DARK = '#122a1c';

export const hsg = stylex.createTheme(chrome, {
  bg: { default: LIGHT, [DARK]: '#0e1013' },
  surface: { default: '#ffffff', [DARK]: '#16191e' },
  surface2: { default: '#ebecea', [DARK]: '#1d2127' },
  border: { default: '#dcdedb', [DARK]: '#262b33' },
  borderStrong: { default: GREY, [DARK]: '#363d47' },
  /**
   * `#000` is the university's black. Kept at full strength for headings and
   * figures via `text`, while `text2` and `text3` stay grey — a page of pure
   * black body copy is a poster, not an instrument.
   */
  text: { default: '#000000', [DARK]: '#e8eaee' },
  text2: { default: GREY_DARK, [DARK]: '#9aa2ae' },
  text3: { default: '#8f908f', [DARK]: '#6b7380' },
  accent: { default: GREEN, [DARK]: GREEN_LIT },
  accentStrong: { default: GREEN_DARK, [DARK]: '#6cc48d' },
  accentSoft: { default: GREEN_PALE, [DARK]: GREEN_TINT_DARK },
  danger: RED,
  warn: '#e8a33d',
});

/**
 * Gill Sans MT Pro is licensed and will not be installed on most machines, so
 * the stack falls back through the Gill Sans family the university's own site
 * falls back through, and then to the system sans. A student without the
 * licence gets a page that is the right colour in the wrong face, which is the
 * correct failure: the alternative is shipping a font we have no right to.
 */
export const hsgType = stylex.createTheme(font, {
  sans: "'Gill Sans MT Pro', 'Gill Sans MT', 'Gill Sans', Calibri, -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif",
  serif: "'Palatino Linotype', Palatino, Charter, XCharter, 'Source Serif 4', Georgia, serif",
  mono: "ui-monospace, 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace",
});
