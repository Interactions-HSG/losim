'use client';

/**
 * The film, on a clock of its own.
 *
 * This page renders the same component that draws the film elsewhere and gives
 * it a definite height. The clock is the film's own, because the film is the one
 * view whose clock is not linear: it slows down where something short is
 * happening, so that a three-millisecond call is on screen long enough to see.
 * A chart of load against time has nothing that flickers past and should not
 * pay for that — on the rest of the console a reference second takes a second.
 */
import { useCallback, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { Film } from '../Film.tsx';
import { useConsole } from '../../lib/console.tsx';
import { refTime } from '../../lib/playback.ts';
import { openUrl, type Run } from '../../lib/runs.ts';
import { A } from '../../lib/text.tsx';

export function FilmView() {
  const { run, runs, go } = useConsole();
  /**
   * A second run, on the same clock.
   *
   * `mr-locality` against `mr-locality-blind` is an argument that ends itself —
   * but only if both are watched at the same instant, because the whole claim is
   * about *when* things happen.
   */
  const [against, setAgainst] = useState<Run | null>(null);
  const [busy, setBusy] = useState(false);

  const compare = useCallback(
    async (name: string) => {
      if (!name) {
        setAgainst(null);
        return;
      }
      const ref = runs.find((r) => r.name === name);
      if (!ref) return;
      setBusy(true);
      try {
        setAgainst(await openUrl(ref.name, ref.href));
      } finally {
        setBusy(false);
      }
    },
    [runs],
  );

  if (!run) return null;

  return (
    <>
      <Head
        crumbs={
          <>
            <A href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</A>
            {' / '}
            <A href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</A>
            {' / Film'}
          </>
        }
        title="Film"
        sub={
          <>
            {run.trace.nodes.length} nodes over {refTime(run.trace.duration)}. Press play on
            the bar above, or drag it — the execution graph, the usage charts and the cost report
            are all at whatever instant it is showing.
          </>
        }
        actions={
          <select
            className="picker"
            value={against?.name ?? ''}
            onChange={(e) => void compare(e.target.value)}
            aria-label="compare with"
            title="watch a second run on the same clock"
          >
            <option value="">compare with…</option>
            {runs
              .filter((r) => r.name !== run.name)
              .map((r) => (
                <option key={r.name} value={r.name}>
                  {r.name}
                </option>
              ))}
          </select>
        }
      />

      <Panel flush>
        <div className="c-stage">
          {busy && <div className="c-over">opening…</div>}
          <Film key={run.name + (against?.name ?? '')} run={run} against={against} transport />
        </div>
      </Panel>

      <style>{`
        .c-stage {
          position: relative;
          display: flex; flex-direction: column;
          height: clamp(460px, calc(100vh - 300px), 900px);
          padding: 0 20px 20px;
        }
        .c-over {
          position: absolute; inset: 0; display: grid; place-items: center;
          background: color-mix(in srgb, var(--surface) 78%, transparent);
          color: var(--text-3); z-index: 2;
        }
      `}</style>
    </>
  );
}
