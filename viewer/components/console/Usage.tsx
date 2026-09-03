'use client';

/**
 * What every machine was doing, up to the clock.
 *
 * The film answers "what is happening"; this answers "how hard is it working",
 * which is a different question and is usually the one that settles an argument.
 * A machine that finishes last because it is small and a machine that finishes
 * last because it is far away look identical on a total. They do not look
 * identical here: one of them is at ninety percent and one of them is at twenty.
 *
 * Everything is read straight off the trace's own channels — the numbers losim
 * recorded while it ran, at the tick rate it recorded them at. Nothing is
 * modelled, smoothed or filled in.
 */
import { useMemo, useState } from 'react';

import { colourOf, LineChart, Legend, Spark, type Series } from './Chart.tsx';
import { Head, Panel } from './Shell.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { refTime } from '../../lib/playback.ts';
import type { Trace } from '../../lib/trace.ts';

interface Metric {
  id: string;
  label: string;
  unit: string;
  /** A fixed axis top, where the metric has one of its own. */
  max?: number;
  divs?: number;
  /** How many decimals this metric is worth reading to. A percentage has none. */
  dp: number;
  note: string;
}

const METRICS: Metric[] = [
  { id: 'busyPct', label: 'CPU', unit: '%', max: 100, dp: 0, note: 'of the machine’s cores, at this instant' },
  { id: 'memPct', label: 'Memory', unit: '%', max: 100, dp: 0, note: 'of its cap — past 75% is where a machine starts to be in trouble' },
  { id: 'retainMb', label: 'Retained heap', unit: 'MB', dp: 2, note: 'what your code is holding on to, not what it allocated' },
  { id: 'bytesOutMb', label: 'Bytes out', unit: 'MB', dp: 2, note: 'cumulative — the line the egress bill is drawn from' },
  { id: 'inflight', label: 'Calls in flight', unit: '', divs: 2, dp: 0, note: 'handlers running on it right now' },
  { id: 'queued', label: 'Queued', unit: '', divs: 2, dp: 0, note: 'calls waiting for a core. A queue that never empties is a machine too small' },
  { id: 'diskPct', label: 'Disk', unit: '%', max: 100, dp: 0, note: 'of its disk cap' },
];

/** A reading, with the unit attached the way that unit is written. */
function reading(v: number, m: Metric): string {
  const n = v.toFixed(m.dp);
  return m.unit === '%' ? `${n}%` : m.unit ? `${n} ${m.unit}` : n;
}

/** At most this many points per series: a path with a segment per pixel is a solid line. */
const CAP = 240;

/** One machine's whole run of one metric, thinned to something a path can carry. */
function whole(trace: Trace, vm: string, metric: string): [number, number][] {
  const { t, v } = trace.series(vm, metric);
  if (!t.length) return [];
  const stride = Math.max(1, Math.ceil(t.length / CAP));
  const out: [number, number][] = [];
  for (let i = 0; i < t.length; i += stride) out.push([t[i], v[Math.min(i, v.length - 1)] ?? 0]);
  const last = t.length - 1;
  if (out[out.length - 1]?.[0] !== t[last]) out.push([t[last], v[Math.min(last, v.length - 1)] ?? 0]);
  return out;
}

/** The part of it that has happened, plus the value it is holding right now. */
function upTo(pts: [number, number][], now: number): [number, number][] {
  let k = 0;
  while (k < pts.length && pts[k][0] <= now) k++;
  const cut = pts.slice(0, k);
  if (k > 0 && k < pts.length) cut.push([now, pts[k - 1][1]]);
  else if (k === 0 && pts.length) cut.push([Math.min(now, pts[0][0]), pts[0][1]]);
  return cut;
}

export function Usage() {
  const { run, go } = useConsole();
  const now = useNow();
  const [pick, setPick] = useState('busyPct');

  const trace = run?.trace;

  const have = useMemo(() => {
    if (!trace) return [] as Metric[];
    const suffixes = new Set(trace.channelNames().map((n) => n.slice(n.indexOf('.') + 1)));
    return METRICS.filter((m) => suffixes.has(m.id));
  }, [trace]);

  /**
   * Every series, in full, built once.
   *
   * The clock ticks sixty times a second and the shape of the run does not
   * change while it does — only how much of it you are allowed to see. So the
   * whole thing is computed here and sliced below, which turns a frame from a
   * few thousand channel lookups into an array slice.
   */
  const all = useMemo(() => {
    const out = new Map<string, { name: string; pts: [number, number][] }[]>();
    if (!trace) return out;
    for (const m of have) {
      out.set(
        m.id,
        trace.machines.map((mc) => ({ name: mc.name, pts: whole(trace, mc.name, m.id) })),
      );
    }
    return out;
  }, [trace, have]);

  /** The ruler, from the whole run. Never from the part of it you can see. */
  const tops = useMemo(() => {
    const out = new Map<string, number>();
    for (const m of have) {
      if (m.max !== undefined) {
        out.set(m.id, m.max);
        continue;
      }
      let top = 0;
      for (const s of all.get(m.id) ?? []) for (const p of s.pts) if (p[1] > top) top = p[1];
      out.set(m.id, top);
    }
    return out;
  }, [have, all]);

  if (!run || !trace) return null;
  const metric = have.find((m) => m.id === pick) ?? have[0];
  if (!metric) {
    return (
      <>
        <Head title="Usage" sub="what each machine was doing" />
        <Panel>
          <p className="muted">
            This trace carries no channels — it was recorded with telemetry off. Run it again
            without <code>--quiet</code> and every machine gets a line here.
          </p>
        </Panel>
      </>
    );
  }

  const series = (m: Metric): Series[] =>
    (all.get(m.id) ?? []).map((s, i) => ({
      name: s.name,
      color: colourOf(i),
      pts: upTo(s.pts, now),
    }));

  return (
    <>
      <Head
        crumbs={
          <>
            <a href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</a>
            {' / '}
            <a href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</a>
            {' / Usage'}
          </>
        }
        title="Usage"
        sub={
          <>
            {trace.machines.length} machines, drawn to {refTime(now)} of{' '}
            {refTime(trace.duration)}. The axis is fixed to the whole run, so dragging the clock
            moves the drawing and never the ruler under it.
          </>
        }
      />

      <Panel flush>
        <div className="tools">
          <span className="lb">Metric</span>
          <div className="seg" role="group" aria-label="metric">
            {have.map((m) => (
              <button key={m.id} aria-pressed={m.id === metric.id} onClick={() => setPick(m.id)}>
                {m.label}
              </button>
            ))}
          </div>
          <span className="note">{metric.note}</span>
        </div>
        <LineChart
          series={series(metric)}
          duration={trace.duration}
          now={now}
          yMax={tops.get(metric.id) ?? 1}
          height={280}
          divs={metric.divs ?? 4}
          unit={metric.unit}
          label={metric.label}
        />
        <div className="pad">
          <Legend keys={trace.machines.map((m) => m.name)} colour={(k) => colourOf(trace.machines.findIndex((m) => m.name === k))} />
        </div>
      </Panel>

      <div className="grid">
        {have
          .filter((m) => m.id !== metric.id)
          .map((m) => (
            <Panel key={m.id} title={m.label} note={m.unit || 'count'} flush
                   actions={<button className="btn" onClick={() => setPick(m.id)}>Expand</button>}>
              <LineChart
                series={series(m)}
                duration={trace.duration}
                now={now}
                yMax={tops.get(m.id) ?? 1}
                height={150}
                divs={m.divs ?? 4}
                unit={m.unit}
                label={m.label}
              />
            </Panel>
          ))}
      </div>

      <Panel title="Per machine, up to the clock" note={`everything below counts only what has happened by ${refTime(now)}`} flush>
        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th>Machine</th>
                <th>Instance</th>
                <th>Zone</th>
                <th className="r">{metric.label} now</th>
                <th className="r">Peak so far</th>
                <th>Shape so far</th>
              </tr>
            </thead>
            <tbody>
              {trace.machines.map((mc, i) => {
                const pts = upTo((all.get(metric.id) ?? [])[i]?.pts ?? [], now);
                const peak = pts.reduce((a, p) => Math.max(a, p[1]), 0);
                const value = pts.length ? pts[pts.length - 1][1] : 0;
                return (
                  <tr key={mc.name}>
                    <td className="id">{mc.name}</td>
                    <td>{mc.instance}</td>
                    <td className="muted">{mc.zone}</td>
                    <td className="n">{reading(value, metric)}</td>
                    <td className="n">{reading(peak, metric)}</td>
                    <td className="sp">
                      <Spark pts={pts} colour={colourOf(i)} max={tops.get(metric.id) ?? 1} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Panel>

      <style>{`
        .tools { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 0 20px 12px; }
        .tools .lb { font-size: 12px; font-weight: 500; color: var(--text-3); }
        .tools .note { font-size: 12.5px; color: var(--text-3); }
        .pad { padding: 0 20px 16px; }
        .grid { display: grid; gap: 20px; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); }
        .scroll { overflow-x: auto; padding: 0 20px 8px; }
        td.id, .id { font-family: var(--mono); font-weight: 500; }
        th.r, td.n { text-align: right; font-variant-numeric: tabular-nums; }
        td.n { font-family: var(--mono); }
        td.sp { width: 130px; }
      `}</style>
    </>
  );
}
