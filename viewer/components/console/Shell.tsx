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

import { Transport } from './Transport.tsx';
import { TIMED, useConsole, type View } from '../../lib/console.tsx';

interface Item {
  id: View;
  label: string;
  icon: string;
  tag?: string;
}

export function Shell({ children }: { children: ReactNode }) {
  const { runs, run, clock, view, go, hasLab, error, busy, openDropped, setError } = useConsole();

  const lab: Item[] = [
    { id: 'runs', label: 'Runs', icon: '▤', tag: String(runs.length || '') },
    // Only with a lab behind the page: the designer reads its classes off what
    // the project compiles to, and there is nothing to read without one.
    ...(hasLab
      ? [{ id: 'design' as View, label: 'Design', icon: '✎' },
         { id: 'systems' as View, label: 'Systems', icon: '⌗' }]
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
      className="console"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        const file = e.dataTransfer.files[0];
        if (file) void openDropped(file);
      }}
    >
      <header className="bar">
        <span className="brand">losim</span>
        <span className="svc">Decentralized Systems Lab</span>
        <span className="grow" />
        {run && <span className="chip dark">{String(run.trace.meta['scenario'] ?? run.name)}</span>}
        <span className="chip dark">{String(run?.bill?.rates?.['region'] ?? 'eu-central-1')}</span>
      </header>

      <div className="body">
        <nav className="rail" aria-label="console">
          <p className="grp">Lab</p>
          <ul>
            {lab.map((n) => (
              <li key={n.id}>
                <button aria-current={view === n.id} onClick={() => go(n.id)}>
                  <i>{n.icon}</i>
                  {n.label}
                  {n.tag && <span className="tag">{n.tag}</span>}
                </button>
              </li>
            ))}
          </ul>

          <p className="grp">
            The open run
            {run && <span className="of">{run.name}</span>}
          </p>
          <ul>
            {open.map((n) => (
              <li key={n.id}>
                <button aria-current={view === n.id} disabled={!run} onClick={() => go(n.id)}>
                  <i>{n.icon}</i>
                  {n.label}
                </button>
              </li>
            ))}
          </ul>

          <p className="fine">
            Drop a trace anywhere on this window to open it — a run from anybody, on any
            machine, reads the same way.
          </p>
          <p className="fine links">
            <a href="./spikes/s1/">glyphs</a>
            <a href="./spikes/s4/">recorder</a>
          </p>
        </nav>

        <div className="pane">
          {/* One clock, above every view that has time in it. Sticky, because a
              cost report is two screens long and the cursor has to stay in reach. */}
          {run && clock && TIMED.has(view) && <Transport run={run} clock={clock} />}

          <main>
            {error && (
              <div className="err" role="alert">
                <span>{error}</span>
                <button className="btn" onClick={() => setError(null)}>dismiss</button>
              </div>
            )}
            {busy && !run ? <div className="wait">opening…</div> : children}
          </main>
        </div>
      </div>

      <style>{`
        .console { min-height: 100vh; display: flex; flex-direction: column; }

        .bar {
          display: flex; align-items: center; gap: 12px; height: 56px;
          padding: 0 20px; flex: none;
          background: var(--bar); color: var(--bar-ink);
        }
        .bar .brand { font-size: 17px; font-weight: 600; letter-spacing: -0.02em; }
        .bar .svc { font-size: 13.5px; color: var(--bar-dim); }
        .bar .grow { flex: 1; }
        .chip.dark {
          background: rgba(255,255,255,0.09); border-color: rgba(255,255,255,0.14);
          color: var(--bar-dim); height: 26px;
        }

        .body { display: grid; grid-template-columns: 244px minmax(0, 1fr); flex: 1; min-height: 0; }

        .rail { padding: 14px 0 24px; border-right: 1px solid var(--border); }
        .rail .grp {
          margin: 18px 0 6px; padding: 0 24px;
          font-size: 12px; font-weight: 500; color: var(--text-3); max-width: none;
        }
        .rail .grp .of {
          display: block; font-family: var(--mono); font-size: 11.5px; color: var(--text-3);
          opacity: 0.75; margin-top: 2px; overflow: hidden; text-overflow: ellipsis;
          white-space: nowrap;
        }
        .rail ul { list-style: none; margin: 0; padding: 0 8px 0 0; }
        .rail button {
          display: flex; align-items: center; gap: 12px; width: 100%;
          height: 40px; padding: 0 16px 0 24px;
          font: inherit; font-size: 14px; color: var(--text-2); text-align: left;
          background: none; border: 0; border-radius: 0 999px 999px 0; cursor: pointer;
          transition: background 0.1s ease, color 0.1s ease;
        }
        .rail button:hover:not(:disabled) { background: var(--surface-2); color: var(--text); }
        .rail button[aria-current='true'] {
          background: var(--accent-soft); color: var(--accent); font-weight: 500;
        }
        .rail button:disabled { color: var(--text-3); opacity: 0.55; cursor: default; }
        .rail button i { font-style: normal; width: 18px; text-align: center; opacity: 0.9; }
        .rail button .tag {
          margin-left: auto; font-family: var(--mono); font-size: 11.5px; color: var(--text-3);
          font-weight: 400;
        }
        .rail .fine {
          margin: 28px 24px 0; font-size: 11.5px; line-height: 1.55; color: var(--text-3);
        }
        .rail .links { display: flex; gap: 14px; margin-top: 10px; }

        .pane { display: flex; flex-direction: column; min-width: 0; }
        .pane main {
          flex: 1; min-width: 0;
          padding: 24px 28px 72px;
          display: flex; flex-direction: column; gap: 20px;
        }

        .err {
          display: flex; align-items: center; gap: 12px;
          padding: 12px 16px; border-radius: var(--r);
          background: #fdeceb; color: #8f231c; border: 1px solid #f3c9c5; font-size: 13px;
        }
        @media (prefers-color-scheme: dark) {
          .err { background: #2c1512; color: #f0b3ad; border-color: #4a221d; }
        }
        .wait { padding: 60px; text-align: center; color: var(--text-3); }

        @media (max-width: 900px) {
          .body { grid-template-columns: 1fr; }
          .rail { display: none; }
          .pane main { padding: 18px 16px 56px; }
        }
      `}</style>
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
    <div className="c-head">
      {crumbs && <p className="c-crumbs">{crumbs}</p>}
      <div className="c-row">
        <div>
          <h1>{title}</h1>
          {sub && <p className="c-sub">{sub}</p>}
        </div>
        {actions && <div className="c-acts">{actions}</div>}
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
    <section className="c-panel card">
      {(title || actions) && (
        <header>
          <h2>{title}</h2>
          {note && <span className="c-note">{note}</span>}
          {actions && <span className="c-acts">{actions}</span>}
        </header>
      )}
      <div className={flush ? 'c-in flush' : 'c-in'}>{children}</div>
    </section>
  );
}

/** One number, said once. */
export function Tile({ k, v, n }: { k: string; v: ReactNode; n?: ReactNode }) {
  return (
    <div className="c-tile card">
      <span className="k">{k}</span>
      <span className="v">{v}</span>
      {n && <span className="n">{n}</span>}
    </div>
  );
}
