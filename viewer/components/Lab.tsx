'use client';

/**
 * The button beside every system.
 *
 * This is what the whole lab server is for. A student has an editor and a
 * browser; everything a command line was doing — generate from the schema,
 * compile it, run the scenario, bill the trace — happens behind one arrow, and
 * the output of the run appears under it while it is going rather than at the
 * end, because the interesting part of a run that hangs is what it printed
 * before it stopped.
 *
 * **A system with variants gets a choice, not two buttons.** `main.yaml` beside
 * `chaos.yaml` is the same code against a crueller afternoon, and the two are
 * two runs in the picker to be compared — so the world is a select and the arrow
 * stays one arrow.
 *
 * **It is absent when there is no lab.** The same exported application serves
 * the gallery and any trace anybody was sent, and in those there is nothing to
 * run and nothing here says otherwise.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import { output, project, run, type Output, type System } from '../lib/lab.ts';

/** How often to ask what the run has said. Slow enough to be free, fast enough to read. */
const POLL_MS = 400;

export function Lab({
  onLab,
  onRan,
}: {
  /** Whether this page has a lab behind it at all, so the chrome can know. */
  onLab: (present: boolean) => void;
  /** A run finished and wrote a trace: open it. */
  onRan: (name: string, href: string) => void;
}) {
  const [systems, setSystems] = useState<System[] | null>(null);
  const [world, setWorld] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [log, setLog] = useState('');
  const [error, setError] = useState<string | null>(null);
  const tail = useRef<HTMLPreElement>(null);

  const look = useCallback(async () => {
    const found = await project();
    onLab(found != null);
    if (!found) return null;
    setSystems(found.systems);
    setBusy(found.busy);
    return found;
  }, [onLab]);

  useEffect(() => {
    void look();
  }, [look]);

  /**
   * Follow the run that is going, wherever it was started from.
   *
   * The page that started a run is not necessarily the page reading it: a
   * Codespace reconnects and a browser is refreshed, and the run carries on. So
   * this attaches to whatever the server says is running rather than only to
   * what this tab asked for — and it asks from 0, because the output so far is
   * the part a reconnecting student has not seen.
   */
  useEffect(() => {
    if (!busy) return;
    let live = true;
    let at = 0;
    let timer: ReturnType<typeof setTimeout>;

    const pull = async () => {
      const said: Output | null = await output(at);
      if (!live) return;
      if (!said) {
        setError('the lab stopped answering — is `losim serve` still running?');
        setBusy(null);
        return;
      }
      if (said.text) setLog((was) => was + said.text);
      at = said.next;
      if (!said.done) {
        timer = setTimeout(pull, POLL_MS);
        return;
      }
      setBusy(null);
      // The list is asked again rather than patched: a finished run may have
      // written a trace, and what is on disk is the only fact that cannot go
      // stale.
      const found = await look();
      if (!live) return;
      if (said.trace) {
        onRan(said.trace.replace(/^traces\//, '').replace(/\.json$/, ''), said.trace);
      } else if (said.ok === false) {
        setError(`${said.system ?? 'that run'} did not finish — the output says why.`);
      }
      // A run with no scenario has no trace and no film; its output is the
      // whole result, so it is left on screen.
      if (!found) return;
    };

    void pull();
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [busy, look, onRan]);

  useEffect(() => {
    const el = tail.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [log]);

  const press = useCallback(
    async (s: System) => {
      setError(null);
      setLog('');
      const chosen = s.scenarios.length > 1 ? (world[s.id] ?? s.scenarios[0]) : undefined;
      const said = await run(s.id, chosen);
      if (said.error) {
        setError(said.error);
        return;
      }
      setBusy(s.id);
    },
    [world],
  );

  if (!systems) return null;

  return (
    <section className="lab">
      <div className="rows">
        {systems.map((s) => {
          const running = busy === s.id;
          return (
            <div key={s.id} className={`row${running ? ' running' : ''}`}>
              <button
                className="go"
                disabled={!s.started || busy != null}
                onClick={() => press(s)}
                title={
                  !s.started
                    ? 'there is no code in this one yet — that is the exercise'
                    : busy != null
                      ? `${busy} is running`
                      : `build and run ${s.id}`
                }
                aria-label={`run ${s.id}`}
              >
                {running ? '…' : '▶'}
              </button>

              <span className="id">{s.id}</span>

              {s.scenarios.length > 1 ? (
                <select
                  className="world"
                  value={world[s.id] ?? s.scenarios[0]}
                  disabled={busy != null}
                  onChange={(e) => setWorld((w) => ({ ...w, [s.id]: e.target.value }))}
                  aria-label={`which world for ${s.id}`}
                >
                  {s.scenarios.map((w) => (
                    <option key={w} value={w}>
                      {w}
                    </option>
                  ))}
                </select>
              ) : (
                <span className="world one">{s.scenarios[0] ?? 'one machine'}</span>
              )}

              <span className="facts">
                {!s.started ? (
                  <em>nothing written yet</em>
                ) : (
                  <>
                    {s.files} file{s.files === 1 ? '' : 's'}
                    {s.schema ? ' · schema' : ''}
                  </>
                )}
              </span>

              {s.trace && (
                <button
                  className="seen"
                  disabled={busy != null}
                  onClick={() =>
                    onRan(s.trace!.replace(/^traces\//, '').replace(/\.json$/, ''), s.trace!)
                  }
                >
                  last run
                </button>
              )}
            </div>
          );
        })}
      </div>

      {error && <div className="err">{error}</div>}
      {(busy || log) && (
        <pre className="tail" ref={tail}>
          {log || 'starting…'}
        </pre>
      )}

      <style>{`
        .lab {
          flex: none; background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--r); box-shadow: var(--shadow-1); overflow: hidden;
        }
        .rows { display: flex; flex-direction: column; }
        .row {
          display: flex; align-items: center; gap: 10px;
          padding: 6px 10px; border-bottom: 1px solid var(--border);
          font-size: 12.5px;
        }
        .row:last-child { border-bottom: 0; }
        .row.running { background: var(--accent-soft); }

        .go {
          flex: none; width: 24px; height: 24px; border-radius: var(--r-sm);
          border: 1px solid var(--border-strong); background: var(--surface-2);
          color: var(--accent); font-size: 11px; line-height: 1; cursor: pointer;
        }
        .go:hover:not(:disabled) { background: var(--accent); color: #fff; border-color: transparent; }
        .go:disabled { opacity: 0.35; cursor: default; }

        .id { font-family: var(--mono); font-weight: 600; }
        .world { font-size: 12px; color: var(--text-2); }
        .world.one { font-family: var(--mono); font-size: 11.5px; color: var(--text-3); }
        .facts { margin-left: auto; color: var(--text-3); font-size: 11.5px; }
        .facts em { font-style: normal; }

        .seen {
          flex: none; padding: 2px 8px; border-radius: var(--r-sm);
          border: 1px solid var(--border-strong); background: transparent;
          color: var(--text-2); font-size: 11.5px; cursor: pointer;
        }
        .seen:hover:not(:disabled) { color: var(--text); border-color: var(--text-3); }
        .seen:disabled { opacity: 0.35; cursor: default; }

        .err {
          padding: 7px 10px; border-top: 1px solid var(--border);
          color: var(--danger); font-size: 12.5px;
        }
        .tail {
          margin: 0; padding: 8px 10px; max-height: 220px; overflow: auto;
          border-top: 1px solid var(--border); background: var(--surface-2);
          font-family: var(--mono); font-size: 11.5px; line-height: 1.5;
          white-space: pre-wrap; color: var(--text-2);
        }
      `}</style>
    </section>
  );
}
