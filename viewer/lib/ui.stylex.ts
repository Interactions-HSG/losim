/**
 * The controls the whole app shares, as StyleX.
 *
 * These were the class names in globals.css that more than one component used —
 * a button, a segmented control, a picker, a card, a chip, and the two type
 * treatments. Everything that only one component used went to that component,
 * where a style and the markup it dresses can be read together.
 *
 * The bare tags they used to sit beside are gone from globals.css too: prose is
 * lib/text.tsx, form controls are components/console/form.tsx, and what is left
 * in the stylesheet is the reset and the scrollbar, which are the document's
 * rather than anything's here.
 */
import * as stylex from '@stylexjs/stylex';

import { chrome, font, radius, shadow } from './tokens.stylex.ts';

export const ui = stylex.create({
  /**
   * The order of the states matters: `:disabled` is written after `:hover`, so a
   * disabled button that the pointer is over stays looking disabled. The old CSS
   * said the same thing with `:hover:not(:disabled)`, which StyleX has no way to
   * express.
   */
  btn: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    height: '34px',
    paddingBlock: 0,
    paddingInline: '16px',
    font: 'inherit',
    fontSize: '13px',
    fontWeight: 500,
    color: { default: chrome.text, ':disabled': chrome.text3 },
    backgroundColor: { default: chrome.surface, ':hover': chrome.surface2 },
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: { default: chrome.border, ':hover': chrome.borderStrong },
    borderRadius: '999px',
    boxShadow: { default: shadow.s1, ':disabled': 'none' },
    cursor: { default: 'pointer', ':disabled': 'default' },
    transitionProperty: 'background-color, border-color, transform',
    transitionDuration: '.12s',
    whiteSpace: 'nowrap',
    transform: { default: null, ':active': 'translateY(0.5px)' },
    outline: { default: null, ':focus-visible': `2px solid ${chrome.accent}` },
    outlineOffset: '2px',
  },
  primary: {
    color: '#fff',
    backgroundColor: chrome.accent,
    borderColor: 'transparent',
    filter: { default: null, ':hover': 'brightness(1.07)' },
  },
  icon: { width: '34px', paddingInline: 0, justifyContent: 'center' },
  /**
   * A button that is a toggle, showing whether it is on.
   *
   * `.btn` never had an `[aria-pressed]` rule, so "failed only" and "critical
   * path" announced their state to a screen reader and showed nothing to
   * anybody looking at them. The seg has always drawn its pressed button; this
   * is the same idea for a button that stands alone.
   */
  btnOn: {
    color: chrome.accent,
    backgroundColor: { default: chrome.accentSoft, ':hover': chrome.accentSoft },
    borderColor: { default: chrome.accent, ':hover': chrome.accent },
  },

  /** A segmented control, for the speeds. */
  seg: {
    display: 'inline-flex',
    padding: '2px',
    backgroundColor: chrome.surface2,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.sm,
  },
  segButton: {
    minWidth: '34px',
    height: '24px',
    paddingBlock: 0,
    paddingInline: '7px',
    font: 'inherit',
    fontSize: '12px',
    fontWeight: 500,
    fontVariantNumeric: 'tabular-nums',
    color: { default: chrome.text2, ':hover': chrome.text },
    backgroundColor: 'transparent',
    borderWidth: 0,
    borderRadius: '4px',
    cursor: 'pointer',
    transitionProperty: 'background-color, color',
    transitionDuration: '.12s',
    outline: { default: null, ':focus-visible': `2px solid ${chrome.accent}` },
    outlineOffset: '2px',
  },
  /** What `[aria-pressed='true']` used to select. The caller knows which one it is. */
  segOn: { color: chrome.text, backgroundColor: chrome.surface, boxShadow: shadow.s1 },

  picker: {
    height: '30px',
    maxWidth: '260px',
    paddingBlock: 0,
    paddingRight: '28px',
    paddingLeft: '10px',
    font: 'inherit',
    fontSize: '13px',
    fontWeight: 500,
    color: chrome.text,
    backgroundColor: chrome.surface,
    backgroundImage:
      "url(\"data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M1 1l4 4 4-4' fill='none' stroke='%238e959f' stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E\")",
    backgroundRepeat: 'no-repeat',
    backgroundPosition: 'right 10px center',
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.sm,
    boxShadow: shadow.s1,
    appearance: 'none',
    cursor: 'pointer',
    outline: { default: null, ':focus-visible': `2px solid ${chrome.accent}` },
    outlineOffset: '2px',
  },

  card: {
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.lg,
    boxShadow: shadow.s1,
  },
  chip: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '5px',
    height: '22px',
    paddingBlock: 0,
    paddingInline: '8px',
    fontSize: '11.5px',
    fontWeight: 500,
    color: chrome.text2,
    backgroundColor: chrome.surface2,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: '999px',
  },
  mono: { fontFamily: font.mono, fontVariantNumeric: 'tabular-nums' },
  muted: { color: chrome.text2 },
});
