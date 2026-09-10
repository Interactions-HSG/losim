/**
 * What a row's state does to the cells inside it.
 *
 * `.ledger-body tbody tr:hover td` and `tr.hot td` both reached from a row into
 * its cells, and StyleX has no descendant selectors. The row sets this; every
 * cell reads it. Hovering a row still lights the whole row, and the coupling is
 * written down in both places instead of living in a selector.
 */
import * as stylex from '@stylexjs/stylex';

export const rowVars = stylex.defineVars({ cellBg: 'transparent' });
