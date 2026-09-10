'use client';

/**
 * The money, while it is being spent.
 *
 * Five buckets rather than one number, because they are five different kinds of
 * decision and adding them up hides the trade. Replication triples **capacity**
 * and adds to **build** in order to empty **incidents**; one figure cannot say
 * that, and a design argument that turns on it cannot be had against a total.
 *
 * Read it while the film plays. Capacity is flat from the first frame — you have
 * already bought a minute of every node before a single call is made — build
 * creeps, consumption follows the work, and incidents are steps at the instants
 * things broke.
 *
 * **And it answers the film.** Point at a node and this says what that
 * node costs: its own slice inside every bar, its lines lifted to the top,
 * and everything it is not answerable for faded back. That connection is the
 * point of having both on one screen — "s0 is the expensive one" is a sentence
 * about a picture and a bill at the same time, and a viewer should not have to
 * hold the two in their head to make it.
 */
import * as stylex from '@stylexjs/stylex';
import { BUCKETS, money, type Bucket, type Ledger as L } from '../lib/ledger.ts';
import * as D from '../lib/design.ts';
import { Code, P, Table, Td, Th } from '../lib/text.tsx';

import { chrome, radius, shadow } from '../lib/tokens.stylex.ts';
import { ui } from '../lib/ui.stylex.ts';
import { rowVars } from './ledger.stylex.ts';

/** One palette for the four buckets, wherever they are drawn. */
export const COLOUR: Record<Bucket, string> = {
  build: '#8E6BA8',
  capacity: '#3C6E9F',
  consumption: '#3E8E8A',
  incidents: D.ALARM,
};

const WHY: Record<Bucket, string> = {
  build: 'Engineering time to construct this design, carried whether or not the thing it protects against happens.',
  capacity: 'The cluster you reserved, priced for the whole period. An idle node costs exactly as much as a busy one.',
  consumption: 'What the work actually burned: storage and egress. This is the line a better algorithm moves.',
  incidents: 'What failure cost: reruns, lost work, being late. Zero until something breaks, then large.',
};

export function LedgerStrip({
  l,
  open,
  onToggle,
  onHover,
}: {
  l: L;
  open: boolean;
  onToggle: () => void;
  /** Pointing at a line points at its node, so the link runs both ways. */
  onHover?: (node: string | null) => void;
}) {
  const scale = Math.max(l.finalCost, 0.0001);
  const focus = l.focus;

  return (
    <div {...stylex.props(styles.ledger)}>
      <button
        {...stylex.props(styles.head, open && styles.headOpen, !!focus && styles.headFocused)}
        onClick={onToggle}
        aria-expanded={open}
      >
        <span {...stylex.props(styles.pl)}>
          <span {...stylex.props(styles.lbl)}>cost</span>
          <strong {...stylex.props(styles.big)}>{money(l.cost, l.currency)}</strong>
        </span>
        {/* What it will come to, beside what it has come to. A cost with nothing
            to be large against is a number nobody can read. */}
        <span {...stylex.props(styles.pl)}>
          <span {...stylex.props(styles.lbl)}>of</span>
          <strong {...stylex.props(styles.big, styles.bigMuted)}>{money(l.finalCost, l.currency)}</strong>
        </span>

        {/* When a node is being pointed at, its own figure stands beside the
            cluster's rather than replacing it: what matters is the proportion, and
            a share shown alone is a number with nothing to be large against. */}
        {focus && (
          <span {...stylex.props(styles.pl, styles.mine)}>
            <span {...stylex.props(styles.lbl, styles.mineLbl)}>{focus.name}</span>
            <strong {...stylex.props(styles.big, styles.mineBig)}>{money(focus.cost, l.currency)}</strong>
            <span {...stylex.props(styles.pct)}>{Math.round((focus.cost / Math.max(l.cost, 1e-9)) * 100)}%</span>
          </span>
        )}

        {/* One stacked bar: what has been spent, against what the whole run comes
            to. The pale remainder is what is still coming, and the bright notch
            inside each segment is the pointed-at node's part of it. */}
        <span {...stylex.props(styles.bar)} title="cost so far, against the whole run">
          {BUCKETS.map((b) => (
            <span
              key={b}
              {...stylex.props(styles.barSeg)}
              style={{ width: `${(l.buckets[b] / scale) * 100}%`, background: COLOUR[b] }}
              title={`${b} ${money(l.buckets[b], l.currency)}`}
            >
              {focus && focus.buckets[b] > 0 && (
                <i style={{ width: `${(focus.buckets[b] / Math.max(l.buckets[b], 1e-9)) * 100}%` }} />
              )}
            </span>
          ))}
          <span {...stylex.props(styles.rest)} style={{ width: `${Math.max(0, ((l.finalCost - l.cost) / scale) * 100)}%` }} />
        </span>

        <span {...stylex.props(styles.caret)}>{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div {...stylex.props(styles.body)}>
          <div {...stylex.props(styles.buckets)}>
            {BUCKETS.map((b) => {
              const mine = focus?.buckets[b] ?? 0;
              return (
                <div
                  key={b}
                  {...stylex.props(styles.bk, !!focus && (mine > 0 ? styles.hot : styles.cold))}
                  title={WHY[b]}
                >
                  <span {...stylex.props(styles.dot)} style={{ background: COLOUR[b] }} />
                  <span {...stylex.props(styles.bkName)}>{b}</span>
                  <span {...stylex.props(styles.amt, ui.mono)}>{money(l.buckets[b], l.currency)}</span>
                  {focus && mine > 0 && (
                    <span {...stylex.props(styles.bkOf, ui.mono)}>
                      {focus.name} {money(mine, l.currency)}
                    </span>
                  )}
                  <P {...stylex.props(styles.bkWhy)}>{WHY[b]}</P>
                </div>
              );
            })}
          </div>

          <Table>
            <thead>
              <tr>
                <Th>line</Th>
                <Th style={cells.right}>quantity</Th>
                <Th style={cells.right}>so far</Th>
                <Th style={cells.right}>{focus ? focus.name : 'whole run'}</Th>
              </tr>
            </thead>
            <tbody>
              {l.lines.slice(0, 16).map(({ line, sofar, mine, why }, i) => {
                const node = line.bucket === 'capacity' ? line.what.split(' (')[0] : null;
                return (
                  <tr
                    key={i}
                    {...stylex.props(!!focus && (mine > 0 ? styles.rowHot : styles.rowCold), styles.row)}
                    onMouseEnter={() => node && onHover?.(node)}
                    onMouseLeave={() => node && onHover?.(null)}
                  >
                    <Td style={styles.cell}>
                      <span {...stylex.props(styles.dot)} style={{ background: COLOUR[line.bucket] }} />
                      {line.what}
                      {why && <em {...stylex.props(styles.why)}> — {why}</em>}
                    </Td>
                    <Td num style={[cells.right, styles.cell]}>
                      {line.quantity.toPrecision(3)} <span {...stylex.props(ui.muted)}>{line.unit}</span>
                    </Td>
                    <Td num style={[cells.right, styles.cell]}>
                      {money(sofar, l.currency)}
                    </Td>
                    <Td num style={[cells.right, styles.cell, ui.muted]}>
                      {focus
                        ? mine > 0
                          ? money(mine, l.currency)
                          : '—'
                        : money(line.amount, l.currency)}
                    </Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
          <P {...stylex.props(styles.fine)}>
            Every amount here is a line <Code>losim bill</Code> already computed; what is added
            is only when it arrives, and who it belongs to. The closing total is the
            bill&rsquo;s, exactly.
            {focus && (
              <>
                {' '}
                A dash means the line is nobody&rsquo;s in particular — the late-finish
                penalty belongs to the job, not to a node.
              </>
            )}
          </P>
        </div>
      )}

    </div>
  );
}

/** The money columns line up on the right, where a column of numbers belongs. */
const cells = stylex.create({ right: { textAlign: 'right' } });

const styles = stylex.create({
  ledger: { flex: 'none' },

  head: {
    display: 'flex',
    alignItems: 'center',
    gap: '14px',
    width: '100%',
    paddingBlock: '7px',
    paddingInline: '12px',
    font: 'inherit',
    color: chrome.text,
    cursor: 'pointer',
    backgroundColor: { default: chrome.surface, ':hover': chrome.surface2 },
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.lg,
    boxShadow: shadow.s1,
    transitionProperty: 'border-color',
    transitionDuration: '.12s',
  },
  /** Open, the head is the top of the panel rather than a thing of its own. */
  headOpen: { borderRadius: `${radius.lg} ${radius.lg} 0 0`, borderBottomColor: 'transparent' },
  headFocused: { borderColor: chrome.accent },

  pl: { display: 'flex', alignItems: 'baseline', gap: '6px', whiteSpace: 'nowrap' },
  lbl: {
    fontSize: '10.5px',
    fontWeight: 600,
    letterSpacing: '.05em',
    textTransform: 'uppercase',
    color: chrome.text3,
  },
  big: { fontSize: '15px', fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em' },
  bigMuted: { fontSize: '13px', color: chrome.text3, fontWeight: 500 },
  /** The pointed-at node's own figure, beside the cluster's rather than replacing it. */
  mine: {
    paddingTop: '2px',
    paddingInline: '9px',
    paddingBottom: '3px',
    borderRadius: '999px',
    backgroundColor: chrome.surface2,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
  },
  mineLbl: { color: chrome.text2, textTransform: 'none', letterSpacing: 0, fontSize: '12px' },
  mineBig: { fontSize: '13.5px' },
  pct: { fontSize: '11.5px', color: chrome.text3 },

  bar: {
    flex: 1,
    display: 'flex',
    height: '8px',
    minWidth: '80px',
    borderRadius: '999px',
    overflow: 'hidden',
    backgroundColor: chrome.surface2,
  },
  barSeg: { position: 'relative', height: '100%' },
  rest: { height: '100%', backgroundColor: chrome.border },
  caret: { color: chrome.text3, fontSize: '11px' },

  body: {
    paddingTop: '12px',
    paddingInline: '14px',
    paddingBottom: '14px',
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderTopWidth: 0,
    borderRadius: `0 0 ${radius.lg} ${radius.lg}`,
    boxShadow: shadow.s1,
    maxHeight: '38vh',
    overflowY: 'auto',
  },
  buckets: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
    gap: '10px',
    marginBottom: '14px',
  },
  bk: {
    paddingBlock: '9px',
    paddingInline: '10px',
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.base,
    backgroundColor: chrome.surface2,
    display: 'grid',
    gridTemplateColumns: 'auto 1fr auto',
    gap: '6px',
    alignItems: 'center',
    transitionProperty: 'opacity, border-color',
    transitionDuration: '.12s',
  },
  cold: { opacity: 0.38 },
  hot: { borderColor: chrome.text3 },
  bkName: { fontWeight: 600, fontSize: '12.5px' },
  amt: { fontSize: '12.5px' },
  bkOf: { gridColumn: '1 / -1', fontSize: '11.5px', color: chrome.text2, paddingTop: '1px' },
  bkWhy: {
    gridColumn: '1 / -1',
    margin: '2px 0 0',
    fontSize: '11px',
    lineHeight: 1.4,
    color: chrome.text3,
    maxWidth: 'none',
  },
  dot: {
    width: '8px',
    height: '8px',
    borderRadius: '50%',
    display: 'inline-block',
    marginRight: '7px',
    verticalAlign: '1px',
  },

  /** The row owns the colour; every cell in it reads `cellBg`. */
  row: { [rowVars.cellBg]: { default: 'transparent', ':hover': chrome.surface2 } },
  rowHot: { [rowVars.cellBg]: chrome.surface2 },
  rowCold: { opacity: 0.32 },
  cell: { backgroundColor: rowVars.cellBg },

  why: { color: chrome.text3, fontStyle: 'normal', fontSize: '11.5px' },
  fine: { fontSize: '11px', color: chrome.text3, margin: '10px 0 0', maxWidth: 'none' },
});
