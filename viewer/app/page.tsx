'use client';

/**
 * The console.
 *
 * One shell, one clock, and five views of the same run. Which view is on is
 * state rather than a route — `lib/console.tsx` says why, and the short version
 * is that this app is a static export served from whatever directory it lands
 * in, and that a real navigation would unmount the clock every time you changed
 * tab.
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
      {/* Mounted whatever else is on screen, so the console finds out whether
          there is a lab behind this page at all — and hidden rather than
          unmounted, so a run started here goes on being followed while you look
          at something else. */}
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
   * `hidden` is still on the element, for anything reading the page rather than
   * looking at it. The display rule is written beside it rather than as
   * `.host[hidden]`, because StyleX has no attribute selectors — and a component
   * that already knows why it is hidden does not need one to find out.
   */
  away: { display: 'none' },
});
