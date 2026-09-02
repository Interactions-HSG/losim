'use client';

/**
 * Everything about one machine, at this instant.
 *
 * **A panel, not a tooltip.** A tooltip is for a number. What a viewer wants
 * when they point at a machine is everything about it right now, and there is
 * far too much of that to float over the picture: what it is, what it holds
 * against what it is allowed to hold, what it is computing and the *whole*
 * payload rather than the digest, what it has served, and what has happened to
 * it.
 *
 * **It is a slice of the same frame the picture is drawn from**, not a second
 * query path — so it cannot disagree with the machine behind it, and it keeps
 * working while the film plays. That last part is the point of pinning: released
 * on hover a machine can only be sampled, and pinned it can be *watched*, which
 * is how you see a reducer fill up rather than discover that it did.
 *
 * The sparklines are the whole run with the current instant marked, so the
 * reading has a shape around it. "Holding 255 MB" says nothing on its own; "255
 * MB, climbing steadily since the shuffle started, 180 left" says what is about
 * to happen.
 */
import { useMemo } from 'react';

import * as D from '../lib/design.ts';
import { bare, type FrameMachine } from '../lib/frame.ts';
import { mb } from './Dataflow.tsx';
import { refTime } from '../lib/playback.ts';
import { Payload } from './Payload.tsx';
import { money as chf, type Ledger } from '../lib/ledger.ts';
import { digest, type Trace, type TraceEvent } from '../lib/trace.ts';

export interface MachinePanelProps {
  trace: Trace;
  m: FrameMachine;
  t: number;
  /** The bill at this instant, focused on this machine. Absent when there is none. */
  money?: Ledger | null;
  pinned: boolean;
  onPin: () => void;
  onClose: () => void;
}

export function MachinePanel({ trace, m, t, money, pinned, onPin, onClose }: MachinePanelProps) {
  const held = useMemo(() => trace.series(m.name, 'retainMb'), [trace, m.name]);
  const disk = useMemo(() => trace.series(m.name, 'diskMb'), [trace, m.name]);
  const busy = useMemo(() => trace.series(m.name, 'busyPct'), [trace, m.name]);

  const mine = useMemo(
    () =>
      trace.events
        .filter((e) => e.vm === m.name && TOLD.has(String(e.kind)))
        .sort((a, b) => Number(a.t ?? 0) - Number(b.t ?? 0)),
    [trace, m.name],
  );
  const totals = useMemo(() => trace.byName.get(m.name), [trace, m.name]);

  return (
    <aside
      className="panel card"
      role="dialog"
      aria-label={`machine ${m.name}`}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <header>
        <div>
          <h1>{m.name}</h1>
          <div className="sub">
            {m.instance} · {m.vcpu} vCPU · {m.zone}
          </div>
        </div>
        <div className="acts">
          <span className={`state ${m.state}`}>{m.state}</span>
          <button className="btn icon" onClick={onPin} aria-pressed={pinned} title={pinned ? 'unpin' : 'pin'}>
            {pinned ? '📌' : '📍'}
          </button>
        </div>
      </header>

      <section>
        <h2>what it offers</h2>
        <div className="chips">
          {m.serves.length ? (
            m.serves.map((s) => (
              <span key={s} className="chip">
                {s}
              </span>
            ))
          ) : (
            <span className="muted">nothing — it listens and offers no service</span>
          )}
        </div>
      </section>

      <section>
        <h2>what is left</h2>
        <Gauge
          name="memory"
          free={m.freeMb}
          used={m.heldMb}
          cap={m.capMb}
          share={m.memShare}
          series={held}
          t={t}
          duration={trace.duration}
        />
        {m.diskCapMb > 0 && (
          <Gauge
            name="disk"
            free={m.diskFreeMb}
            used={m.diskMb}
            cap={m.diskCapMb}
            share={m.diskShare}
            series={disk}
            t={t}
            duration={trace.duration}
          />
        )}
      </section>

      <section>
        <h2>what it is doing</h2>
        {m.work.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>
            idle
          </p>
        ) : (
          m.work.map((w) => (
            <div key={w.span.id} className="work">
              <div className="wtop">
                <span className="dot" style={{ background: D.taskColour(w.task) }} />
                <strong>{bare(w.label)}</strong>
                {w.task !== null && <span className="muted">task {w.task}</span>}
                <span className="muted mono" style={{ marginLeft: 'auto' }}>
                  {refTime(t - w.span.t0)} in
                </span>
              </div>
              <Payload detail={w.span.detail} />
            </div>
          ))
        )}
        <div className="lanes">
          <span className="muted">
            {Math.round(m.busy)}% of {m.vcpu} cores
          </span>
          {m.queued > 0 && <span style={{ color: D.WARN }}>{Math.round(m.queued)} waiting for a core</span>}
          {m.inflight > 0 && <span className="muted">{Math.round(m.inflight)} calls in flight</span>}
        </div>
        <Spark values={busy.v} times={busy.t} t={t} duration={trace.duration} colour={D.taskColour(0)} height={22} />
      </section>

      {totals && (
        <section>
          <h2>over the whole run</h2>
          <table>
            <tbody>
              <Row k="calls served" v={String(Math.round(num(totals.raw, 'calls')))} />
              <Row k="allocated" v={mb(num(totals.raw, 'allocMb'))} />
              <Row k="bytes out" v={mb(num(totals.raw, 'wireMb'))} />
              <Row k="bytes in" v={mb(num(totals.raw, 'inMb'))} />
              <Row k="crossed a zone" v={mb(num(totals.raw, 'crossZoneMb'))} hint="billed, and slower" />
              {/* And where it went, because that is what sets the rate: the zone
                  next door, another region, or across an ocean are three prices
                  for the same byte. A row here is a row on the bill. */}
              {egress(totals.raw).map(([region, sent]) => (
                <Row key={region} k={`↳ ${region}`} v={mb(sent)} />
              ))}
              <Row
                k="losim's own cost"
                v={mb(num(totals.raw, 'losimMb'))}
                hint="metered and taken back off everything above"
              />
            </tbody>
          </table>
        </section>
      )}

      {money?.focus && money.focus.name === m.name && (
        <section>
          <h2>what it has cost</h2>
          <div className="cost">
            <div>
              <span className="muted">so far</span>
              <strong className="mono">{chf(money.focus.cost, money.currency)}</strong>
            </div>
            <div>
              <span className="muted">by the end</span>
              <strong className="mono">{chf(money.focus.finalCost, money.currency)}</strong>
            </div>
            <div>
              <span className="muted">of the fleet</span>
              <strong className="mono">
                {Math.round((money.focus.cost / Math.max(money.cost, 1e-9)) * 100)}%
              </strong>
            </div>
          </div>
          <ul className="mylines">
            {money.lines
              .filter((x) => x.mine > 0)
              .slice(0, 5)
              .map((x, i) => (
                <li key={i}>
                  <span className="what">{x.line.what}</span>
                  <span className="mono">{chf(x.mine, money.currency)}</span>
                  <em>{x.why}</em>
                </li>
              ))}
          </ul>
          <p className="muted" style={{ fontSize: 11, margin: '6px 0 0' }}>
            Its share of lines <code>losim bill</code> already computed. Revenue and the
            late-finish penalty belong to the job and are not here.
          </p>
        </section>
      )}

      <section>
        <h2>what happened to it</h2>
        {mine.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>
            nothing — it ran to the end untouched
          </p>
        ) : (
          <ol className="events">
            {mine.map((e, i) => (
              <li key={i} className={Number(e.t ?? 0) <= t ? '' : 'later'}>
                <span className="mono when">{refTime(Number(e.t ?? 0))}</span>
                <span className="kind" style={{ color: kindColour(String(e.kind)) }}>
                  {String(e.kind).replace(/_/g, ' ')}
                </span>
                <span className="muted">{say(e)}</span>
              </li>
            ))}
          </ol>
        )}
      </section>

      <style>{`
        .panel {
          width: 340px; max-height: 100%; overflow-y: auto;
          padding: 14px 16px 18px; box-shadow: var(--shadow-3);
        }
        .panel header {
          display: flex; align-items: flex-start; gap: 10px;
          padding-bottom: 12px; margin-bottom: 12px; border-bottom: 1px solid var(--border);
          position: sticky; top: -14px; background: var(--surface); z-index: 2;
          padding-top: 14px; margin-top: -14px;
        }
        .panel .sub { font-size: 12px; color: var(--text-3); margin-top: 1px; }
        .panel .acts { margin-left: auto; display: flex; align-items: center; gap: 6px; }
        .state {
          font-size: 11px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase;
          padding: 2px 7px; border-radius: 999px;
          background: var(--surface-2); color: var(--text-2);
        }
        .state.dead { background: #fbeae8; color: ${D.ALARM}; }
        .state.degraded { background: #fdf3e3; color: #9a6a1c; }
        .state.frozen { background: #eaeff4; color: #4d6076; }
        @media (prefers-color-scheme: dark) {
          .state.dead { background: #3a1d1a; }
          .state.degraded { background: #392c15; color: ${D.WARN}; }
          .state.frozen { background: #1c2530; color: ${D.CHILL}; }
        }

        .panel section { margin-bottom: 16px; }
        .chips { display: flex; flex-wrap: wrap; gap: 5px; }

        .work { padding: 8px 0; border-bottom: 1px solid var(--border); }
        .work:last-of-type { border-bottom: 0; }
        .wtop { display: flex; align-items: center; gap: 7px; font-size: 12.5px; margin-bottom: 5px; }
        .dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
        .lanes { display: flex; gap: 12px; font-size: 12px; margin-top: 6px; }

        .events { list-style: none; margin: 0; padding: 0; font-size: 12.5px; }
        .events li { display: flex; gap: 8px; padding: 3px 0; align-items: baseline; }
        .events li.later { opacity: .35; }
        .events .when { color: var(--text-3); min-width: 54px; }
        .events .kind { font-weight: 600; }

        .cost { display: flex; gap: 14px; margin-bottom: 8px; }
        .cost div { display: flex; flex-direction: column; gap: 1px; }
        .cost span { font-size: 11px; }
        .cost strong { font-size: 14px; letter-spacing: -0.01em; }
        .mylines { list-style: none; margin: 0; padding: 0; font-size: 12px; }
        .mylines li {
          display: grid; grid-template-columns: 1fr auto; gap: 2px 10px;
          padding: 4px 0; border-top: 1px solid var(--border);
        }
        .mylines .what { font-weight: 500; }
        .mylines em {
          grid-column: 1 / -1; font-style: normal; font-size: 11px; color: var(--text-3);
        }
      `}</style>
    </aside>
  );
}

function Row({ k, v, hint }: { k: string; v: string; hint?: string }) {
  return (
    <tr>
      <th style={{ textTransform: 'none', letterSpacing: 0, fontSize: 12 }}>
        {k}
        {hint && <div style={{ color: 'var(--text-3)', fontSize: 11 }}>{hint}</div>}
      </th>
      <td className="n" style={{ textAlign: 'right' }}>
        {v}
      </td>
    </tr>
  );
}

/**
 * How much room is left, as a bar and as a shape over time.
 *
 * The free figure leads. What is *used* is the number a dashboard shows and it
 * is the wrong one here: a machine holding 255 MB is fine or doomed depending
 * entirely on a number that is not on the screen.
 */
function Gauge({
  name,
  free,
  used,
  cap,
  share,
  series,
  t,
  duration,
}: {
  name: string;
  free: number;
  used: number;
  cap: number;
  share: number;
  series: { t: number[]; v: number[] };
  t: number;
  duration: number;
}) {
  const colour = share >= 1 ? D.ALARM : share >= D.WARN_AT ? D.WARN : D.DATA_EDGE;
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, fontSize: 12.5 }}>
        <span className="muted">{name}</span>
        <strong className="mono" style={{ color: colour, fontSize: 13.5 }}>
          {mb(free)} left
        </strong>
        <span className="muted mono" style={{ marginLeft: 'auto', fontSize: 11.5 }}>
          {mb(used)} of {mb(cap)}
        </span>
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 3,
          background: 'var(--surface-2)',
          overflow: 'hidden',
          margin: '4px 0 2px',
        }}
      >
        <div style={{ width: `${Math.min(100, share * 100)}%`, height: '100%', background: colour }} />
      </div>
      <Spark values={series.v} times={series.t} t={t} duration={duration} colour={colour} height={26} cap={cap} />
    </div>
  );
}

/** The whole run, with now marked on it. */
function Spark({
  values,
  times,
  t,
  duration,
  colour,
  height,
  cap,
}: {
  values: number[];
  times: number[];
  t: number;
  duration: number;
  colour: string;
  height: number;
  cap?: number;
}) {
  if (!values.length || !times.length) return null;
  const top = Math.max(cap ?? 0, ...values) || 1;
  const w = 308;
  const pts = values
    .map((v, i) => {
      const x = ((times[Math.min(i, times.length - 1)] ?? 0) / duration) * w;
      const y = height - (v / top) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  const at = (t / duration) * w;
  return (
    <svg width="100%" viewBox={`0 0 ${w} ${height}`} style={{ display: 'block', overflow: 'visible' }}>
      <polyline points={`0,${height} ${pts} ${w},${height}`} fill={colour} opacity={0.13} stroke="none" />
      <polyline points={pts} fill="none" stroke={colour} strokeWidth={1.2} />
      {cap !== undefined && cap > 0 && (
        <line x1={0} x2={w} y1={height - (cap / top) * height} y2={height - (cap / top) * height} stroke={D.ALARM} strokeWidth={0.8} strokeDasharray="3 3" opacity={0.6} />
      )}
      <line x1={at} x2={at} y1={-2} y2={height + 2} stroke="var(--text)" strokeWidth={1} opacity={0.55} />
    </svg>
  );
}

const TOLD = new Set([
  'boot',
  'kill',
  'restart',
  'freeze',
  'thaw',
  'degrade',
  'oom',
  'disk_full',
  'spot_notice',
  'partition',
  'retry',
  'rpc_timeout',
  'job_failed',
]);

function kindColour(kind: string): string {
  if (kind === 'oom' || kind === 'disk_full' || kind === 'kill' || kind === 'job_failed') return D.ALARM;
  if (kind === 'freeze' || kind === 'thaw') return D.CHILL;
  if (kind === 'restart' || kind === 'boot') return '#4F8A5B';
  return D.WARN;
}

function say(e: TraceEvent): string {
  const d = e.detail ?? {};
  const bits: string[] = [];
  for (const k of Object.keys(d)) {
    const v = d[k];
    if (typeof v === 'number') bits.push(`${k} ${v.toLocaleString(undefined, { maximumFractionDigits: 3 })}`);
    else if (typeof v === 'string') bits.push(v);
    else if (typeof v === 'boolean') bits.push(v ? k : `no ${k}`);
  }
  return bits.join(' · ');
}

function num(raw: Record<string, number | string | boolean>, key: string): number {
  return Number(raw[key] ?? 0);
}

/**
 * Where this machine's cross-zone bytes went, largest first.
 *
 * Absent on a trace written before losim recorded the split, and then simply not
 * shown — the total above is still right, and inventing a breakdown for it would
 * be inventing where the traffic went.
 */
function egress(raw: Record<string, number | string | boolean>): [string, number][] {
  const by = (raw as Record<string, unknown>)['egressMb'];
  if (!by || typeof by !== 'object') return [];
  return Object.entries(by as Record<string, number>)
    .map(([region, sent]) => [region, Number(sent)] as [string, number])
    .filter(([, sent]) => sent > 0)
    .sort((a, b) => b[1] - a[1]);
}
