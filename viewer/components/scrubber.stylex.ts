/**
 * The two things about the scrubber that a parent's state decides.
 *
 * StyleX has no descendant selectors on purpose, so `.scrub:hover .track` has
 * nowhere to live. These are the sanctioned replacement: the parent sets them
 * from its own `:hover`, the children read them, and the coupling is visible in
 * both files rather than hidden in a selector.
 */
import * as stylex from '@stylexjs/stylex';

export const scrubVars = stylex.defineVars({
  /** The bar thickens under the pointer, so the thing being dragged is the thing being pointed at. */
  trackHeight: '5px',
  /** The handle only exists while you are using it. */
  knobScale: '0',
});
