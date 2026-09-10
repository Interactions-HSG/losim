/**
 * The chrome's design tokens, as StyleX variables.
 *
 * These are what globals.css declared on :root, moved verbatim — the values are
 * the same in both schemes, and the dark ones are the ones the old
 * `prefers-color-scheme` block set.
 *
 * Two registers, and the line between them is the whole idea. **The figure** —
 * nodes, zones, packets, spans — is drawn in the language of the whiteboard, and
 * those tokens are fixed in both schemes because a film recorded in the dark and
 * one recorded in the light have to be the same file. **Everything else** is
 * chrome, and follows the system.
 *
 * Dark sits beside light in one declaration rather than in a second :root block:
 * a token and its dark value cannot drift apart if they are written together.
 */
import * as stylex from '@stylexjs/stylex';

const DARK = '@media (prefers-color-scheme: dark)';

/** Chrome. Neutral, low-contrast, out of the way. */
export const chrome = stylex.defineVars({
  bg: { default: '#f6f7f9', [DARK]: '#0e1013' },
  surface: { default: '#ffffff', [DARK]: '#16191e' },
  surface2: { default: '#f1f3f6', [DARK]: '#1d2127' },
  border: { default: '#e3e6eb', [DARK]: '#262b33' },
  borderStrong: { default: '#cfd4dc', [DARK]: '#363d47' },
  text: { default: '#14161a', [DARK]: '#e8eaee' },
  text2: { default: '#5b626d', [DARK]: '#9aa2ae' },
  text3: { default: '#8e959f', [DARK]: '#6b7380' },
  accent: { default: '#3d6fd4', [DARK]: '#6b95ea' },
  accentSoft: { default: '#e8eefb', [DARK]: '#1b2740' },
  danger: '#c4342a',
  warn: '#e8a33d',
});

/**
 * The console's own bar. Dark in both schemes, so the chrome ends somewhere
 * visible and the page below it can be quiet all the way down.
 */
export const bar = stylex.defineVars({
  bg: { default: '#1c2029', [DARK]: '#090b0e' },
  ink: { default: '#f2f4f7', [DARK]: '#e8eaee' },
  dim: { default: '#a7b0bd', [DARK]: '#838c99' },
});

export const shadow = stylex.defineVars({
  s1: {
    default: '0 1px 2px rgba(16, 20, 28, 0.05), 0 1px 1px rgba(16, 20, 28, 0.03)',
    [DARK]: '0 1px 2px rgba(0, 0, 0, 0.4)',
  },
  s2: {
    default: '0 4px 16px rgba(16, 20, 28, 0.08), 0 1px 3px rgba(16, 20, 28, 0.05)',
    [DARK]: '0 4px 16px rgba(0, 0, 0, 0.45)',
  },
  s3: {
    default: '0 12px 40px rgba(16, 20, 28, 0.16), 0 2px 8px rgba(16, 20, 28, 0.06)',
    [DARK]: '0 12px 40px rgba(0, 0, 0, 0.55)',
  },
});

export const radius = stylex.defineVars({ sm: '6px', base: '10px', lg: '14px' });

/**
 * The figure. Fixed in both schemes on purpose — see the note above, and
 * lib/design.ts, which owns what these mean.
 */
export const figure = stylex.defineVars({
  paper: '#f7f7f5',
  ink: '#1a1a1a',
  pencil: '#5a5a5a',
  rule: '#bfbfbf',
  faint: '#e4e4e0',
  narrate: '#d6392b',
});

export const font = stylex.defineVars({
  sans: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', Helvetica, Arial, sans-serif",
  serif: "Charter, XCharter, 'Bitstream Charter', 'Source Serif 4', 'Iowan Old Style', Georgia, serif",
  mono: "ui-monospace, 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace",
});
