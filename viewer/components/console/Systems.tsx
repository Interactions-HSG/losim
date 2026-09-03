'use client';

/**
 * The project behind the page, when there is one.
 *
 * `losim serve` puts the systems in the project on the same port as this app, so
 * a run can be started from here rather than from a command line. Without a lab
 * behind the page — the gallery, a trace somebody was sent, a static host — this
 * view is not in the rail at all.
 */
import { Head, Panel } from './Shell.tsx';
import { Lab } from '../Lab.tsx';
import { useConsole } from '../../lib/console.tsx';

export function Systems() {
  const { setHasLab, reload, openAt, watching } = useConsole();

  return (
    <>
      <Head
        title="Systems"
        sub={
          <>
            Everything in this project that can be run. Press ▶ beside one and its trace appears
            in Runs when it finishes — and opens here.
          </>
        }
      />
      <Panel>
        <Lab
          watch={watching}
          onLab={setHasLab}
          onRan={async (name, href) => {
            // Opened by where it is rather than by name: the index is asked
            // again too, but a run that finished a second ago is on disk before
            // it is in the list, and it is the run you were waiting for.
            await openAt(name, href, 'overview');
            await reload();
          }}
        />
      </Panel>
    </>
  );
}
