'use client';

/**
 * The button beside every scenario.
 *
 * This is what the whole lab server is for. A student has an editor and a
 * browser; everything a command line was doing — generate from the schema,
 * compile it, run the scenario, bill the trace — happens behind one arrow.
 *
 * **Pressing it does not stay here.** Streaming a build's own output inline,
 * under the row that started it, would conflate "is my scenario right" and
 * "did the last run finish" into the same page, and a student watching text
 * scroll is not looking at a fleet. The build itself is followed by the
 * console, not by this component, so it outlives whichever page you are on;
 * this only starts it and moves you to Runs, where the trace lands when it is
 * ready.
 *
 * **It is absent when there is no lab.** The same exported application serves
 * the gallery and any trace anybody was sent, and in those there is nothing to
 * run and nothing here says otherwise.
 */
import { useCallback, useEffect, useState } from 'react';

import { useConsole } from '../lib/console.tsx';
import { project, type Project, type Scenario } from '../lib/lab.ts';

export function Lab({
  onEdit,
}: {
  /** Load an existing scenario's file back into the console for editing. */
  onEdit: (name: string) => void;
}) {
  const { setHasLab, openAt, watching, building, startBuild, go } = useConsole();
  const [lab, setLab] = useState<Project | null>(null);

  const look = useCallback(async () => {
    const found = await project();
    setHasLab(found != null);
    if (found) setLab(found);
    return found;
  }, [setHasLab]);

  // `watching` catches a scenario written from the form above; `building`
  // catches the run that just finished — the trace it wrote is what turns the
  // row's "last run" button on.
  useEffect(() => {
    void look();
  }, [look, watching, building]);

  const press = useCallback(
    async (name: string) => {
      await startBuild(name);
      go('runs');
    },
    [startBuild, go],
  );

  if (!lab) return null;

  return (
    <section className="lab">
      <div className="rows">
        {lab.scenarios.length === 0 && (
          <div className="row">
            <span className="empty">
              No scenarios yet. Write one above and it appears here.
            </span>
          </div>
        )}
        {lab.scenarios.map((sc: Scenario) => {
          const running = building?.scenario === sc.name;
          return (
            <div key={sc.name} className={`row${running ? ' running' : ''}`}>
              <button
                className="go"
                disabled={!lab.started || building != null}
                onClick={() => press(sc.name)}
                title={
                  !lab.started
                    ? 'there is no code in this lab yet — that is the exercise'
                    : building != null
                      ? `${building.scenario} is running`
                      : `run ${sc.name}`
                }
                aria-label={`run ${sc.name}`}
              >
                {running ? '…' : '▶'}
              </button>

              <span className="id">{sc.name}</span>
              <span className="facts">{sc.path}</span>

              <button
                className="seen"
                disabled={building != null}
                onClick={() => onEdit(sc.name)}
              >
                edit
              </button>

              {sc.trace && (
                <button
                  className="seen"
                  disabled={building != null}
                  onClick={() =>
                    void openAt(
                      sc.trace!.replace(/^traces\//, '').replace(/\.json$/, ''),
                      sc.trace!,
                      'overview',
                    )
                  }
                >
                  last run
                </button>
              )}
            </div>
          );
        })}
      </div>

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
        .facts { margin-left: auto; color: var(--text-3); font-size: 11.5px; font-family: var(--mono); }
        .empty { color: var(--text-3); font-size: 12.5px; }

        .seen {
          flex: none; padding: 2px 8px; border-radius: var(--r-sm);
          border: 1px solid var(--border-strong); background: transparent;
          color: var(--text-2); font-size: 11.5px; cursor: pointer;
        }
        .seen:hover:not(:disabled) { color: var(--text); border-color: var(--text-3); }
        .seen:disabled { opacity: 0.35; cursor: default; }
      `}</style>
    </section>
  );
}
