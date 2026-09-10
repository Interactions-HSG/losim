'use client';

/**
 * The console around the views.
 *
 * Spacious: a wide left rail you can read from across a lecture theatre, page
 * titles set light rather than bold, and content in white cards on a grey ground
 * with room around them. The density argument is that this is a teaching tool
 * before it is an instrument — a student meeting a distributed system for the
 * first time should not also be meeting an eleven-column table.
 *
 * The two registers of `globals.css` still hold and this is the chrome half of
 * them. Nothing here touches the figure's tokens: the film is a figure, drawn in
 * the language of the whiteboard, and it looks the same in any theme so that a
 * recorded film is one file rather than two.
 */
import type { ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import { bar, chrome, font, radius } from '../../lib/tokens.stylex.ts';
import { ui } from '../../lib/ui.stylex.ts';

import { Transport } from './Transport.tsx';
import { TIMED, useConsole, type View } from '../../lib/console.tsx';
import { H1, H2, P } from '../../lib/text.tsx';

interface Item {
  id: View;
  label: string;
  icon: string;
  tag?: string;
}

export function Shell({ children }: { children: ReactNode }) {
  const {
    runs, run, clock, view, go, hasLab, error, busy, openDropped, setError, building,
  } = useConsole();

  const lab: Item[] = [
    { id: 'runs', label: 'Runs', icon: '▤', tag: String(runs.length || '') },
    // Only with a lab behind the page: the designer reads its classes off what
    // the project compiles to, and there is nothing to read without one.
    ...(hasLab
      ? [{ id: 'simulations' as View, label: 'Simulations', icon: '✎' }]
      : []),
  ];
  const open: Item[] = [
    { id: 'overview', label: 'Overview', icon: '≡' },
    { id: 'film', label: 'Film', icon: '▶' },
    { id: 'usage', label: 'Usage', icon: '◴' },
    { id: 'cost', label: 'Cost', icon: '¤' },
  ];

  return (
    <div
      {...stylex.props(styles.console)}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        const file = e.dataTransfer.files[0];
        if (file) void openDropped(file);
      }}
    >
      <header {...stylex.props(styles.bar)}>
        <span {...stylex.props(styles.brand)}>DISSAL</span>
        <span {...stylex.props(styles.svc)}>Distributed Systems Simulation Analysis Lab</span>
        <span {...stylex.props(styles.grow)} />
        {/* The one chip that belongs here: a build is true globally, not of one
            page — pressing ▶ on Simulations moves you to Runs before it finishes,
            so this is the only place left that says it is still going. */}
        {building && (
          <span {...stylex.props(ui.chip, styles.chipDark, styles.building)}>
            <i {...stylex.props(styles.dot)} aria-hidden /> building {building.simulation}
          </span>
        )}
      </header>

      <div {...stylex.props(styles.body)}>
        <nav {...stylex.props(styles.rail)} aria-label="console">
          <P {...stylex.props(styles.grp)}>Lab</P>
          <ul {...stylex.props(styles.list)}>
            {lab.map((n) => (
              <li key={n.id}>
                <button
                  {...stylex.props(styles.nav, view === n.id && styles.navOn)}
                  aria-current={view === n.id}
                  onClick={() => go(n.id)}
                >
                  <i {...stylex.props(styles.icon)}>{n.icon}</i>
                  {n.label}
                  {n.tag && <span {...stylex.props(styles.tag)}>{n.tag}</span>}
                </button>
              </li>
            ))}
          </ul>

          <P {...stylex.props(styles.grp)}>
            The open run
            {run && <span {...stylex.props(styles.of)}>{run.name}</span>}
          </P>
          <ul {...stylex.props(styles.list)}>
            {open.map((n) => (
              <li key={n.id}>
                <button
                  {...stylex.props(styles.nav, view === n.id && styles.navOn, !run && styles.navOff)}
                  aria-current={view === n.id}
                  disabled={!run}
                  onClick={() => go(n.id)}
                >
                  <i {...stylex.props(styles.icon)}>{n.icon}</i>
                  {n.label}
                </button>
              </li>
            ))}
          </ul>

          <P {...stylex.props(styles.fine)}>
            Drop a trace anywhere on this window to open it — a run from anybody, on any
            node, reads the same way.
          </P>
        </nav>

        <div {...stylex.props(styles.pane)}>
          {/* One clock, above every view that has time in it. Sticky, because a
              cost report is two screens long and the cursor has to stay in reach. */}
          {run && clock && TIMED.has(view) && <Transport run={run} clock={clock} />}

          <main {...stylex.props(styles.main)}>
            {error && (
              <div {...stylex.props(styles.err)} role="alert">
                <span>{error}</span>
                <button {...stylex.props(ui.btn)} onClick={() => setError(null)}>dismiss</button>
              </div>
            )}
            {busy && !run ? <div {...stylex.props(styles.wait)}>opening…</div> : children}
          </main>
        </div>
      </div>
    </div>
  );
}

/**
 * The head of a page: what it is, what it is of, and what you can do to it.
 *
 * The title is set light and large on purpose. A bold 23px heading on every page
 * of a console is six things shouting; one calm 26px line says the same thing
 * and leaves the emphasis for the numbers underneath, which are what somebody
 * came to read.
 */
export function Head({
  title,
  sub,
  actions,
  crumbs,
}: {
  title: string;
  sub?: ReactNode;
  actions?: ReactNode;
  crumbs?: ReactNode;
}) {
  return (
    <div {...stylex.props(styles.head)}>
      {crumbs && <P {...stylex.props(styles.crumbs)}>{crumbs}</P>}
      <div {...stylex.props(styles.headRow)}>
        <div>
          <H1 {...stylex.props(styles.h1)}>{title}</H1>
          {sub && <P {...stylex.props(styles.sub)}>{sub}</P>}
        </div>
        {actions && <div {...stylex.props(styles.acts)}>{actions}</div>}
      </div>
    </div>
  );
}

/** A card with a title, and room inside it. */
export function Panel({
  title,
  note,
  actions,
  flush,
  children,
}: {
  title?: string;
  note?: ReactNode;
  actions?: ReactNode;
  /** No padding: for a chart or a table that should reach the edges. */
  flush?: boolean;
  children: ReactNode;
}) {
  return (
    <section {...stylex.props(ui.card, styles.panel)}>
      {(title || actions) && (
        <header {...stylex.props(styles.panelHead)}>
          <H2 {...stylex.props(styles.h2)}>{title}</H2>
          {note && <span {...stylex.props(styles.note)}>{note}</span>}
          {actions && <span {...stylex.props(styles.panelActs)}>{actions}</span>}
        </header>
      )}
      <div {...stylex.props(styles.in_, flush && styles.flush)}>{children}</div>
    </section>
  );
}

/** One number, said once. */
export function Tile({ k, v, n }: { k: string; v: ReactNode; n?: ReactNode }) {
  return (
    <div {...stylex.props(ui.card, styles.tile)}>
      <span {...stylex.props(styles.tileK)}>{k}</span>
      <span {...stylex.props(styles.tileV)}>{v}</span>
      {n && <span {...stylex.props(styles.tileN)}>{n}</span>}
    </div>
  );
}

/** The dot beside "building", so a build that is still going looks like it is. */
const pulse = stylex.keyframes({ '0%, 100%': { opacity: 1 }, '50%': { opacity: 0.25 } });

const NARROW = '@media (max-width: 900px)';
const STILL = '@media (prefers-reduced-motion: reduce)';
const DARK = '@media (prefers-color-scheme: dark)';

const styles = stylex.create({
  console: { minHeight: '100vh', display: 'flex', flexDirection: 'column' },

  bar: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    height: '56px',
    paddingBlock: 0,
    paddingInline: '20px',
    flex: 'none',
    backgroundColor: bar.bg,
    color: bar.ink,
  },
  brand: { fontSize: '17px', fontWeight: 600, letterSpacing: '-0.02em' },
  // The title is long enough to wrap, and a wrapped one puts "Lab" under the
  // sidebar heading where it reads as a stray word.
  svc: { fontSize: '13.5px', color: bar.dim, whiteSpace: 'nowrap' },
  grow: { flex: 1 },

  chipDark: {
    backgroundColor: 'rgba(255,255,255,0.09)',
    borderColor: 'rgba(255,255,255,0.14)',
    color: bar.dim,
    height: '26px',
  },
  building: { display: 'flex', alignItems: 'center', gap: '7px', fontFamily: font.mono },
  dot: {
    width: '6px',
    height: '6px',
    borderRadius: '50%',
    backgroundColor: chrome.accent,
    flex: 'none',
    // Stated the other way round from the old CSS, which asked for
    // no-preference. Reduce is the preference worth naming.
    animationName: { default: pulse, [STILL]: 'none' },
    animationDuration: '1.2s',
    animationTimingFunction: 'ease-in-out',
    animationIterationCount: 'infinite',
  },

  body: {
    display: 'grid',
    gridTemplateColumns: { default: '244px minmax(0, 1fr)', [NARROW]: '1fr' },
    flex: 1,
    minHeight: 0,
  },
  rail: {
    paddingTop: '14px',
    paddingBottom: '24px',
    borderRightWidth: '1px',
    borderRightStyle: 'solid',
    borderRightColor: chrome.border,
    display: { default: 'block', [NARROW]: 'none' },
  },
  grp: {
    marginBlock: '18px 6px',
    paddingInline: '24px',
    fontSize: '12px',
    fontWeight: 500,
    color: chrome.text3,
    maxWidth: 'none',
  },
  of: {
    display: 'block',
    fontFamily: font.mono,
    fontSize: '11.5px',
    color: chrome.text3,
    opacity: 0.75,
    marginTop: '2px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  list: { listStyle: 'none', margin: 0, paddingBlock: 0, paddingRight: '8px', paddingLeft: 0 },

  /**
   * `:disabled` is written after `:hover` so a disabled item the pointer is over
   * stays looking disabled — the old CSS said that with `:hover:not(:disabled)`.
   * Which item is current is a prop rather than an attribute selector, because
   * the component already knows.
   */
  nav: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    width: '100%',
    height: '40px',
    paddingBlock: 0,
    paddingRight: '16px',
    paddingLeft: '24px',
    font: 'inherit',
    fontSize: '14px',
    color: { default: chrome.text2, ':hover': chrome.text },
    textAlign: 'left',
    backgroundColor: { default: 'transparent', ':hover': chrome.surface2 },
    borderWidth: 0,
    borderRadius: '0 999px 999px 0',
    cursor: 'pointer',
    transitionProperty: 'background-color, color',
    transitionDuration: '0.1s',
  },
  navOn: { backgroundColor: chrome.accentSoft, color: chrome.accent, fontWeight: 500 },
  navOff: { color: chrome.text3, opacity: 0.55, cursor: 'default', backgroundColor: 'transparent' },
  icon: { fontStyle: 'normal', width: '18px', textAlign: 'center', opacity: 0.9 },
  tag: {
    marginLeft: 'auto',
    fontFamily: font.mono,
    fontSize: '11.5px',
    color: chrome.text3,
    fontWeight: 400,
  },
  fine: {
    marginTop: '28px',
    marginInline: '24px',
    fontSize: '11.5px',
    lineHeight: 1.55,
    color: chrome.text3,
  },

  pane: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  main: {
    flex: 1,
    minWidth: 0,
    paddingTop: { default: '24px', [NARROW]: '18px' },
    paddingInline: { default: '28px', [NARROW]: '16px' },
    paddingBottom: { default: '72px', [NARROW]: '56px' },
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
  },
  err: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    paddingBlock: '12px',
    paddingInline: '16px',
    borderRadius: radius.base,
    backgroundColor: { default: '#fdeceb', [DARK]: '#2c1512' },
    color: { default: '#8f231c', [DARK]: '#f0b3ad' },
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: { default: '#f3c9c5', [DARK]: '#4a221d' },
    fontSize: '13px',
  },
  wait: { padding: '60px', textAlign: 'center', color: chrome.text3 },

  head: { display: 'flex', flexDirection: 'column', gap: '4px' },
  crumbs: { margin: '0 0 2px', fontSize: '12.5px', color: chrome.text3 },
  headRow: { display: 'flex', alignItems: 'flex-end', gap: '20px', flexWrap: 'wrap' },
  h1: { fontSize: '26px', fontWeight: 400, letterSpacing: '-0.012em', margin: 0 },
  sub: { margin: '4px 0 0', fontSize: '13.5px', color: chrome.text2, maxWidth: '80ch' },
  acts: { marginLeft: 'auto', display: 'flex', gap: '8px', flexWrap: 'wrap' },

  panel: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  panelHead: {
    display: 'flex',
    alignItems: 'baseline',
    gap: '12px',
    flexWrap: 'wrap',
    paddingTop: '18px',
    paddingInline: '20px',
    paddingBottom: 0,
  },
  h2: {
    fontSize: '16px',
    fontWeight: 500,
    letterSpacing: '-0.008em',
    textTransform: 'none',
    color: chrome.text,
    margin: 0,
  },
  note: { fontSize: '12.5px', color: chrome.text3 },
  panelActs: { marginLeft: 'auto', display: 'flex', gap: '6px' },
  in_: { paddingTop: '16px', paddingInline: '20px', paddingBottom: '20px', minWidth: 0 },
  /** No padding: for a chart or a table that should reach the edges. */
  flush: { paddingTop: '12px', paddingInline: 0, paddingBottom: 0 },

  tile: {
    display: 'flex',
    flexDirection: 'column',
    gap: '3px',
    paddingTop: '16px',
    paddingInline: '18px',
    paddingBottom: '18px',
  },
  tileK: { fontSize: '12.5px', color: chrome.text3 },
  tileV: {
    fontSize: '26px',
    fontWeight: 500,
    letterSpacing: '-0.02em',
    lineHeight: 1.15,
    fontVariantNumeric: 'tabular-nums',
    marginTop: '3px',
  },
  tileN: { fontSize: '11.5px', color: chrome.text3 },
});
