/**
 * The tags that used to be styled by name, as components that style themselves.
 *
 * globals.css gave `p`, `a`, `h1`, `h2`, `code`, `table`, `th` and `td` their
 * look from one place, which is the thing StyleX exists to remove: a component
 * rendered a paragraph and something three files away decided what it looked
 * like. Each of these carries its own baseline instead, and takes a `style` prop
 * so a caller can override it — local styles first, prop styles last, which is
 * the documented order.
 *
 * A reset is different and stays in globals.css: `*`, `html`, `body` and the
 * scrollbar are the document's, not any component's, and StyleX has no element
 * selectors to express them with.
 */
import type { ComponentProps, ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import { chrome, font, radius } from './tokens.stylex.ts';

type Styles = stylex.StyleXStyles;

export const text = stylex.create({
  h1: { fontSize: '15px', fontWeight: 600, letterSpacing: '-0.011em', margin: 0 },
  h2: {
    fontSize: '12px',
    fontWeight: 600,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    color: chrome.text3,
    margin: '0 0 8px',
  },
  p: { color: chrome.text2, margin: '0 0 10px', maxWidth: '64ch' },
  a: {
    color: chrome.accent,
    textDecoration: { default: 'none', ':hover': 'underline' },
  },
  code: {
    fontFamily: font.mono,
    fontSize: '0.9em',
    backgroundColor: chrome.surface2,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: '5px',
    paddingBlock: '1px',
    paddingInline: '5px',
  },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '13px' },
  cell: {
    textAlign: 'left',
    paddingTop: '10px',
    paddingRight: '16px',
    paddingBottom: '10px',
    paddingLeft: 0,
    borderBottomWidth: '1px',
    borderBottomStyle: 'solid',
    borderBottomColor: chrome.border,
    verticalAlign: 'baseline',
  },
  th: {
    color: chrome.text3,
    fontWeight: 500,
    fontSize: '11px',
    letterSpacing: '0.03em',
    textTransform: 'uppercase',
  },
  /** `tbody tr:last-child td` had no bottom rule. A row knows whether it is last. */
  lastRow: { borderBottomWidth: 0 },
  /** What `td.n` meant: a column of numbers, lined up. */
  num: { fontFamily: font.mono, fontVariantNumeric: 'tabular-nums' },
});

/**
 * `className?: never` is load-bearing, not decoration.
 *
 * These components spread `stylex.props` *after* whatever they are given, so a
 * `className` handed to one — either written out, or spread in from a
 * `stylex.props()` call at the call site — is overwritten before it reaches the
 * DOM and the style is silently lost. That is not hypothetical: it cost this
 * app 59 right-aligned number columns, a page title, and every breadcrumb in
 * the console, and nothing failed while it did. Declaring the prop as `never`
 * turns both spellings of the mistake into a compile error.
 *
 * Styles come through `style`, which takes one or an array of them.
 */
type With<T extends keyof React.JSX.IntrinsicElements> = Omit<
  ComponentProps<T>,
  'style' | 'className'
> & {
  style?: Styles;
  className?: never;
  children?: ReactNode;
};

export const H1 = ({ style, ...rest }: With<'h1'>) => <h1 {...rest} {...stylex.props(text.h1, style)} />;
export const H2 = ({ style, ...rest }: With<'h2'>) => <h2 {...rest} {...stylex.props(text.h2, style)} />;
export const P = ({ style, ...rest }: With<'p'>) => <p {...rest} {...stylex.props(text.p, style)} />;
export const A = ({ style, ...rest }: With<'a'>) => <a {...rest} {...stylex.props(text.a, style)} />;
export const Code = ({ style, ...rest }: With<'code'>) => <code {...rest} {...stylex.props(text.code, style)} />;
export const Kbd = ({ style, ...rest }: With<'kbd'>) => <kbd {...rest} {...stylex.props(text.code, style)} />;
export const Table = ({ style, ...rest }: With<'table'>) => <table {...rest} {...stylex.props(text.table, style)} />;
export const Th = ({ style, ...rest }: With<'th'>) => <th {...rest} {...stylex.props(text.cell, text.th, style)} />;

/** `last` drops the rule under the final row, which `tr:last-child` used to do. */
export const Td = ({ style, num, last, ...rest }: With<'td'> & { num?: boolean; last?: boolean }) => (
  <td {...rest} {...stylex.props(text.cell, num && text.num, last && text.lastRow, style)} />
);
