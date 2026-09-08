'use client';

/**
 * The open run, read in order.
 *
 * This is the page somebody meeting a distributed system for the first time
 * lands on, and the thing that makes it teach rather than merely display is
 * that it is **numbered**. Five questions, asked in the order you have to ask
 * them, each with a one-line answer above the instrument that argues it:
 *
 *   1. What is this system made of?      — the map
 *   2. What did it actually do?          — the call tree
 *   3. How hard did each node work?   — the cluster, at the clock
 *   4. What went wrong?                  — the accidents
 *   5. What did it cost?                 — the money, as it arrived
 *
 * One column, because a two-column page has no order and this page is nothing
 * but order. Every section is drawn at the clock above, so scrubbing moves all
 * five at once — which is the point of having them on one page.
 */
import { useEffect, useMemo, useRef, useState } from 'react';

import { LineChart, short } from './Chart.tsx';
import { Head, Panel, Tile } from './Shell.tsx';
import { Spans } from '../Spans.tsx';
import { Topology } from '../Topology.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { BUCKETS, money } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import { useTheme } from '../../lib/theme.ts';

/**
 * What a trace's event kinds are, said the way somebody would say them.
 *
 * `disk_full` is a field name. A student reading a list of them is reading a
 * schema, not a story, and the story is the only reason this list is on the
 * page — so the field name stays as the tag and the sentence sits beside it.
 */
const PLAIN: Record<string, string> = {
  kill: 'a node was killed',
  oom: 'a node ran out of memory',
  disk_full: 'a node ran out of disk',
  rpc_timeout: 'a call did not answer in time',
  retry: 'a call was tried again',
  spot_notice: 'a cheap node was about to be taken back',
  freeze: 'a node stopped answering',
  thaw: 'a node started answering again',
  degrade: 'a node slowed down',
  failed: 'the simulation ended without an answer',
  done: 'the simulation finished',
};

/**
 * One numbered question, and the thing that answers it.
 *
 * Not `Panel`: two of these hold instruments that draw their own cards, and a
 * card inside a card is a border somebody has to look past. The number is the
 * whole design — it says there is an order and that you are somewhere in it.
 */
function Step({
  n,
  q,
  say,
  aside,
  children,
}: {
  n: number;
  q: string;
  say: React.ReactNode;
  aside?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="step">
      <header>
        <span className="step-n" aria-hidden>{n}</span>
        <div className="step-q">
          <h2>{q}</h2>
          <p>{say}</p>
        </div>
        {aside && <div className="step-aside">{aside}</div>}
      </header>
      {children}
      <style>{`
        .step { display: flex; flex-direction: column; gap: 12px; }
        .step > header { display: flex; align-items: flex-start; gap: 14px; }
        /* Named apart from the table conventions on purpose: a bare .n here
           would also match every right-aligned number cell inside the section. */
        .step-n {
          flex: none; width: 26px; height: 26px; border-radius: 50%;
          display: grid; place-items: center; margin-top: 1px;
          background: var(--accent-soft); color: var(--accent);
          font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums;
        }
        .step-q { min-width: 0; }
        .step-q h2 { margin: 0; font-size: 17px; font-weight: 500; letter-spacing: -0.01em; }
        .step-q p { margin: 3px 0 0; font-size: 13px; color: var(--text-3); max-width: 68ch; }
        .step-aside { margin-left: auto; flex: none; display: flex; gap: 8px; align-items: center; }
      `}</style>
    </section>
  );
}

export function Overview() {
  const { run, ledger, clock, go } = useConsole();
  const now = useNow();
  const theme = useTheme();

  const frame = useMemo(() => (run ? run.index.frameAt(now) : null), [run, now]);
  const l = useMemo(() => ledger?.at(now) ?? null, [ledger, now]);

  /**
   * What the whole run cost, so the accruing chart has a ruler that does not
   * move while the clock does.
   */
  const finalBuckets = useMemo(() => ledger?.at(Number.MAX_SAFE_INTEGER) ?? null, [ledger]);

  /**
   * The map's shape, so the cluster is arranged for the box it is actually in.
   *
   * Quantised to a tenth for the same reason the film does it: a layout that
   * re-searched on every pixel of a window drag would rearrange the cluster while
   * somebody was resizing it.
   */
  const mapBox = useRef<HTMLDivElement>(null);
  const [aspect, setAspect] = useState(2.2);
  useEffect(() => {
    const el = mapBox.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const w = el.clientWidth;
      const h = el.clientHeight;
      if (w > 0 && h > 0) setAspect(Math.round((w / h) * 10) / 10);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const layout = useMemo(
    () => (run ? run.index.refit([aspect * 5.6, 5.6]) : null),
    [run, aspect],
  );

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
    () =>
      curve
        .filter((p) => p[0] <= now)
        .concat(l ? [[Math.min(now, run?.trace.duration ?? now), l.cost]] : []),
    [curve, now, l, run],
  );

  if (!run) return null;
  const { trace } = run;
  const started = trace.spans.filter((s) => s.kind === 'rpc' && s.t0 <= now).length;
  const rpcs = trace.spans.filter((s) => s.kind === 'rpc').length;
  const failed = trace.spans.filter(
    (s) => s.kind === 'rpc' && s.t1 >= 0 && s.t1 <= now && s.status !== 'OK' && s.status !== '',
  ).length;
  // The run's own notable moments, not every line in the trace: a call being
  // made and a handler starting are the system working, and listing them under
  // "gone wrong" would bury the kill that actually did.
  const wrong = run.index.events().filter((e) => Number(e.t ?? 0) <= now);
  const zones = new Set(trace.nodes.map((m) => m.zone)).size;
  const busiest = [...(frame?.nodes ?? [])].sort((a, b) => b.inflight - a.inflight)[0] ?? null;

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
            Entered at {trace.entry || 'a node'}, across {trace.nodes.length} nodes
            {zones > 1 ? ` in ${zones} zones` : ' in one zone'}, over {refTime(trace.duration)}.
            {' '}Everything below is drawn at whatever instant the bar above is showing — drag it,
            and all five sections move together.
          </>
        }
        actions={<button className="btn primary" onClick={() => go('film')}>▶ Watch it</button>}
      />

      {/* Four numbers, and each one is a sentence. A tile that says `19` and
          nothing else is a number somebody has to go and find the meaning of. */}
      <div className="tiles">
        <Tile
          k="Where we are"
          v={refTime(now)}
          n={`of ${refTime(trace.duration)}`}
        />
        <Tile
          k="Calls made so far"
          v={started}
          n={`of ${rpcs} the run makes in total`}
        />
        <Tile
          k="Calls that failed"
          v={failed}
          n={failed ? 'they answered with an error, or not at all' : 'every call so far was answered'}
        />
        <Tile
          k="Cost so far"
          v={l ? money(l.cost, l.currency) : '—'}
          n={l ? `of ${money(l.finalCost, l.currency)} for the whole run` : 'no bill beside this trace'}
        />
      </div>

      <Step
        n={1}
        q="What is this system made of?"
        say={
          <>
            Every node, and every node it ever calls. An arrow is thicker where more
            data went down it, and tinted where the call crossed a zone — those are the ones
            you pay for and wait longer for. The picture arrives as the clock runs.
          </>
        }
      >
        <div className="map card" ref={mapBox}>
          {layout && (
            <Topology
              trace={trace}
              layout={layout}
              theme={theme}
              t={now}
              hovered={null}
              plain
            />
          )}
        </div>
      </Step>

      <Step
        n={2}
        q="What did it actually do?"
        say={
          <>
            One call per row, indented under the call that caused it — so a row's width is how
            long it took and its depth is who asked for it. Click a row to move the clock to it.
            The outlined chain is the <strong>critical path</strong>: the calls the run was
            actually waiting on, and the only ones worth making faster.
          </>
        }
        aside={<button className="btn" onClick={() => go('film')}>Watch it instead</button>}
      >
        <div className="calls">
          <Spans
            trace={trace}
            theme={theme}
            t={now}
            onSeek={(to) => {
              clock?.pause();
              clock?.seek(to);
            }}
            hovered={null}
            onHoverNode={() => {}}
          />
        </div>
      </Step>

      <Step
        n={3}
        q="How hard is each node working?"
        say={
          <>
            The cluster at this exact instant.{' '}
            <strong>In flight</strong> is what it is handling now, <strong>queued</strong> is
            what is waiting for a free core — a queue that never empties is a node too small.
            {busiest && busiest.inflight > 0 && (
              <> Right now the busiest is <code>{busiest.name}</code>.</>
            )}
          </>
        }
        aside={<button className="btn" onClick={() => go('usage')}>Charts over time</button>}
      >
        <Panel flush>
          <div className="scroll">
            <table>
              <thead>
                <tr>
                  <th>Node</th>
                  <th>Zone</th>
                  <th>Serves</th>
                  <th className="r">In flight</th>
                  <th className="r">Queued</th>
                  <th className="r">Memory held</th>
                  <th>Doing</th>
                </tr>
              </thead>
              <tbody>
                {(frame?.nodes ?? []).map((m) => (
                  <tr key={m.name}>
                    <td className="id">{m.name}</td>
                    <td className="muted">{m.zone}</td>
                    <td className="muted">{m.serves.join(', ') || '—'}</td>
                    <td className="n">{m.inflight}</td>
                    <td className="n">{m.queued}</td>
                    <td className="n">{short(m.heldMb)} MB</td>
                    <td>
                      <span className={`state ${m.state}`}>{m.state}</span>
                      {m.work.length > 0 && <span className="muted"> · {m.work[0].method}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </Step>

      <Step
        n={4}
        q="What has gone wrong?"
        say={
          wrong.length ? (
            <>
              {wrong.length} {wrong.length === 1 ? 'thing has' : 'things have'} gone wrong by{' '}
              {refTime(now)}. These are the moments the run had to survive — and section 5 is
              what surviving them cost.
            </>
          ) : (
            <>
              Nothing by {refTime(now)}. Press <kbd>]</kbd> to jump straight to the next thing
              that broke, rather than hunting for it with the scrubber.
            </>
          )
        }
      >
        <Panel flush>
          {wrong.length ? (
            <ul className="events">
              {[...wrong].reverse().slice(0, 14).map((e, i) => (
                <li key={i}>
                  <button
                    onClick={() => {
                      clock?.pause();
                      clock?.seek(Number(e.t ?? 0));
                    }}
                    title="move the clock to this moment"
                  >
                    <span className="when mono">{refTime(Number(e.t ?? 0))}</span>
                    <span className={`kind ${e.kind}`}>{e.kind}</span>
                    <span className="said">{PLAIN[String(e.kind)] ?? 'something the trace records'}</span>
                    {e.vm && <span className="who mono">{String(e.vm)}</span>}
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="pad muted">Nothing yet.</p>
          )}
          {wrong.length > 14 && (
            <p className="pad muted">{wrong.length - 14} earlier ones, not listed.</p>
          )}
        </Panel>
      </Step>

      <Step
        n={5}
        q="What did it cost?"
        say={
          l ? (
            <>
              Money arrives at different times for different reasons, and that is the whole
              lesson: <strong>build</strong> and <strong>capacity</strong> are settled by drawing
              the nodes, before a byte moves; <strong>consumption</strong> arrives with the
              work; <strong>incidents</strong> land at the instant something breaks.
            </>
          ) : (
            <>There is no bill beside this trace, so there is no money to show.</>
          )
        }
        aside={<button className="btn" onClick={() => go('cost')}>The full bill</button>}
      >
        {l && finalBuckets ? (
          <Panel>
            <div className="money">
              <div className="sum">
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
              </div>
              <div className="curve">
                <LineChart
                  series={[{ name: 'cost', color: COLOUR.capacity, pts: drawn }]}
                  duration={trace.duration}
                  now={now}
                  yMax={finalBuckets.cost}
                  height={200}
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
                          {money(settled, l.currency)} of the final{' '}
                          {money(finalBuckets.cost, l.currency)} was fixed before anything ran
                        </strong>
                        {' — '}
                        {pct >= 99.5 ? 'very nearly all of it' : `${pct.toFixed(0)}% of it`}. Build
                        is engineering time and capacity is the cluster you reserved: both are
                        settled by drawing the nodes, and running the job can only change{' '}
                        {money(earned, l.currency)} of it.
                      </>
                    );
                  })()}
                </p>
              </div>
            </div>
          </Panel>
        ) : (
          <Panel>
            <p className="muted">
              Run <code>losim bill</code> next to this trace and this fills in — the viewer will
              not invent prices of its own.
            </p>
          </Panel>
        )}
      </Step>

      <style>{`
        .tiles { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); }

        /* The map and the call tree both draw into whatever room they are given,
           so they are the two things on this page with a height of their own. */
        .map { height: clamp(300px, 42vh, 480px); padding: 0; overflow: hidden; display: flex; }
        .calls { display: flex; flex-direction: column; height: clamp(400px, 56vh, 660px); }

        .scroll { overflow-x: auto; padding: 0 20px 8px; }
        .pad { padding: 14px 20px; margin: 0; }
        td.id, .id { font-family: var(--mono); font-weight: 500; }
        th.r, td.n { text-align: right; font-variant-numeric: tabular-nums; }
        td.n { font-family: var(--mono); }
        .state { font-size: 11.5px; font-weight: 500; }
        .state.alive { color: var(--text-2); }
        .state.degraded { color: var(--warn); }
        .state.frozen { color: #7c93a8; }
        .state.dead, .state.reclaiming { color: var(--danger); }

        .money { display: grid; gap: 24px; grid-template-columns: minmax(0, 260px) minmax(0, 1fr); align-items: start; }
        @media (max-width: 900px) { .money { grid-template-columns: 1fr; } }
        .big { font-size: 30px; font-weight: 500; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
        .note { margin: 4px 0 12px; font-size: 12.5px; color: var(--text-3); }
        .curve .note { margin: 10px 0 0; }
        .stack { display: flex; height: 10px; border-radius: 5px; overflow: hidden; background: var(--surface-2); }
        .stack i { display: block; height: 100%; }
        .kv { margin: 12px 0 0; display: flex; flex-direction: column; }
        .kv div { display: flex; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 13px; }
        .kv div + div { border-top: 1px solid var(--border); }
        .kv dt { display: flex; align-items: center; gap: 8px; color: var(--text-2); }
        .kv dt i { width: 9px; height: 9px; border-radius: 2px; }
        .kv dd { margin: 0; font-family: var(--mono); font-variant-numeric: tabular-nums; }
        .kv .tot { font-weight: 600; border-top: 2px solid var(--text) !important; margin-top: 4px; }

        /* Every accident is a place on the clock, so every accident is a button. */
        .events { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
        .events li + li { border-top: 1px solid var(--border); }
        .events button {
          display: flex; gap: 12px; align-items: baseline; width: 100%;
          padding: 9px 20px; font: inherit; font-size: 13px; text-align: left;
          background: none; border: 0; cursor: pointer; color: var(--text);
        }
        .events button:hover { background: var(--surface-2); }
        .events .when { color: var(--text-3); width: 76px; flex: none; }
        .events .kind { font-weight: 500; width: 108px; flex: none; }
        .events .said { color: var(--text-2); }
        .events .who { margin-left: auto; color: var(--text-3); font-size: 12px; }
        .events .kind.kill, .events .kind.oom, .events .kind.disk_full, .events .kind.failed { color: var(--danger); }
        .events .kind.retry, .events .kind.rpc_timeout, .events .kind.degrade, .events .kind.spot_notice { color: var(--warn); }
        .events .kind.freeze, .events .kind.thaw { color: #7c93a8; }
      `}</style>
    </>
  );
}
