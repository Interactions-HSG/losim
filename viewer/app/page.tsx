'use client';

/**
 * Console shell and active view.
 *
 * The static export keeps the active view in state because route navigation
 * would unmount the clock.
 */
import * as stylex from '@stylexjs/stylex';

import { Cost } from '../components/console/Cost.tsx';
import { Simulations } from '../components/console/Simulations.tsx';
import { FilmView } from '../components/console/FilmView.tsx';
import { Gallery } from '../components/console/Gallery.tsx';
import { Overview } from '../components/console/Overview.tsx';
import { Shell } from '../components/console/Shell.tsx';
import { Usage } from '../components/console/Usage.tsx';
import { ConsoleProvider, useConsole } from '../lib/console.tsx';

export default function Home() {
  return (
    <ConsoleProvider>
      <Shell>
        <View />
      </Shell>
    </ConsoleProvider>
  );
}

function View() {
  const { view, run } = useConsole();
  return (
    <>
      {/* Keep Simulations mounted so it can detect a lab and follow active runs. */}
      <div {...stylex.props(sx.host, view !== 'simulations' && sx.away)} hidden={view !== 'simulations'}>
        <Simulations />
      </div>

      {view === 'runs' && <Gallery />}
      {run && view === 'overview' && <Overview />}
      {run && view === 'film' && <FilmView />}
      {run && view === 'usage' && <Usage />}
      {run && view === 'cost' && <Cost />}
    </>
  );
}

const sx = stylex.create({
  host: { display: 'flex', flexDirection: 'column', gap: '20px' },
  /**
   * `hidden` remains on the element for document readers. StyleX has no
   * attribute selectors, so this display rule stays beside the condition.
   */
  away: { display: 'none' },
});
