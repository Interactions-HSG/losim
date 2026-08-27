'use client';

/**
 * The app: pick a run, watch it, take it apart.
 *
 * The picker lists whatever the export was built with, and any trace dropped on
 * the page opens the same way — a raw `losim run` trace, with no baking step in
 * between, because the case this exists for is a student pointing it at their
 * own run.
 */
import { useCallback, useEffect, useState } from 'react';

import { Film } from '../components/Film.tsx';
import { manifest, openFile, openUrl, type Run, type RunRef } from '../lib/runs.ts';
import { refTime } from '../lib/playback.ts';

/** Whose runs, in the order somebody looking at their own work wants them. */
const GROUPS = [
  { key: 'yours', label: 'your runs' },
  { key: 'suite', label: 'reference suite' },
  { key: 'gallery', label: 'gallery — worked examples' },
] as const;

export default function Home() {
  const [runs, setRuns] = useState<RunRef[]>([]);
  const [run, setRun] = useState<Run | null>(null);
  /**
   * A second run, on the same clock.
   *
   * `mr-locality` against `mr-locality-blind` is an argument that ends itself —
   * but only if both are watched at the same instant, because the whole claim is
   * about *when* things happen. So the two share one clock rather than being two
   * players that happen to be next to each other.
   */
  const [against, setAgainst] = useState<Run | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [over, setOver] = useState(false);

  useEffect(() => {
    let live = true;
    manifest()
      .then(async (found) => {
        if (!live) return;
        setRuns(found);
        // Open something immediately: an empty viewer cannot be judged, and the
        // first question anybody has is what this looks like.
        //
        // Whatever is first, and never a run named in this file. Naming one
        // would bake a particular scenario into the app, and the run that
        // matters here is always the one whose author is looking at it.
        const asked = new URLSearchParams(window.location.search).get('run');
        // Yours, if you have any. The gallery is worked examples; the run that
        // matters is the one whose author is looking at it.
        const mine = found.filter((r) => r.from === 'yours');
        const first =
          (asked && found.find((r) => r.name === asked)) ?? mine[0] ?? found[0];
        if (first) setRun(await openUrl(first.name, first.href));
        const vs = new URLSearchParams(window.location.search).get('vs');
        const other = vs && found.find((r) => r.name === vs);
        if (other) setAgainst(await openUrl(other.name, other.href));
      })
      .catch((e) => live && setError(String(e.message ?? e)))
      .finally(() => live && setBusy(false));
    return () => {
      live = false;
    };
  }, []);

  const pick = useCallback(
    async (name: string) => {
      const ref = runs.find((r) => r.name === name);
      if (!ref) return;
      setBusy(true);
      setError(null);
      try {
        setRun(await openUrl(ref.name, ref.href));
      } catch (e) {
        setError(String((e as Error).message));
      } finally {
        setBusy(false);
      }
    },
    [runs],
  );

  const compare = useCallback(
    async (name: string) => {
      if (!name) {
        setAgainst(null);
        return;
      }
      const ref = runs.find((r) => r.name === name);
      if (!ref) return;
      setError(null);
      try {
        setAgainst(await openUrl(ref.name, ref.href));
      } catch (e) {
        setError(String((e as Error).message));
      }
    },
    [runs],
  );

  const drop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault();
    setOver(false);
    const file = e.dataTransfer.files[0];
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      setRun(await openFile(file));
    } catch (err) {
      setError(String((err as Error).message));
    } finally {
      setBusy(false);
    }
  }, []);

  return (
    <div
      className={`app${over ? ' over' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={drop}
    >
      <header className="top">
        <h1>losim</h1>
        <select
          className="picker"
          value={run?.name ?? ''}
          onChange={(e) => pick(e.target.value)}
          aria-label="which run"
        >
          {!runs.some((r) => r.name === run?.name) && run && <option value={run.name}>{run.name}</option>}
          {GROUPS.map(({ key, label }) => {
            const some = runs.filter((r) => (r.from ?? 'gallery') === key);
            if (!some.length) return null;
            return (
              <optgroup key={key} label={label}>
                {some.map((r) => (
                  <option key={r.name} value={r.name}>
                    {r.name}
                  </option>
                ))}
              </optgroup>
            );
          })}
        </select>

        {runs.length > 1 && (
          <select
            className="picker vs"
            value={against?.name ?? ''}
            onChange={(e) => compare(e.target.value)}
            aria-label="compare with"
            title="watch a second run on the same clock"
          >
            <option value="">compare with…</option>
            {runs
              .filter((r) => r.name !== run?.name)
              .map((r) => (
                <option key={r.name} value={r.name}>
                  {r.name}
                </option>
              ))}
          </select>
        )}

        {run && (
          <div className="facts">
            <span className="chip">{run.trace.machines.length} machines</span>
            <span className="chip">{new Set(run.trace.machines.map((m) => m.zone)).size} zones</span>
            <span className="chip">{refTime(run.trace.duration)}</span>
            <span className="chip">{run.trace.spans.length} spans</span>
            {run.trace.meta['completed'] === false && (
              <span className="chip bad">did not finish</span>
            )}
          </div>
        )}

        <nav>
          <a href="./spikes/s1/">glyphs</a>
          <a href="./spikes/s4/">recorder</a>
        </nav>
      </header>

      {error && <div className="err">{error}</div>}
      {busy && !run && <div className="loading">opening…</div>}
      {run && <Film key={run.name} run={run} against={against} />}
      {!busy && !run && !error && (
        <div className="loading">
          <p>
            <strong>Nothing has been run yet.</strong> Press the ▶ beside a system, or open{' '}
            <code>RunExperiments.java</code> and press the ▶ above <code>main</code>.
          </p>
          <pre className="mono">{`Experiments.here()
        .run("0-tour/1-two-machines")
        .show();`}</pre>
          <p className="muted">
            Every run you make appears here on its own. Or drop a trace file anywhere on this
            window — a run from anybody, on any machine, opens the same way.
          </p>
        </div>
      )}

      <style>{`
        .app {
          height: 100vh; display: flex; flex-direction: column; gap: 10px;
          padding: 12px 14px 14px;
        }
        .app.over { outline: 2px dashed var(--accent); outline-offset: -8px; }

        .top { display: flex; align-items: center; gap: 10px; flex: none; }
        .top h1 {
          font-size: 14px; font-weight: 700; letter-spacing: -0.02em;
          padding-right: 4px;
        }
        .facts { display: flex; gap: 5px; flex-wrap: wrap; }
        .chip.bad { color: #fff; background: var(--danger); border-color: transparent; }
        .top nav { margin-left: auto; display: flex; gap: 14px; font-size: 12.5px; }
        .top nav a { color: var(--text-3); }
        .top nav a:hover { color: var(--text); }

        .err {
          padding: 9px 12px; border-radius: var(--r);
          background: #fdeceb; color: #8f231c; border: 1px solid #f3c9c5; font-size: 13px;
        }
        @media (prefers-color-scheme: dark) {
          .err { background: #2c1512; color: #f0b3ad; border-color: #4a221d; }
        }
        .loading {
          flex: 1; display: grid; place-items: center; color: var(--text-3);
          border: 1px dashed var(--border-strong); border-radius: var(--r-lg);
          text-align: center; padding: 20px;
        }
      `}</style>
    </div>
  );
}
