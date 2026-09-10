'use client';

/**
 * What every node was doing, up to the clock.
 *
 * The film answers "what is happening"; this answers "how hard is it working",
 * which is a different question and is usually the one that settles an argument.
 * A node that finishes last because it is small and a node that finishes
 * last because it is far away look identical on a total. They do not look
 * identical here: one of them is at ninety percent and one of them is at twenty.
 *
 * Everything is read straight off the trace's own channels — the numbers DISSALy
 * recorded while it ran, at the tick rate it recorded them at. Nothing is
 * modelled, smoothed or filled in.
 */
import { useMemo, useState } from 'react';
import * as stylex from '@stylexjs/stylex';

import { colourOf, LineChart, Legend, Spark, type Series } from './Chart.tsx';
import { Head, Panel } from './Shell.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { refTime } from '../../lib/playback.ts';
import type { Trace } from '../../lib/trace.ts';
import { A, Code, P, Table, Td, Th } from '../../lib/text.tsx';
import { MB, reading, sizeUnit, type Unit } from '../../lib/units.ts';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome, font } from '../../lib/tokens.stylex.ts';
import { Projected } from './Projected.tsx';

interface Metric {
  id: string;
  label: string;
  unit: string;
  /** A fixed axis top, where the metric has one of its own. */
  max?: number;
  divs?: number;
  /** How many decimals this metric is worth reading to. A percentage has none. */
  dp: number;
  /**
   * A size in megabytes, so it is written in whatever unit it has grown into.
   *
   * A percentage is a percentage at any magnitude and a queue of 4,000 is a
   * queue of 4,000. Bytes are the one thing here that changes its name as it
   * grows, and a scaled run is exactly where it does.
   */
  size?: boolean;
  note: string;
}

const METRICS: Metric[] = [
  { id: 'busyPct', label: 'CPU', unit: '%', max: 100, dp: 0, note: 'Node core utilization at the selected time.' },
  { id: 'bytesOutMb', label: 'Bytes out', unit: 'MB', dp: 2, size: true, note: 'Cumulative egress volume.' },
  { id: 'inflight', label: 'Calls in flight', unit: '', divs: 2, dp: 0, note: 'Handlers active at the selected time.' },
  { id: 'queued', label: 'Queued', unit: '', divs: 2, dp: 0, note: 'Calls waiting for a core.' },
  { id: 'diskPct', label: 'Disk', unit: '%', max: 100, dp: 0, note: 'Share of the disk cap in use.' },
];

/** At most this many points per series: a path with a segment per pixel is a solid line. */
const CAP = 240;

/** One node's whole run of one metric, thinned to something a path can carry. */
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
        trace.nodes.map((mc) => ({ name: mc.name, pts: whole(trace, mc.name, m.id) })),
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

  /**
   * The unit each metric is written in, decided once from the whole run.
   *
   * From the top of the axis rather than per value, so that the ruler, the
   * readings under it and the peaks in the table are all in one unit and can be
   * compared with each other. Deciding per cell would put `4.00 MB` in the row
   * above `1.24 GB` and leave the reader to notice.
   */
  const units = useMemo(() => {
    const out = new Map<string, Unit>();
    for (const m of have) out.set(m.id, m.size ? sizeUnit(tops.get(m.id) ?? 0) : { unit: m.unit, per: 1 });
    return out;
  }, [have, tops]);

  if (!run || !trace) return null;
  const metric = have.find((m) => m.id === pick) ?? have[0];
  if (!metric) {
    return (
      <>
        <Head title="Usage" sub="Node activity" />
        <Panel>
          <P style={ui.muted}>
            This trace has no telemetry channels. Run the simulation without <Code>--quiet</Code> to
            record node activity.
          </P>
        </Panel>
        {/* A model still has its answers, and they do not come from the channels.
            Leaving them out here made turning telemetry off delete the only figures
            on the page that were not going to be drawn anyway. */}
        <Projected trace={trace} />
      </>
    );
  }

  const series = (m: Metric): Series[] =>
    (all.get(m.id) ?? []).map((s, i) => ({
      name: s.name,
      color: colourOf(i),
      pts: upTo(s.pts, now),
    }));

  /** A reading of the metric on show, in the unit its axis is written in. */
  const shown = units.get(metric.id) ?? MB;
  const say = (v: number) =>
    reading(v / shown.per, shown.unit, shown.per === 1 ? metric.dp : 2);

  return (
    <>
      <Head
        crumbs={
          <>
            <A href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</A>
            {' / '}
            <A href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</A>
            {' / Usage'}
          </>
        }
        title="Usage"
        sub={
          <>
            {trace.nodes.length} nodes through {refTime(now)} of {refTime(trace.duration)}. The axis
            spans the full run; moving the clock changes the displayed data.
            {trace.scaled && (
              <>
                {' '}This trace executed {trace.scaled.units.toLocaleString()} units. The panel below
                reports the model for {trace.scaled.fullUnits.toLocaleString()} units.
              </>
            )}
          </>
        }
      />

      <Projected trace={trace} />

      <Panel flush>
        <div {...stylex.props(sx.tools)}>
          <span {...stylex.props(sx.lb)}>Metric</span>
          <div {...stylex.props(ui.seg)} role="group" aria-label="Metric">
            {have.map((m) => (
              <button
                key={m.id}
                {...stylex.props(ui.segButton, m.id === metric.id && ui.segOn)}
                aria-pressed={m.id === metric.id}
                onClick={() => setPick(m.id)}
              >
                {m.label}
              </button>
            ))}
          </div>
          <span {...stylex.props(sx.note)}>{metric.note}</span>
        </div>
        <LineChart
          series={series(metric)}
          duration={trace.duration}
          now={now}
          yMax={tops.get(metric.id) ?? 1}
          height={280}
          divs={metric.divs ?? 4}
          unit={(units.get(metric.id) ?? MB).unit}
          per={(units.get(metric.id) ?? MB).per}
          label={metric.label}
        />
        <div {...stylex.props(sx.pad)}>
          <Legend keys={trace.nodes.map((m) => m.name)} colour={(k) => colourOf(trace.nodes.findIndex((m) => m.name === k))} />
        </div>
      </Panel>

      <div {...stylex.props(sx.grid)}>
        {have
          .filter((m) => m.id !== metric.id)
          .map((m) => (
            <Panel key={m.id} title={m.label} note={(units.get(m.id) ?? MB).unit || 'count'} flush
                   actions={<button {...stylex.props(ui.btn)} onClick={() => setPick(m.id)}>Expand</button>}>
              <LineChart
                series={series(m)}
                duration={trace.duration}
                now={now}
                yMax={tops.get(m.id) ?? 1}
                height={150}
                divs={m.divs ?? 4}
                unit={(units.get(m.id) ?? MB).unit}
                per={(units.get(m.id) ?? MB).per}
                label={m.label}
              />
            </Panel>
          ))}
      </div>

      <Panel title="Per-node usage" note={`Metrics through ${refTime(now)}`} flush>
        <div {...stylex.props(sx.scroll)}>
          <Table>
            <thead>
              <tr>
                <Th>Node</Th>
                <Th>Instance</Th>
                <Th>Zone</Th>
                <Th style={sx.right}>{metric.label} at selected time</Th>
                <Th style={sx.right}>Peak</Th>
                <Th>Trend</Th>
              </tr>
            </thead>
            <tbody>
              {trace.nodes.map((mc, i) => {
                const pts = upTo((all.get(metric.id) ?? [])[i]?.pts ?? [], now);
                const peak = pts.reduce((a, p) => Math.max(a, p[1]), 0);
                const value = pts.length ? pts[pts.length - 1][1] : 0;
                return (
                  <tr key={mc.name}>
                    <Td style={sx.id}>{mc.name}</Td>
                    <Td>{mc.instance}</Td>
                    <Td style={ui.muted}>{mc.zone}</Td>
                    <Td num style={sx.right}>{say(value)}</Td>
                    <Td num style={sx.right}>{say(peak)}</Td>
                    <Td style={sx.sp}>
                      <Spark pts={pts} colour={colourOf(i)} max={tops.get(metric.id) ?? 1} />
                    </Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        </div>
      </Panel>

    </>
  );
}

const sx = stylex.create({
  tools: {
    display: 'flex',
    alignItems: 'center',
    gap: '14px',
    flexWrap: 'wrap',
    paddingTop: 0,
    paddingInline: '20px',
    paddingBottom: '12px',
  },
  lb: { fontSize: '12px', fontWeight: 500, color: chrome.text3 },
  note: { fontSize: '12.5px', color: chrome.text3 },
  pad: { paddingTop: 0, paddingInline: '20px', paddingBottom: '16px' },
  grid: {
    display: 'grid',
    gap: '20px',
    gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))',
  },
  scroll: { overflowX: 'auto', paddingTop: 0, paddingInline: '20px', paddingBottom: '8px' },
  id: { fontFamily: font.mono, fontWeight: 500 },
  /** Numbers and the headings over them, on the same edge. `Td num` does the rest. */
  right: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  sp: { width: '130px' },
});
