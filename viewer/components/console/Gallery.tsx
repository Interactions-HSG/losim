'use client';

/**
 * Every run there is, as something you can choose between.
 *
 * A picker with a hundred and five lines in it is a filing cabinet. What a
 * student actually wants from this page is the comparison — this design took
 * five seconds and cost 1.27; the same design with a mapper killed took seven
 * and cost 1.53 — and a comparison needs the two numbers on the same screen,
 * not one at a time behind a dropdown.
 *
 * So the cards carry what `traces.sh` copied out of the trace and the bill: how
 * many machines, how far apart they were, how long it took, what it cost, and
 * whether it finished. None of it is computed here. The viewer inventing its own
 * prices would be a second accountant, and two accountants disagree.
 */
import { useMemo, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole } from '../../lib/console.tsx';
import { BUCKETS, money } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import type { RunRef } from '../../lib/runs.ts';

const GROUPS = [
  { key: 'yours', label: 'Your runs', note: 'whatever you have run in this project' },
  { key: 'suite', label: 'Reference suite', note: 'the runs losim checks itself against' },
  { key: 'gallery', label: 'Gallery', note: 'worked examples, written to teach with' },
] as const;

export function Gallery() {
  const { runs, run, open } = useConsole();
  const [q, setQ] = useState('');
  const [only, setOnly] = useState<string>('');

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return runs.filter(
      (r) =>
        (!only || (r.from ?? 'gallery') === only)
        && (!needle
          || r.name.toLowerCase().includes(needle)
          || (r.job ?? '').toLowerCase().includes(needle)
          || (r.scenario ?? '').toLowerCase().includes(needle)),
    );
  }, [runs, q, only]);

  const currency = runs.find((r) => r.currency)?.currency ?? 'CHF';

  return (
    <>
      <Head
        title="Runs"
        sub={
          <>
            Every trace beside this app. Open one and the clock above governs all four views of
            it — the film, the execution graph, what each machine was doing, and what it had
            cost by then.
          </>
        }
        actions={
          <input
            className="find"
            placeholder="Filter by name, job or scenario"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="filter runs"
          />
        }
      />

      <div className="seg" role="group" aria-label="whose runs">
        <button aria-pressed={only === ''} onClick={() => setOnly('')}>
          all {runs.length}
        </button>
        {GROUPS.map((g) => {
          const n = runs.filter((r) => (r.from ?? 'gallery') === g.key).length;
          if (!n) return null;
          return (
            <button key={g.key} aria-pressed={only === g.key} onClick={() => setOnly(g.key)}>
              {g.label.toLowerCase()} {n}
            </button>
          );
        })}
      </div>

      {GROUPS.map((g) => {
        const some = shown.filter((r) => (r.from ?? 'gallery') === g.key);
        if (!some.length) return null;
        return (
          <Panel key={g.key} title={g.label} note={`${some.length} · ${g.note}`}>
            <div className="cards">
              {some.map((r) => (
                <Card
                  key={r.name}
                  r={r}
                  here={r.name === run?.name}
                  currency={currency}
                  onOpen={() => void open(r.name, 'overview')}
                />
              ))}
            </div>
          </Panel>
        );
      })}

      {!shown.length && (
        <Panel>
          <p className="muted">
            Nothing matches <strong>{q}</strong>. Every run is named for the scenario it came
            from, so <code>kill</code>, <code>chaos</code> and <code>locality</code> are all
            worth trying.
          </p>
        </Panel>
      )}

      <style>{`
        .find {
          height: 36px; width: 300px; max-width: 46vw; padding: 0 14px;
          font: inherit; font-size: 13.5px; color: var(--text);
          background: var(--surface); border: 1px solid var(--border);
          border-radius: 999px; box-shadow: var(--shadow-1);
        }
        .cards {
          display: grid; gap: 16px;
          grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
        }
        .run {
          display: flex; flex-direction: column; overflow: hidden;
          background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--r-lg); box-shadow: var(--shadow-1);
          transition: box-shadow .14s ease, border-color .14s ease;
        }
        .run:hover { box-shadow: var(--shadow-2); }
        .run.here { border-color: var(--accent); }
        .cover { display: block; padding: 0; border: 0; background: none; cursor: pointer; }
        .in { display: flex; flex-direction: column; gap: 8px; padding: 14px 16px 16px; }
        .line { display: flex; align-items: center; gap: 8px; }
        .name {
          padding: 0; border: 0; background: none; cursor: pointer;
          font: inherit; font-family: var(--mono); font-size: 13.5px; font-weight: 500;
          color: var(--text); text-align: left;
        }
        .name:hover { color: var(--accent); }
        .of { margin: 0; font-size: 12.5px; color: var(--text-2); }
        dl { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin: 2px 0 0; }
        dl div { display: flex; justify-content: space-between; gap: 8px; }
        dt { font-size: 11.5px; color: var(--text-3); }
        dd {
          margin: 0; font-family: var(--mono); font-size: 12px;
          font-variant-numeric: tabular-nums; color: var(--text-2);
        }
        dd.cost { color: var(--text); font-weight: 500; }
        .far { color: var(--warn); }
        .stack {
          display: flex; height: 6px; border-radius: 3px; overflow: hidden;
          background: var(--surface-2); margin-top: 2px;
        }
        .stack i { display: block; height: 100%; }
        .chip.bad { color: #fff; background: var(--danger); border-color: transparent; align-self: flex-start; }
        .cvr { display: block; width: 100%; height: auto; }
        .cvr .zl { font: 400 9.5px var(--mono); fill: var(--text-3); }
      `}</style>
    </>
  );
}

function Card({
  r,
  here,
  currency,
  onOpen,
}: {
  r: RunRef;
  here: boolean;
  currency: string;
  onOpen: () => void;
}) {
  const zones = r.zones ?? [];
  const regions = [...new Set(zones.map((z) => z.replace(/[a-z0-9]$/, '')))];
  return (
    <article className={`run${here ? ' here' : ''}`}>
      <button className="cover" onClick={onOpen} aria-label={`open ${r.name}`}>
        <Cover machines={r.machines ?? 1} zones={zones} broke={r.completed === false} />
      </button>
      <div className="in">
        <div className="line">
          <button className="name" onClick={onOpen}>{r.name}</button>
          {here && <span className="chip">open</span>}
        </div>
        <p className="of">
          {r.job ?? 'a job'}
          {r.scenario && <span className="muted"> · {r.scenario}</span>}
        </p>
        <dl>
          <div>
            <dt>machines</dt>
            <dd>{r.machines ?? '—'}</dd>
          </div>
          <div>
            <dt>zones</dt>
            <dd>
              {zones.length || '—'}
              {regions.length > 1 && <span className="far"> · {regions.length} regions</span>}
            </dd>
          </div>
          <div>
            <dt>took</dt>
            <dd>{r.durationRefMs === undefined ? '—' : refTime(r.durationRefMs)}</dd>
          </div>
          <div>
            <dt>cost</dt>
            <dd className="cost">
              {r.cost === undefined ? '—' : money(r.cost, r.currency ?? currency)}
            </dd>
          </div>
        </dl>
        {r.buckets && (
          <div className="stack" title="build · capacity · consumption · incidents">
            {BUCKETS.map((b) => {
              const v = r.buckets?.[b] ?? 0;
              if (v <= 0) return null;
              return (
                <i
                  key={b}
                  style={{
                    width: `${(v / Math.max(r.cost ?? 1, 1e-9)) * 100}%`,
                    background: COLOUR[b],
                  }}
                />
              );
            })}
          </div>
        )}
        {r.completed === false && <span className="chip bad">did not finish</span>}
      </div>

    </article>
  );
}

/**
 * A run, drawn small: its zones as boxes and a dot per machine.
 *
 * Not decoration. Twelve dots in one box and twelve spread over three are two
 * different designs, and the difference is the thing this course is about —
 * which makes it the thing worth being able to see without opening either.
 */
function Cover({ machines, zones, broke }: { machines: number; zones: string[]; broke: boolean }) {
  const W = 300;
  const H = 104;
  const n = Math.max(zones.length, 1);
  const pad = 12;
  const gap = 10;
  const w = (W - pad * 2 - gap * (n - 1)) / n;
  const per = Math.ceil(machines / n);
  const cols = Math.min(4, Math.max(1, Math.floor((w - 16) / 18) || 1));

  let left = machines;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="cvr" aria-hidden>
      <rect x={0} y={0} width={W} height={H} style={{ fill: 'var(--surface-2)' }} />
      {Array.from({ length: n }, (_, i) => {
        const x = pad + i * (w + gap);
        const here = Math.min(left, per);
        left -= here;
        return (
          <g key={i}>
            <rect
              x={x} y={12} width={w} height={H - 30} rx={6}
              style={{ fill: 'var(--surface)', stroke: 'var(--border-strong)' }}
            />
            {Array.from({ length: here }, (_, k) => (
              <circle
                key={k}
                cx={x + 14 + (k % cols) * 17}
                cy={28 + Math.floor(k / cols) * 17}
                r={5.5}
                style={{ fill: broke && k === 0 && i === 0 ? 'var(--danger)' : 'var(--accent)' }}
                opacity={0.82}
              />
            ))}
            <text x={x + 6} y={H - 6} className="zl">{zones[i] ?? 'one zone'}</text>
          </g>
        );
      })}
    </svg>
  );
}
