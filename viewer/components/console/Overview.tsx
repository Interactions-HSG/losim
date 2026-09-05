'use client';

/**
 * The open run, at the clock.
 *
 * Four things about one instant: what has happened by now, what it has cost by
 * now, what every machine is doing at this exact moment, and what has gone wrong
 * so far. None of them is a summary of the run — a summary is what the bill
 * already is, and what a summary cannot tell you is *when* the money was
 * decided, which is the only question this page exists to answer.
 */
import { useMemo } from 'react';

import { LineChart, short } from './Chart.tsx';
import { Head, Panel, Tile } from './Shell.tsx';
import { Spans } from '../Spans.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { BUCKETS, money } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import { useTheme } from '../../lib/theme.ts';

export function Overview() {
  const { run, ledger, clock, go } = useConsole();
  const now = useNow();
  const theme = useTheme();

  const frame = useMemo(() => run?.index.frameAt(now) ?? null, [run, now]);
  const l = useMemo(() => ledger?.at(now) ?? null, [ledger, now]);

  /**
   * What the whole run cost, bucket by bucket, so the accruing chart has a ruler
   * that does not move while the clock does.
   */
  const finalBuckets = useMemo(() => ledger?.at(Number.MAX_SAFE_INTEGER) ?? null, [ledger]);

  /**
   * The cost curve, built once and sliced at the clock.
   *
   * Rebuilding it on every frame would be a hundred `ledger.at()` calls sixty
   * times a second; the shape does not change, only how much of it you can see.
   */
  const curve = useMemo(() => {
    if (!ledger || !run) return [];
    const d = run.trace.duration;
    const out: [number, number][] = [];
    for (let i = 0; i <= 120; i++) {
      const t = (d * i) / 120;
      out.push([t, ledger.at(t).cost]);
    }
    return out;
  }, [ledger, run]);
  const drawn = useMemo(
    () => curve.filter((p) => p[0] <= now).concat(l ? [[Math.min(now, run?.trace.duration ?? now), l.cost]] : []),
    [curve, now, l, run],
  );

  if (!run) return null;
  const { trace } = run;
  const started = trace.spans.filter((s) => s.kind === 'rpc' && s.t0 <= now).length;
  const rpcs = trace.spans.filter((s) => s.kind === 'rpc').length;
  const phase = trace.phaseAt(now);
  // The run's own notable moments, not every line in the trace: a call being
  // made and a handler starting are the system working, and listing them under
  // "gone wrong" would bury the kill that actually did.
  const wrong = run.index.events().filter((e) => Number(e.t ?? 0) <= now);

  return (
    <>
      <Head
        crumbs={
          <>
            <a href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</a>
            {' / '}
            {run.name}
          </>
        }
        title={run.name}
        sub={
          <>
            {String(trace.meta['job'] ?? 'a job')} · {trace.machines.length} machines in{' '}
            {new Set(trace.machines.map((m) => m.zone)).size} zones ·{' '}
            {String(trace.meta['scenario'] ?? '')}
          </>
        }
        actions={
          <>
            <button className="btn" onClick={() => go('film')}>▶ Watch it</button>
            <button className="btn" onClick={() => go('usage')}>Usage</button>
            <button className="btn primary" onClick={() => go('cost')}>Cost</button>
          </>
        }
      />

      <div className="tiles">
        <Tile
          k="Billed so far"
          v={l ? money(l.cost, l.currency) : '—'}
          n={l ? `of ${money(l.finalCost, l.currency)} for the whole run` : 'no bill beside this trace'}
        />
        <Tile
          k="At"
          v={refTime(now)}
          n={`of ${refTime(trace.duration)}${phase ? ` · ${phase}` : ''}`}
        />
        <Tile k="Calls started" v={started} n={`of ${rpcs} in the run`} />
        <Tile
          k="Gone wrong"
          v={wrong.length}
          n={wrong.length ? [...new Set(wrong.map((e) => e.kind))].join(', ') : 'nothing yet'}
        />
      </div>

      <div className="two">
        <div className="col">
          <Panel
            title="Execution graph"
            note="the distributed call stack, to the clock"
            actions={<button className="btn" onClick={() => go('film')}>Watch it</button>}
            flush
          >
            <Spans
              trace={trace}
              theme={theme}
              t={now}
              onSeek={(to) => {
                clock?.pause();
                clock?.seek(to);
              }}
              hovered={null}
              onHoverMachine={() => {}}
            />
          </Panel>

          <Panel title="Machines, at the clock" note={`${trace.machines.length}`} flush>
            <div className="scroll">
              <table>
                <thead>
                  <tr>
                    <th>Machine</th>
                    <th>Instance</th>
                    <th>Zone</th>
                    <th>Serves</th>
                    <th className="r">Held</th>
                    <th className="r">In flight</th>
                    <th className="r">Queued</th>
                    <th>Doing</th>
                  </tr>
                </thead>
                <tbody>
                  {(frame?.machines ?? []).map((m) => (
                    <tr key={m.name}>
                      <td className="id">{m.name}</td>
                      <td>{m.instance}</td>
                      <td className="muted">{m.zone}</td>
                      <td className="muted">{m.serves.join(', ') || '—'}</td>
                      <td className="n">{short(m.heldMb)} MB</td>
                      <td className="n">{m.inflight}</td>
                      <td className="n">{m.queued}</td>
                      <td>
                        <span className={`state ${m.state}`}>{m.state}</span>
                        {m.work.length > 0 && (
                          <span className="muted"> · {m.work[0].method}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </div>

        <div className="col">
          {l && finalBuckets ? (
            <Panel
              title="Cost, as it accrues"
              actions={<button className="btn" onClick={() => go('cost')}>Open in Cost</button>}
            >
              <div className="big">{money(l.cost, l.currency)}</div>
              <p className="note">
                as at <span className="mono">{refTime(now)}</span> of {refTime(trace.duration)}
              </p>
              <div className="stack">
                {BUCKETS.map((b) => (
                  <i
                    key={b}
                    style={{
                      width: `${(l.buckets[b] / Math.max(l.cost, 1e-9)) * 100}%`,
                      background: COLOUR[b],
                    }}
                  />
                ))}
              </div>
              <dl className="kv">
                {BUCKETS.map((b) => (
                  <div key={b}>
                    <dt>
                      <i style={{ background: COLOUR[b] }} />
                      {b}
                    </dt>
                    <dd>{money(l.buckets[b], l.currency)}</dd>
                  </div>
                ))}
                <div className="tot">
                  <dt>Total</dt>
                  <dd>{money(l.cost, l.currency)}</dd>
                </div>
              </dl>
              <LineChart
                series={[{ name: 'cost', color: COLOUR.capacity, pts: drawn }]}
                duration={trace.duration}
                now={now}
                yMax={finalBuckets.cost}
                height={130}
                area
                unit={l.currency}
                label="what this run had cost, over the run"
              />
              <p className="note">
                {(() => {
                  // Against the *whole* run, not against what has accrued: the
                  // claim is about what the design fixed in advance, and that is
                  // a property of the final bill rather than of this instant.
                  const settled = finalBuckets.buckets.build + finalBuckets.buckets.capacity;
                  const earned = finalBuckets.cost - settled;
                  const pct = (settled / Math.max(finalBuckets.cost, 1e-9)) * 100;
                  return (
                    <>
                      <strong>
                        {money(settled, l.currency)} of the final {money(finalBuckets.cost, l.currency)}
                        {' '}was fixed before anything ran
                      </strong>
                      {' — '}{pct >= 99.5 ? 'very nearly all of it' : `${pct.toFixed(0)}% of it`}.
                      Build is
                      engineering time and capacity is the fleet you reserved: both are settled by
                      drawing the machines, and the run can only change {money(earned, l.currency)}
                      {' '}of it.
                    </>
                  );
                })()}
              </p>
            </Panel>
          ) : (
            <Panel title="Cost">
              <p className="muted">
                No bill beside this trace, so there is no money on this page. Run{' '}
                <code>losim bill</code> next to it and this fills in — the viewer will not invent
                prices of its own.
              </p>
            </Panel>
          )}

          <Panel title="Wrong so far" note={`${wrong.length} so far`}>
            {wrong.length ? (
              <ul className="events">
                {wrong.slice(-12).reverse().map((e, i) => (
                  <li key={i}>
                    <span className="when mono">{refTime(Number(e.t ?? 0))}</span>
                    <span className={`kind ${e.kind}`}>{e.kind}</span>
                    <span className="muted">{e.vm ?? ''}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">
                Nothing by {refTime(now)}. Drag the clock forward — or press <kbd>]</kbd>, which
                jumps to the next thing that happened.
              </p>
            )}
          </Panel>
        </div>
      </div>

      <style>{`
        .tiles { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); }
        .two { display: grid; gap: 20px; grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr); align-items: start; }
        .col { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
        @media (max-width: 1180px) { .two { grid-template-columns: 1fr; } }

        .scroll { overflow-x: auto; padding: 0 20px 8px; }
        td.id, .id { font-family: var(--mono); font-weight: 500; }
        th.r, td.n { text-align: right; font-variant-numeric: tabular-nums; }
        td.n { font-family: var(--mono); }
        .state { font-size: 11.5px; font-weight: 500; }
        .state.alive { color: var(--text-2); }
        .state.degraded { color: var(--warn); }
        .state.frozen { color: #7c93a8; }
        .state.dead, .state.reclaiming { color: var(--danger); }

        .big { font-size: 32px; font-weight: 500; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
        .note { margin: 4px 0 14px; font-size: 12px; color: var(--text-3); }
        .stack { display: flex; height: 10px; border-radius: 5px; overflow: hidden; background: var(--surface-2); }
        .stack i { display: block; height: 100%; }
        .kv { margin: 12px 0 4px; display: flex; flex-direction: column; }
        .kv div { display: flex; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 13px; }
        .kv div + div { border-top: 1px solid var(--border); }
        .kv dt { display: flex; align-items: center; gap: 8px; color: var(--text-2); }
        .kv dt i { width: 9px; height: 9px; border-radius: 2px; }
        .kv dd { margin: 0; font-family: var(--mono); font-variant-numeric: tabular-nums; }
        .kv .tot { font-weight: 600; border-top: 2px solid var(--text) !important; margin-top: 4px; }

        .events { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
        .events li { display: flex; gap: 10px; align-items: baseline; padding: 6px 0; font-size: 12.5px; }
        .events li + li { border-top: 1px solid var(--border); }
        .events .when { color: var(--text-3); width: 76px; flex: none; }
        .events .kind { font-weight: 500; }
        .events .kind.kill, .events .kind.oom, .events .kind.disk_full, .events .kind.job_failed { color: var(--danger); }
        .events .kind.retry, .events .kind.rpc_timeout, .events .kind.degrade, .events .kind.spot_notice { color: var(--warn); }
        .events .kind.freeze, .events .kind.thaw { color: #7c93a8; }
      `}</style>
    </>
  );
}
