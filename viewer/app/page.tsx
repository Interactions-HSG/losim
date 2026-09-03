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
import { Cost } from '../components/console/Cost.tsx';
import { Scenarios } from '../components/console/Scenarios.tsx';
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
      {/* Mounted whatever else is on screen, so the console finds out whether
          there is a lab behind this page at all — and hidden rather than
          unmounted, so a run started here goes on being followed while you look
          at something else. */}
      <div className="host" hidden={view !== 'scenarios'}>
        <Scenarios />
      </div>

      {view === 'runs' && <Gallery />}
      {run && view === 'overview' && <Overview />}
      {run && view === 'film' && <FilmView />}
      {run && view === 'usage' && <Usage />}
      {run && view === 'cost' && <Cost />}

      <style>{`
        .host { display: flex; flex-direction: column; gap: 20px; }
        .host[hidden] { display: none; }
      `}</style>
    </>
  );
}
