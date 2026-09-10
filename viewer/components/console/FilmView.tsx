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
import * as stylex from '@stylexjs/stylex';
import { useCallback, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { Film } from '../Film.tsx';
import { useConsole } from '../../lib/console.tsx';
import { refTime } from '../../lib/playback.ts';
import { openUrl, type Run } from '../../lib/runs.ts';
import { A } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome } from '../../lib/tokens.stylex.ts';

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
            the bar under the picture, or drag it. This film keeps its own clock — Overview,
            Usage and Cost have theirs, and moving one does not move the other.
          </>
        }
        actions={
          <select
            {...stylex.props(ui.picker)}
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
        <div {...stylex.props(sx.stage)}>
          {busy && <div {...stylex.props(sx.over)}>opening…</div>}
          <Film key={run.name + (against?.name ?? '')} run={run} against={against} transport />
        </div>
      </Panel>

    </>
  );
}

const sx = stylex.create({
  stage: {
    position: 'relative',
    display: 'flex',
    flexDirection: 'column',
    height: 'clamp(460px, calc(100vh - 300px), 900px)',
    paddingTop: 0,
    paddingInline: '20px',
    paddingBottom: '20px',
  },
  /** Over the film while another run is being read, not instead of it. */
  over: {
    position: 'absolute',
    inset: 0,
    display: 'grid',
    placeItems: 'center',
    backgroundColor: `color-mix(in srgb, ${chrome.surface} 78%, transparent)`,
    color: chrome.text3,
    zIndex: 2,
  },
});
