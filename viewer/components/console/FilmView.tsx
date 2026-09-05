'use client';

/**
 * The film, on the console's clock.
 *
 * This page renders the same component that draws the film elsewhere, but
 * gives it a definite height and hands it the clock from above instead of
 * letting it make its own — which is why the cost report two tabs away can
 * be at the same instant as the picture.
 */
import { useCallback, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { Film } from '../Film.tsx';
import { useConsole } from '../../lib/console.tsx';
import { refTime } from '../../lib/playback.ts';
import { openUrl, type Run } from '../../lib/runs.ts';

export function FilmView() {
  const { run, runs, clock, go } = useConsole();
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

  if (!run || !clock) return null;

  return (
    <>
      <Head
        crumbs={
          <>
            <a href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</a>
            {' / '}
            <a href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</a>
            {' / Film'}
          </>
        }
        title="Film"
        sub={
          <>
            {run.trace.machines.length} machines over {refTime(run.trace.duration)}. Press play on
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
          <Film key={run.name + (against?.name ?? '')} run={run} against={against} clock={clock} transport={false} />
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
