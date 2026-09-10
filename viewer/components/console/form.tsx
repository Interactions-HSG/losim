/**
 * The controls a student authors a simulation with, as components.
 *
 * Simulations.tsx used to carry nine `<style>` blocks, one per section, and
 * they were not scoped to their sections: a `<style>` tag is global wherever it
 * is written, so `.field`, `.hint`, `.rule` and `.lead` were each declared four
 * or five times over and the winner was whichever section React happened to
 * render last. `.field input` took its width from the scale section and its
 * font from the network section, and neither section could be read to find that
 * out.
 *
 * So the shapes that were repeated are declared once, here, and the tags that
 * were styled by name — `label`, `input`, `select` — become components that
 * carry their own look, the same move `lib/text.tsx` made for prose. The two
 * sizes are real and stay: a field on a card is 32px, a control inside a rule
 * in a list is 28px.
 */
import type { ComponentProps, ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import { chrome, font, radius } from '../../lib/tokens.stylex.ts';

type Styles = stylex.StyleXStyles;
/** `className?: never` for the reason lib/text.tsx gives at length. */
type With<T extends keyof React.JSX.IntrinsicElements> = Omit<
  ComponentProps<T>,
  'style' | 'className'
> & { style?: Styles; className?: never; children?: ReactNode };

export const form = stylex.create({
  /** A row of fields, wrapping rather than shrinking: these are all captions. */
  row: { display: 'flex', gap: '12px', flexWrap: 'wrap' },
  wide: { gap: '20px', alignItems: 'flex-start', marginBottom: '14px' },
  field: { display: 'flex', flexDirection: 'column', gap: '4px', minWidth: '90px' },
  /** The one field on a row that takes what is left — an instance name is long. */
  grow: { flexGrow: 1, flexShrink: 1, minWidth: '180px' },
  label: { fontSize: '11.5px', color: chrome.text3 },
  control: {
    height: '32px',
    paddingBlock: 0,
    paddingInline: '10px',
    font: 'inherit',
    fontSize: '13px',
    color: chrome.text,
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.sm,
  },
  /** Anything typed rather than chosen is a value, and values are monospaced. */
  typed: { fontFamily: font.mono, width: '110px' },
  small: { height: '28px', paddingInline: '8px', fontSize: '12.5px' },
  hint: { fontSize: '11px', color: chrome.text3 },
  warn: { color: chrome.warn },
  lead: { fontSize: '13px', marginTop: 0, marginRight: 0, marginBottom: '14px', marginLeft: 0 },
  /** A heading over a group of controls, small enough not to be a section. */
  lbl: { fontSize: '11.5px', color: chrome.text3 },

  /**
   * One line of a list of rules — a failure, a cost, a retry. Every list of them
   * rules between its lines and not above the first, which is what `+ .rule`
   * used to say and what `ruled` now says on the row that knows its own index.
   */
  rule: {
    display: 'flex',
    alignItems: 'center',
    gap: '7px',
    flexWrap: 'wrap',
    fontSize: '12.5px',
    color: chrome.text2,
    paddingBlock: '6px',
  },
  ruled: { borderTopWidth: '1px', borderTopStyle: 'solid', borderTopColor: chrome.border },
  /** The sentence under a rule saying what it will do. It gets its own line. */
  aside: { flexBasis: '100%', fontSize: '11px', color: chrome.text3 },

  /** A note the author should read before running: blue for a fact, amber for a risk. */
  flag: {
    display: 'flex',
    gap: '10px',
    alignItems: 'flex-start',
    marginTop: '14px',
    paddingBlock: '11px',
    paddingInline: '14px',
    borderRadius: radius.sm,
    fontSize: '12.5px',
    backgroundColor: chrome.accentSoft,
    color: chrome.text2,
  },
  flagWarn: {
    backgroundColor: { default: '#fff8e8', '@media (prefers-color-scheme: dark)': '#2a2211' },
    color: { default: '#6b4d09', '@media (prefers-color-scheme: dark)': '#e6c684' },
  },

  /** A toggle: a service, a zone, a preset count. On means chosen, not merely hovered. */
  pill: {
    height: '26px',
    paddingBlock: 0,
    paddingInline: '11px',
    font: 'inherit',
    fontSize: '11.5px',
    cursor: 'pointer',
    color: { default: chrome.text3, ':hover': chrome.text2 },
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: { default: chrome.border, ':hover': chrome.borderStrong },
    borderRadius: '999px',
  },
  pillOn: {
    color: chrome.accent,
    borderColor: chrome.accent,
    backgroundColor: chrome.accentSoft,
  },
  /** The entry point is not one choice among many, so it is not the accent. */
  pillEntry: { color: '#1a7f37', borderColor: 'currentColor', backgroundColor: 'transparent' },
  /** A button that is an operation on one line — remove, add — rather than an action. */
  mini: { height: '26px', width: '26px', paddingInline: 0, justifyContent: 'center' },
  short: { height: '26px' },
});

export const Field = ({ grow, style, ...rest }: With<'div'> & { grow?: boolean }) => (
  <div {...rest} {...stylex.props(form.field, grow && form.grow, style)} />
);
export const Label = ({ style, ...rest }: With<'label'>) => (
  <label {...rest} {...stylex.props(form.label, style)} />
);
export const Hint = ({ warn, style, ...rest }: With<'span'> & { warn?: boolean }) => (
  <span {...rest} {...stylex.props(form.hint, warn && form.warn, style)} />
);
export const Aside = ({ warn, style, ...rest }: With<'span'> & { warn?: boolean }) => (
  <span {...rest} {...stylex.props(form.aside, warn && form.warn, style)} />
);
export const Lbl = ({ style, ...rest }: With<'span'>) => (
  <span {...rest} {...stylex.props(form.lbl, style)} />
);

/** `small` is the 28px size a control takes inside a rule, rather than on a card. */
export const Input = ({ small, style, ...rest }: With<'input'> & { small?: boolean }) => (
  <input {...rest} {...stylex.props(form.control, form.typed, small && form.small, style)} />
);
export const Select = ({ small, style, ...rest }: With<'select'> & { small?: boolean }) => (
  <select {...rest} {...stylex.props(form.control, small && form.small, style)} />
);
