'use client';

/**
 * The button beside every simulation.
 *
 * This is what the whole lab server is for. A student has an editor and a
 * browser; everything a command line was doing — generate from the schema,
 * compile it, run the simulation, bill the trace — happens behind one arrow.
 *
 * **Pressing it does not stay here.** Streaming a build's own output inline,
 * under the row that started it, would conflate "is my simulation right" and
 * "did the last run finish" into the same page, and a student watching text
 * scroll is not looking at a cluster. The build itself is followed by the
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
import { project, type Project, type Simulation } from '../lib/lab.ts';
import * as stylex from '@stylexjs/stylex';

import { chrome, font, radius, shadow } from '../lib/tokens.stylex.ts';

export function Lab({
  onEdit,
}: {
  /** Load an existing simulation's file back into the console for editing. */
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

  // `watching` catches a simulation written from the form above; `building`
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
    <section {...stylex.props(sty.lab)}>
      <div {...stylex.props(sty.rows)}>
        {lab.simulations.length === 0 && (
          <div {...stylex.props(sty.row, sty.lastRow)}>
            <span {...stylex.props(sty.empty)}>
              No simulations yet. Create a simulation to list it here.
            </span>
          </div>
        )}
        {lab.simulations.map((sc: Simulation, i: number) => {
          const running = building?.simulation === sc.name;
          return (
            <div key={sc.name} {...stylex.props(sty.row, running && sty.running, i === lab.simulations.length - 1 && sty.lastRow)}>
              <button
                {...stylex.props(sty.go)}
                disabled={!lab.started || building != null}
                onClick={() => press(sc.name)}
                title={
                  !lab.started
                    ? 'This lab has no runnable code yet.'
                    : building != null
                      ? `${building.simulation} is running`
                      : `Run ${sc.name}`
                }
                aria-label={`Run ${sc.name}`}
              >
                {running ? '...' : '>'}
              </button>

              <span {...stylex.props(sty.id)}>{sc.name}</span>
              <span {...stylex.props(sty.facts)}>{sc.path}</span>

              <button
                {...stylex.props(sty.seen)}
                disabled={building != null}
                onClick={() => onEdit(sc.name)}
              >
                Edit
              </button>

              {sc.trace && (
                <button
                  {...stylex.props(sty.seen)}
                  disabled={building != null}
                  onClick={() =>
                    void openAt(
                      sc.trace!.replace(/^traces\//, '').replace(/\.json$/, ''),
                      sc.trace!,
                      'overview',
                    )
                  }
                >
                  Last run
                </button>
              )}
            </div>
          );
        })}
      </div>

    </section>
  );
}

const sty = stylex.create({
  lab: {
    flex: 'none',
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.base,
    boxShadow: shadow.s1,
    overflow: 'hidden',
  },
  rows: { display: 'flex', flexDirection: 'column' },
  row: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    paddingBlock: '6px',
    paddingInline: '10px',
    borderBottomWidth: '1px',
    borderBottomStyle: 'solid',
    borderBottomColor: chrome.border,
    fontSize: '12.5px',
  },
  /** `:last-child` had to ask the DOM; the list knows which row is last. */
  lastRow: { borderBottomWidth: 0 },
  running: { backgroundColor: chrome.accentSoft },
  go: {
    flex: 'none',
    width: '24px',
    height: '24px',
    borderRadius: radius.sm,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: { default: chrome.borderStrong, ':hover': 'transparent', ':disabled': chrome.borderStrong },
    backgroundColor: { default: chrome.surface2, ':hover': chrome.accent, ':disabled': chrome.surface2 },
    color: { default: chrome.accent, ':hover': '#fff', ':disabled': chrome.accent },
    fontSize: '11px',
    lineHeight: 1,
    cursor: { default: 'pointer', ':disabled': 'default' },
    opacity: { default: 1, ':disabled': 0.35 },
  },
  id: { fontFamily: font.mono, fontWeight: 600 },
  facts: { marginLeft: 'auto', color: chrome.text3, fontSize: '11.5px', fontFamily: font.mono },
  empty: { color: chrome.text3, fontSize: '12.5px' },
  seen: {
    flex: 'none',
    paddingBlock: '2px',
    paddingInline: '8px',
    borderRadius: radius.sm,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: { default: chrome.borderStrong, ':hover': chrome.text3, ':disabled': chrome.borderStrong },
    backgroundColor: 'transparent',
    color: { default: chrome.text2, ':hover': chrome.text, ':disabled': chrome.text2 },
    fontSize: '11.5px',
    cursor: { default: 'pointer', ':disabled': 'default' },
    opacity: { default: 1, ':disabled': 0.35 },
  },
});
