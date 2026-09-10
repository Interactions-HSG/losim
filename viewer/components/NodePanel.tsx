'use client';

/**
 * Everything about one node, at this instant.
 *
 * **A panel, not a tooltip.** A tooltip is for a number. What a viewer wants
 * when they point at a node is everything about it right now, and there is
 * far too much of that to float over the picture: what it is, what it is
 * computing and the *whole* payload rather than the digest, what it has
 * served, and what has happened to it.
 *
 * **It is a slice of the same frame the picture is drawn from**, not a second
 * query path — so it cannot disagree with the node behind it, and it keeps
 * working while the film plays. That last part is the point of pinning: released
 * on hover a node can only be sampled, and pinned it can be *watched*, which
 * is how you see a reducer fill up rather than discover that it did.
 *
 * The sparkline is the whole run with the current instant marked, so the
 * reading has a shape around it. "255 MB of disk" says nothing on its own; "255
 * MB, climbing steadily since the spill started, 180 left" says what is about
 * to happen.
 */
import * as stylex from '@stylexjs/stylex';
import { useMemo } from 'react';

import * as D from '../lib/design.ts';
import { bare, type FrameNode } from '../lib/frame.ts';
import { mb } from './Dataflow.tsx';
import { refTime } from '../lib/playback.ts';
import { Payload } from './Payload.tsx';
import { money as chf, type Ledger } from '../lib/ledger.ts';
import { digest, type Trace, type TraceEvent } from '../lib/trace.ts';
import { Code, H1, H2, P, Table, Td, Th } from '../lib/text.tsx';

import { chrome, shadow, state } from '../lib/tokens.stylex.ts';
import { ui } from '../lib/ui.stylex.ts';

export interface NodePanelProps {
  trace: Trace;
  m: FrameNode;
  t: number;
  /** The bill at this instant, focused on this node. Absent when there is none. */
  money?: Ledger | null;
  pinned: boolean;
  onPin: () => void;
  onClose: () => void;
}

export function NodePanel({ trace, m, t, money, pinned, onPin, onClose }: NodePanelProps) {
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
      {...stylex.props(ui.card, panel.panel)}
      role="dialog"
      aria-label={`node ${m.name}`}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <header {...stylex.props(panel.head)}>
        <div>
          <H1>{m.name}</H1>
          <div {...stylex.props(panel.sub)}>
            {m.instance} · {m.vcpu} vCPU · {m.zone}
          </div>
          {/* Hovering shows this over the picture, and moving the pointer onto it
              takes the pointer off the drawing — which closes it. So while it is
              only hovered it can be read and nothing else, and the way to scroll
              it, or to select anything in it, is to pin it first. */}
          {!pinned && <div {...stylex.props(panel.hint)}>click the node to keep this open</div>}
        </div>
        <div {...stylex.props(panel.acts)}>
          <span
            {...stylex.props(
              panel.state,
              m.state === 'dead' && panel.dead,
              m.state === 'degraded' && panel.degraded,
              m.state === 'frozen' && panel.frozen,
            )}
          >
            {m.state}
          </span>
          <button {...stylex.props(ui.btn, ui.icon)} onClick={onPin} aria-pressed={pinned} title={pinned ? 'unpin' : 'pin'}>
            {pinned ? '📌' : '📍'}
          </button>
        </div>
      </header>

      <section {...stylex.props(panel.section)}>
        <H2>services offered</H2>
        <div {...stylex.props(panel.chips)}>
          {m.serves.length ? (
            m.serves.map((s) => (
              <span key={s} {...stylex.props(ui.chip)}>
                {s}
              </span>
            ))
          ) : (
            <span {...stylex.props(ui.muted)}>nothing — it listens and offers no service</span>
          )}
        </div>
      </section>

      {m.diskCapMb > 0 && (
        <section {...stylex.props(panel.section)}>
          <H2>capacity remaining</H2>
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
        </section>
      )}

      <section {...stylex.props(panel.section)}>
        <H2>current activity</H2>
        {m.work.length === 0 ? (
          <P style={[ui.muted, panel.flat]}>
            idle
          </P>
        ) : (
          m.work.map((w) => (
            <div key={w.span.id} {...stylex.props(panel.work)}>
              <div {...stylex.props(panel.wtop)}>
                <span {...stylex.props(panel.dot)} style={{ background: D.taskColour(w.task) }} />
                <strong>{bare(w.label)}</strong>
                {w.task !== null && <span {...stylex.props(ui.muted)}>task {w.task}</span>}
                <span {...stylex.props(ui.muted, ui.mono)} style={{ marginLeft: 'auto' }}>
                  {refTime(t - w.span.t0)} in
                </span>
              </div>
              <Payload detail={w.span.detail} />
            </div>
          ))
        )}
        <div {...stylex.props(panel.lanes)}>
          <span {...stylex.props(ui.muted)}>
            {Math.round(m.busy)}% of {m.vcpu} cores
          </span>
          {m.queued > 0 && <span style={{ color: D.WARN }}>{Math.round(m.queued)} waiting for a core</span>}
          {m.inflight > 0 && <span {...stylex.props(ui.muted)}>{Math.round(m.inflight)} calls in flight</span>}
        </div>
        <Spark values={busy.v} times={busy.t} t={t} duration={trace.duration} colour={D.taskColour(0)} height={22} />
      </section>

      {totals && (
        <section {...stylex.props(panel.section)}>
          <H2>over the whole run</H2>
          <Table>
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
          </Table>
        </section>
      )}

      {money?.focus && money.focus.name === m.name && (
        <section {...stylex.props(panel.section)}>
          <H2>cost</H2>
          <div {...stylex.props(panel.cost)}>
            <div {...stylex.props(panel.costCol)}>
              <span {...stylex.props(ui.muted)}>so far</span>
              <strong {...stylex.props(ui.mono)}>{chf(money.focus.cost, money.currency)}</strong>
            </div>
            <div {...stylex.props(panel.costCol)}>
              <span {...stylex.props(ui.muted)}>by the end</span>
              <strong {...stylex.props(ui.mono)}>{chf(money.focus.finalCost, money.currency)}</strong>
            </div>
            <div {...stylex.props(panel.costCol)}>
              <span {...stylex.props(ui.muted)}>of the cluster</span>
              <strong {...stylex.props(ui.mono)}>
                {Math.round((money.focus.cost / Math.max(money.cost, 1e-9)) * 100)}%
              </strong>
            </div>
          </div>
          <ul {...stylex.props(panel.mylines)}>
            {money.lines
              .filter((x) => x.mine > 0)
              .slice(0, 5)
              .map((x, i) => (
                <li key={i} {...stylex.props(panel.myline)}>
                  <span {...stylex.props(panel.what)}>{x.line.what}</span>
                  <span {...stylex.props(ui.mono)}>{chf(x.mine, money.currency)}</span>
                  <em {...stylex.props(panel.why)}>{x.why}</em>
                </li>
              ))}
          </ul>
          <P style={[ui.muted, panel.small]}>
            Its share of lines <Code>losim bill</Code> already computed. The late-finish
            penalty belongs to the job and is not here.
          </P>
        </section>
      )}

      <section {...stylex.props(panel.section)}>
        <H2>events</H2>
        {mine.length === 0 ? (
          <P style={[ui.muted, panel.flat]}>
            nothing — it ran to the end untouched
          </P>
        ) : (
          <ol {...stylex.props(panel.events)}>
            {mine.map((e, i) => (
              <li key={i} {...stylex.props(panel.ev, Number(e.t ?? 0) > t && panel.later)}>
                <span {...stylex.props(ui.mono, panel.when)}>{refTime(Number(e.t ?? 0))}</span>
                <span {...stylex.props(panel.kind)} style={{ color: kindColour(String(e.kind)) }}>
                  {String(e.kind).replace(/_/g, ' ')}
                </span>
                <span {...stylex.props(ui.muted)}>{say(e)}</span>
              </li>
            ))}
          </ol>
        )}
      </section>

    </aside>
  );
}

function Row({ k, v, hint }: { k: string; v: string; hint?: string }) {
  return (
    <tr>
      <Th style={panel.plainHead}>
        {k}
        {hint && <div style={{ color: chrome.text3, fontSize: 11 }}>{hint}</div>}
      </Th>
      <Td num style={panel.right}>
        {v}
      </Td>
    </tr>
  );
}

/**
 * How much room is left, as a bar and as a shape over time.
 *
 * The free figure leads. What is *used* is the number a dashboard shows and it
 * is the wrong one here: a node holding 255 MB is fine or doomed depending
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
        <span {...stylex.props(ui.muted)}>{name}</span>
        <strong {...stylex.props(ui.mono)} style={{ color: colour, fontSize: 13.5 }}>
          {mb(free)} left
        </strong>
        <span {...stylex.props(ui.muted, ui.mono)} style={{ marginLeft: 'auto', fontSize: 11.5 }}>
          {mb(used)} of {mb(cap)}
        </span>
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 3,
          background: chrome.surface2,
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
      <line x1={at} x2={at} y1={-2} y2={height + 2} stroke={chrome.text} strokeWidth={1} opacity={0.55} />
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
  'heal',
  'retry',
  'retry_done',
  'rpc_timeout',
  'rpc_error',
  'rpc_failure',
  'failed',
  // What this node said about itself, in time order among the things that
  // were done to it — which is the order a narration is written to be read in.
  'log',
]);

function kindColour(kind: string): string {
  if (kind === 'oom' || kind === 'disk_full' || kind === 'kill' || kind === 'failed') return D.ALARM;
  if (kind === 'freeze' || kind === 'thaw') return D.CHILL;
  if (kind === 'restart' || kind === 'boot') return '#4F8A5B';
  // Narration is not a warning about anything, and amber would say it was.
  if (kind === 'log') return D.NARRATE;
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
 * Where this node's cross-zone bytes went, largest first.
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


const panel = stylex.create({
  flat: { margin: 0 },
  small: { fontSize: '11px', margin: '6px 0 0' },
  /** One header that is a label rather than a column name, so it is not shouted. */
  plainHead: { textTransform: 'none', letterSpacing: 0, fontSize: '12px' },
  right: { textAlign: 'right' },

  /**
   * `pointerEvents: auto` because the dock it sits in is `none`, so the film
   * behind it stays clickable. Film.tsx said that with `.dock > *`, which StyleX
   * cannot select.
   */
  panel: {
    width: '340px',
    maxHeight: '100%',
    overflowY: 'auto',
    paddingTop: '14px',
    paddingInline: '16px',
    paddingBottom: '18px',
    boxShadow: shadow.s3,
    pointerEvents: 'auto',
  },
  /** Sticky, so the node's name stays while its detail scrolls under it. */
  head: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: '10px',
    paddingBottom: '12px',
    marginBottom: '12px',
    borderBottomWidth: '1px',
    borderBottomStyle: 'solid',
    borderBottomColor: chrome.border,
    position: 'sticky',
    top: '-14px',
    backgroundColor: chrome.surface,
    zIndex: 2,
    paddingTop: '14px',
    marginTop: '-14px',
  },
  section: { marginBottom: '16px' },
  sub: { fontSize: '12px', color: chrome.text3, marginTop: '1px' },
  hint: { fontSize: '11px', color: chrome.text3, marginTop: '3px', fontStyle: 'italic' },
  acts: { marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '6px' },

  state: {
    fontSize: '11px',
    fontWeight: 600,
    letterSpacing: '.03em',
    textTransform: 'uppercase',
    paddingBlock: '2px',
    paddingInline: '7px',
    borderRadius: '999px',
    backgroundColor: chrome.surface2,
    color: chrome.text2,
  },
  dead: { backgroundColor: state.deadBg, color: state.deadInk },
  degraded: { backgroundColor: state.degradedBg, color: state.degradedInk },
  frozen: { backgroundColor: state.frozenBg, color: state.frozenInk },

  chips: { display: 'flex', flexWrap: 'wrap', gap: '5px' },
  work: {
    paddingBlock: '8px',
    borderBottomWidth: '1px',
    borderBottomStyle: 'solid',
    borderBottomColor: chrome.border,
  },
  wtop: { display: 'flex', alignItems: 'center', gap: '7px', fontSize: '12.5px', marginBottom: '5px' },
  dot: { width: '7px', height: '7px', borderRadius: '50%', flex: 'none' },
  lanes: { display: 'flex', gap: '12px', fontSize: '12px', marginTop: '6px' },

  events: { listStyle: 'none', margin: 0, padding: 0, fontSize: '12.5px' },
  ev: { display: 'flex', gap: '8px', paddingBlock: '3px', alignItems: 'baseline' },
  /** What has not happened yet, still on screen so the order is readable. */
  later: { opacity: 0.35 },
  when: { color: chrome.text3, minWidth: '54px' },
  kind: { fontWeight: 600 },

  cost: { display: 'flex', gap: '14px', marginBottom: '8px' },
  costCol: { display: 'flex', flexDirection: 'column', gap: '1px' },
  mylines: { listStyle: 'none', margin: 0, padding: 0, fontSize: '12px' },
  myline: {
    display: 'grid',
    gridTemplateColumns: '1fr auto',
    gap: '2px 10px',
    paddingBlock: '4px',
    borderTopWidth: '1px',
    borderTopStyle: 'solid',
    borderTopColor: chrome.border,
  },
  what: { fontWeight: 500 },
  why: { gridColumn: '1 / -1', fontStyle: 'normal', fontSize: '11px', color: chrome.text3 },
});
