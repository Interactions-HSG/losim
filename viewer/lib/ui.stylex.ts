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

import { chrome, font, radius, shadow, size } from './tokens.stylex.ts';

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
    height: '36px',
    paddingBlock: 0,
    paddingInline: '16px',
    font: 'inherit',
    fontSize: size.md,
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
    backgroundColor: { default: chrome.accent, ':hover': chrome.accentStrong },
    borderColor: 'transparent',
  },
  icon: { width: '36px', paddingInline: 0, justifyContent: 'center' },
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
    height: '25px',
    paddingBlock: 0,
    paddingInline: '8px',
    font: 'inherit',
    fontSize: size.sm,
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
    height: '32px',
    maxWidth: '260px',
    paddingBlock: 0,
    paddingRight: '28px',
    paddingLeft: '10px',
    font: 'inherit',
    fontSize: size.md,
    fontWeight: 500,
    color: chrome.text,
    backgroundColor: chrome.surface,
    /**
     * The arrow, drawn as two gradient wedges rather than as an SVG.
     *
     * It was a data URI with `stroke='%238e959f'` in it — a grey no theme could
     * reach, because a data URI is an opaque string to CSS and cannot read a
     * variable. So the one mark on this control stayed the old palette's while
     * everything around it turned. Two half-transparent gradients meeting at a
     * point make the same chevron out of a colour token, and the token is what
     * makes it follow the theme and the dark scheme without being told twice.
     */
    backgroundImage: `linear-gradient(45deg, transparent 50%, ${chrome.text3} 50%),`
      + ` linear-gradient(135deg, ${chrome.text3} 50%, transparent 50%)`,
    backgroundSize: '5px 5px, 5px 5px',
    backgroundRepeat: 'no-repeat',
    backgroundPosition: 'right 14px center, right 10px center',
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
    height: '23px',
    paddingBlock: 0,
    paddingInline: '9px',
    fontSize: size.xs,
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
