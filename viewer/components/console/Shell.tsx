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
import { useEffect, useState, type ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import { bar, chrome, font, radius, shadow, size } from '../../lib/tokens.stylex.ts';
import { ui } from '../../lib/ui.stylex.ts';

import { Transport } from './Transport.tsx';
import { TIMED, useConsole, type View } from '../../lib/console.tsx';
import { H1, H2, P } from '../../lib/text.tsx';

/**
 * Where the rail's state is kept between visits.
 *
 * Read in an effect rather than during render: this app is a static export, so
 * the first paint is prerendered where there is no `localStorage`, and reading
 * it while rendering would be a hydration mismatch. The rail therefore opens
 * and then closes itself, which is one frame and is the correct trade — the
 * alternative is a rail that renders nothing until the browser has spoken.
 *
 * The read is wrapped because a browser set to refuse site data throws on
 * access rather than returning null.
 */
const RAIL_KEY = 'dissaly.rail.collapsed';

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

  const [tight, setTight] = useState(false);
  useEffect(() => {
    try {
      const said = window.localStorage.getItem(RAIL_KEY);
      // No stored preference and no room for a rail beside the page: start with
      // it away. Below 900px the rail is an overlay, and an overlay that is open
      // on arrival is a menu covering the thing you came to read.
      setTight(said === null ? window.innerWidth <= 900 : said === '1');
    } catch {
      /* site data refused; the rail stays open, which is the better default */
    }
  }, []);
  /**
   * Go somewhere, and get the drawer out of the way if it was over the page.
   *
   * Not written to storage: closing it here is what this tap meant, not a
   * preference about rails. Remembering it would collapse the rail on a laptop
   * because somebody once used a phone.
   */
  const pick = (id: View) => {
    go(id);
    try {
      if (window.innerWidth <= 900) setTight(true);
    } catch {
      /* no window to measure; the drawer stays as it is */
    }
  };

  const toggleRail = () => {
    setTight((was) => {
      const now = !was;
      try {
        window.localStorage.setItem(RAIL_KEY, now ? '1' : '0');
      } catch {
        /* nothing to remember it with; the rail still collapses for this visit */
      }
      return now;
    });
  };

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
        <button
          {...stylex.props(styles.hamburger)}
          onClick={toggleRail}
          aria-expanded={!tight}
          aria-controls="console-rail"
          title={tight ? 'show the menu' : 'hide the menu'}
        >
          <span aria-hidden>☰</span>
          <span {...stylex.props(styles.away)}>{tight ? 'show the menu' : 'hide the menu'}</span>
        </button>
        <span {...stylex.props(styles.brand)}>DISSALy</span>
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

      <div {...stylex.props(styles.body, tight && styles.bodyTight)}>
        <nav
          id="console-rail"
          {...stylex.props(styles.rail, tight && styles.railTight)}
          aria-label="console"
        >
          {tight
            ? <hr {...stylex.props(styles.rule, styles.ruleFirst)} />
            : <h2 {...stylex.props(styles.grp, styles.grpFirst)}>Lab</h2>}
          <ul {...stylex.props(styles.list)}>
            {lab.map((n) => (
              <li key={n.id}>
                <button
                  {...stylex.props(styles.nav, tight && styles.navTight, view === n.id && styles.navOn)}
                  aria-current={view === n.id}
                  aria-label={n.label}
                  title={tight ? n.label : undefined}
                  onClick={() => pick(n.id)}
                >
                  <i {...stylex.props(styles.icon)}>{n.icon}</i>
                  {!tight && n.label}
                  {!tight && n.tag && <span {...stylex.props(styles.tag)}>{n.tag}</span>}
                </button>
              </li>
            ))}
          </ul>

          {tight
            ? <hr {...stylex.props(styles.rule)} />
            : (
              <h2 {...stylex.props(styles.grp)}>
                The open run
                {run && <span {...stylex.props(styles.of)}>{run.name}</span>}
              </h2>
            )}
          <ul {...stylex.props(styles.list)}>
            {open.map((n) => (
              <li key={n.id}>
                <button
                  {...stylex.props(
                    styles.nav,
                    tight && styles.navTight,
                    view === n.id && styles.navOn,
                    !run && styles.navOff,
                  )}
                  aria-current={view === n.id}
                  aria-label={n.label}
                  title={tight ? n.label : undefined}
                  disabled={!run}
                  onClick={() => pick(n.id)}
                >
                  <i {...stylex.props(styles.icon)}>{n.icon}</i>
                  {!tight && n.label}
                </button>
              </li>
            ))}
          </ul>

          {!tight && (
            <p {...stylex.props(styles.fine)}>
              Drop a trace anywhere on this window to open it — a run from anybody, on any
              node, reads the same way.
            </p>
          )}
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
      {crumbs && <P style={styles.crumbs}>{crumbs}</P>}
      <div {...stylex.props(styles.headRow)}>
        <div>
          <H1 style={styles.h1}>{title}</H1>
          {sub && <P style={styles.sub}>{sub}</P>}
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
          <H2 style={styles.h2}>{title}</H2>
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
  /**
   * The rail's switch, in the bar rather than in the rail — a control that hides
   * something has to stay put when that thing is hidden.
   */
  hamburger: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '30px',
    height: '30px',
    marginLeft: '-6px',
    marginRight: '2px',
    font: 'inherit',
    fontSize: '15px',
    lineHeight: 1,
    color: bar.ink,
    backgroundColor: { default: 'transparent', ':hover': 'rgba(255,255,255,0.14)' },
    borderWidth: 0,
    borderRadius: radius.sm,
    cursor: 'pointer',
    transitionProperty: 'background-color',
    transitionDuration: '0.1s',
    outline: { default: null, ':focus-visible': `2px solid ${bar.ink}` },
    outlineOffset: '1px',
  },
  /** Read aloud, never drawn: the button's own word for what it is about to do. */
  away: {
    position: 'absolute',
    width: '1px',
    height: '1px',
    padding: 0,
    margin: '-1px',
    overflow: 'hidden',
    clipPath: 'inset(50%)',
    whiteSpace: 'nowrap',
    borderWidth: 0,
  },
  brand: { fontSize: '18px', fontWeight: 600, letterSpacing: '-0.02em' },
  // The title is long enough to wrap, and a wrapped one puts "Lab" under the
  // sidebar heading where it reads as a stray word.
  svc: { fontSize: size.md, color: bar.dim, whiteSpace: 'nowrap' },
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
    gridTemplateColumns: { default: '248px minmax(0, 1fr)', [NARROW]: '1fr' },
    transitionProperty: 'grid-template-columns',
    transitionDuration: '0.14s',
    transitionTimingFunction: 'ease',
    flex: 1,
    minHeight: 0,
  },
  /** Collapsed: wide enough for an icon and its target, and nothing else. */
  bodyTight: { gridTemplateColumns: { default: '56px minmax(0, 1fr)', [NARROW]: '1fr' } },
  rail: {
    paddingTop: '10px',
    paddingBottom: '20px',
    paddingInline: '10px',
    borderRightWidth: '1px',
    borderRightStyle: 'solid',
    borderRightColor: chrome.border,
    backgroundColor: chrome.surface,
    display: 'flex',
    flexDirection: 'column',
    overflowY: 'auto',
    overflowX: 'hidden',
    /**
     * Below 900px there is no column to put it in, so it stops being a column
     * and becomes a drawer over the page. It used to be `display: none` here,
     * which left a phone with a ☰ that toggled nothing and no way to reach any
     * view but the one already open.
     */
    position: { default: 'static', [NARROW]: 'fixed' },
    top: { default: null, [NARROW]: '56px' },
    bottom: { default: null, [NARROW]: 0 },
    left: { default: null, [NARROW]: 0 },
    width: { default: 'auto', [NARROW]: '248px' },
    maxWidth: { default: 'none', [NARROW]: '80vw' },
    zIndex: { default: null, [NARROW]: 30 },
    boxShadow: { default: 'none', [NARROW]: shadow.s2 },
  },
  /** Away. Wide, that is 56px of icons; narrow, it is the drawer shut. */
  railTight: {
    paddingInline: '8px',
    display: { default: 'flex', [NARROW]: 'none' },
  },
  /**
   * What a group heading becomes when there is no room to read one. A rule
   * rather than a truncated word: the grouping is still there to be seen, and
   * nothing pretends to be a label it is too narrow to be.
   */
  rule: {
    width: '100%',
    height: '1px',
    borderWidth: 0,
    backgroundColor: chrome.border,
    marginTop: '14px',
    marginBottom: '10px',
  },
  ruleFirst: { marginTop: '6px' },
  /**
   * A group heading, in the app's own heading idiom rather than a paragraph that
   * happened to be grey: small, upper case, tracked out. It is padded to the
   * same inline edge as the label of the items under it — the old one lined up
   * with their icons instead, which read as an indent nobody had asked for.
   */
  grp: {
    marginTop: '18px',
    marginRight: 0,
    marginBottom: '4px',
    marginLeft: 0,
    paddingInline: '10px',
    fontSize: '11.5px',
    fontWeight: 600,
    letterSpacing: '0.07em',
    textTransform: 'uppercase',
    color: chrome.text3,
  },
  grpFirst: { marginTop: '4px' },
  /** The run's own name under the heading, which is data and is set as data. */
  of: {
    display: 'block',
    fontFamily: font.mono,
    fontSize: size.xs,
    letterSpacing: 0,
    textTransform: 'none',
    fontWeight: 400,
    color: chrome.text2,
    marginTop: '3px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  list: { listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '1px' },

  /**
   * `:disabled` is written after `:hover` so a disabled item the pointer is over
   * stays looking disabled — the old CSS said that with `:hover:not(:disabled)`.
   * Which item is current is a prop rather than an attribute selector, because
   * the component already knows.
   */
  nav: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    width: '100%',
    height: '38px',
    paddingBlock: 0,
    paddingInline: '10px',
    font: 'inherit',
    fontSize: size.base,
    color: { default: chrome.text2, ':hover': chrome.text },
    textAlign: 'left',
    backgroundColor: { default: 'transparent', ':hover': chrome.surface2 },
    borderWidth: 0,
    /**
     * Rounded on both sides and inset from the rail's edge, rather than a half
     * pill bleeding off the left of the window. The item is a thing on a shelf;
     * it should not look like it is falling off it.
     */
    borderRadius: radius.sm,
    cursor: 'pointer',
    transitionProperty: 'background-color, color, box-shadow',
    transitionDuration: '0.1s',
  },
  /**
   * The open view, said twice: tinted, and marked on its leading edge. The mark
   * is an inset shadow rather than a border or a pseudo-element, so it follows
   * the corner radius and costs the layout nothing.
   */
  navOn: {
    backgroundColor: chrome.accentSoft,
    color: chrome.accent,
    fontWeight: 500,
    boxShadow: `inset 3px 0 0 ${chrome.accent}`,
  },
  navTight: { justifyContent: 'center', paddingInline: 0, gap: 0 },
  navOff: { color: chrome.text3, opacity: 0.55, cursor: 'default', backgroundColor: 'transparent' },
  icon: {
    fontStyle: 'normal',
    width: '16px',
    flexGrow: 0,
    flexShrink: 0,
    textAlign: 'center',
    fontSize: size.sm,
    opacity: 0.75,
  },
  /** How many runs there are. A count, so it is set like one and sits in a well. */
  tag: {
    marginLeft: 'auto',
    minWidth: '22px',
    paddingInline: '7px',
    borderRadius: '999px',
    backgroundColor: chrome.surface2,
    fontFamily: font.mono,
    fontSize: size.xs,
    lineHeight: '19px',
    textAlign: 'center',
    color: chrome.text3,
    fontWeight: 400,
  },
  fine: {
    marginTop: 'auto',
    marginRight: 0,
    marginBottom: 0,
    marginLeft: 0,
    paddingTop: '18px',
    paddingInline: '10px',
    fontSize: size.sm,
    lineHeight: 1.5,
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
    fontSize: size.md,
  },
  wait: { padding: '60px', textAlign: 'center', color: chrome.text3 },

  head: { display: 'flex', flexDirection: 'column', gap: '4px' },
  crumbs: { margin: '0 0 2px', fontSize: size.md, color: chrome.text3 },
  headRow: { display: 'flex', alignItems: 'flex-end', gap: '20px', flexWrap: 'wrap' },
  h1: { fontSize: '28px', fontWeight: 400, letterSpacing: '-0.012em', margin: 0 },
  sub: { margin: '4px 0 0', fontSize: size.base, color: chrome.text2, maxWidth: '80ch' },
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
    fontSize: size.lg,
    fontWeight: 500,
    letterSpacing: '-0.008em',
    textTransform: 'none',
    color: chrome.text,
    margin: 0,
  },
  note: { fontSize: size.md, color: chrome.text3 },
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
  tileK: { fontSize: size.md, color: chrome.text3 },
  tileV: {
    fontSize: '26px',
    fontWeight: 500,
    letterSpacing: '-0.02em',
    lineHeight: 1.15,
    fontVariantNumeric: 'tabular-nums',
    marginTop: '3px',
  },
  tileN: { fontSize: size.sm, color: chrome.text3 },
});
