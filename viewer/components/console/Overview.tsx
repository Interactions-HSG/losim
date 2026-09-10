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
import * as stylex from '@stylexjs/stylex';

import { LineChart } from './Chart.tsx';
import { Head, Panel, Tile } from './Shell.tsx';
import { Spans } from '../Spans.tsx';
import { Topology } from '../Topology.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { BUCKETS, money } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import { useTheme } from '../../lib/theme.ts';
import { A, Code, H2, Kbd, P, Table, Td, Th } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome, font, state } from '../../lib/tokens.stylex.ts';

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
    <section {...stylex.props(sx.step)}>
      <header {...stylex.props(sx.header)}>
        <span {...stylex.props(sx.count)} aria-hidden>{n}</span>
        <div {...stylex.props(sx.question)}>
          <H2 style={sx.ask}>{q}</H2>
          <P style={sx.say}>{say}</P>
        </div>
        {aside && <div {...stylex.props(sx.aside)}>{aside}</div>}
      </header>
      {children}
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
            <A href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</A>
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
            {trace.scaled && (
              <>
                {' '}This one is a <strong>model</strong>: it ran{' '}
                {trace.scaled.units.toLocaleString()} units and stands for{' '}
                {trace.scaled.fullUnits.toLocaleString()}, so every figure on this page is the
                run that executed. What it says about the full size is on Usage and on Cost.
              </>
            )}
          </>
        }
        actions={<button {...stylex.props(ui.btn, ui.primary)} onClick={() => go('film')}>▶ Watch it</button>}
      />

      {/* Four numbers, and each one is a sentence. A tile that says `19` and
          nothing else is a number somebody has to go and find the meaning of. */}
      <div {...stylex.props(sx.tiles)}>
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
        <div {...stylex.props(ui.card, sx.map)} ref={mapBox}>
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
        aside={<button {...stylex.props(ui.btn)} onClick={() => go('film')}>Watch it instead</button>}
      >
        <div {...stylex.props(sx.calls)}>
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
              <> Right now the busiest is <Code>{busiest.name}</Code>.</>
            )}
          </>
        }
        aside={<button {...stylex.props(ui.btn)} onClick={() => go('usage')}>Charts over time</button>}
      >
        <Panel flush>
          <div {...stylex.props(sx.scroll)}>
            <Table>
              <thead>
                <tr>
                  <Th>Node</Th>
                  <Th>Zone</Th>
                  <Th>Serves</Th>
                  <Th style={sx.right}>In flight</Th>
                  <Th style={sx.right}>Queued</Th>
                  <Th>Doing</Th>
                </tr>
              </thead>
              <tbody>
                {(frame?.nodes ?? []).map((m) => (
                  <tr key={m.name}>
                    <Td style={sx.id}>{m.name}</Td>
                    <Td style={ui.muted}>{m.zone}</Td>
                    <Td style={ui.muted}>{m.serves.join(', ') || '—'}</Td>
                    <Td num style={sx.right}>{m.inflight}</Td>
                    <Td num style={sx.right}>{m.queued}</Td>
                    <Td>
                      <span {...stylex.props(sx.state, STATE[m.state] ?? sx.alive)}>{m.state}</span>
                      {m.work.length > 0 && <span {...stylex.props(ui.muted)}> · {m.work[0].method}</span>}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
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
              Nothing by {refTime(now)}. Press <Kbd>]</Kbd> to jump straight to the next thing
              that broke, rather than hunting for it with the scrubber.
            </>
          )
        }
      >
        <Panel flush>
          {wrong.length ? (
            <ul {...stylex.props(sx.events)}>
              {[...wrong].reverse().slice(0, 14).map((e, i) => (
                <li key={i} {...stylex.props(i > 0 && sx.ruled)}>
                  <button
                    {...stylex.props(sx.event)}
                    onClick={() => {
                      clock?.pause();
                      clock?.seek(Number(e.t ?? 0));
                    }}
                    title="move the clock to this moment"
                  >
                    <span {...stylex.props(sx.when, ui.mono)}>{refTime(Number(e.t ?? 0))}</span>
                    <span {...stylex.props(sx.kind, KIND[String(e.kind)])}>{e.kind}</span>
                    <span {...stylex.props(ui.muted)}>{PLAIN[String(e.kind)] ?? 'something the trace records'}</span>
                    {e.vm && <span {...stylex.props(sx.who, ui.mono)}>{String(e.vm)}</span>}
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <P style={[sx.pad, ui.muted]}>Nothing yet.</P>
          )}
          {wrong.length > 14 && (
            <P style={[sx.pad, ui.muted]}>{wrong.length - 14} earlier ones, not listed.</P>
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
        aside={<button {...stylex.props(ui.btn)} onClick={() => go('cost')}>The full bill</button>}
      >
        {l && finalBuckets ? (
          <Panel>
            <div {...stylex.props(sx.money)}>
              <div>
                <div {...stylex.props(sx.big)}>{money(l.cost, l.currency)}</div>
                <P style={sx.note}>
                  as at <span {...stylex.props(ui.mono)}>{refTime(now)}</span> of {refTime(trace.duration)}
                </P>
                <div {...stylex.props(sx.stack)}>
                  {BUCKETS.map((b) => (
                    <i
                      key={b}
                      {...stylex.props(sx.slice)}
                      style={{
                        width: `${(l.buckets[b] / Math.max(l.cost, 1e-9)) * 100}%`,
                        background: COLOUR[b],
                      }}
                    />
                  ))}
                </div>
                <dl {...stylex.props(sx.kv)}>
                  {BUCKETS.map((b, i) => (
                    <div key={b} {...stylex.props(sx.kvRow, i > 0 && sx.ruled)}>
                      <dt {...stylex.props(sx.kvKey)}>
                        <i {...stylex.props(sx.swatch)} style={{ background: COLOUR[b] }} />
                        {b}
                      </dt>
                      <dd {...stylex.props(sx.kvValue)}>{money(l.buckets[b], l.currency)}</dd>
                    </div>
                  ))}
                  <div {...stylex.props(sx.kvRow, sx.total)}>
                    <dt {...stylex.props(sx.kvKey)}>Total</dt>
                    <dd {...stylex.props(sx.kvValue)}>{money(l.cost, l.currency)}</dd>
                  </div>
                </dl>
              </div>
              <div>
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
                <P style={sx.afterChart}>
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
                </P>
              </div>
            </div>
          </Panel>
        ) : (
          <Panel>
            <P style={ui.muted}>
              Run <Code>dissaly bill</Code> next to this trace and this fills in — the viewer will
              not invent prices of its own.
            </P>
          </Panel>
        )}
      </Step>

    </>
  );
}

const sx = stylex.create({
  step: { display: 'flex', flexDirection: 'column', gap: '12px' },
  header: { display: 'flex', alignItems: 'flex-start', gap: '14px' },
  /* Named apart from the table conventions on purpose: a bare `n` here would
     also have matched every right-aligned number cell inside the section. */
  count: {
    flexGrow: 0,
    flexShrink: 0,
    width: '26px',
    height: '26px',
    borderRadius: '50%',
    display: 'grid',
    placeItems: 'center',
    marginTop: '1px',
    backgroundColor: chrome.accentSoft,
    color: chrome.accent,
    fontSize: '13px',
    fontWeight: 600,
    fontVariantNumeric: 'tabular-nums',
  },
  question: { minWidth: 0 },
  ask: { margin: 0, fontSize: '17px', fontWeight: 500, letterSpacing: '-0.01em' },
  say: {
    marginTop: '3px',
    marginRight: 0,
    marginBottom: 0,
    marginLeft: 0,
    fontSize: '13px',
    color: chrome.text3,
    maxWidth: '68ch',
  },
  aside: { marginLeft: 'auto', flexGrow: 0, flexShrink: 0, display: 'flex', gap: '8px', alignItems: 'center' },

  tiles: { display: 'grid', gap: '16px', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))' },
  /* The map and the call tree both draw into whatever room they are given, so
     they are the two things on this page with a height of their own. */
  map: { height: 'clamp(300px, 42vh, 480px)', padding: 0, overflow: 'hidden', display: 'flex' },
  calls: { display: 'flex', flexDirection: 'column', height: 'clamp(400px, 56vh, 660px)' },

  scroll: { overflowX: 'auto', paddingTop: 0, paddingInline: '20px', paddingBottom: '8px' },
  pad: { marginBlock: 0, paddingBlock: '14px', paddingInline: '20px' },
  id: { fontFamily: font.mono, fontWeight: 500 },
  right: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },

  state: { fontSize: '11.5px', fontWeight: 500 },
  alive: { color: chrome.text2 },
  degraded: { color: state.degradedInk },
  frozen: { color: state.frozenInk },
  dead: { color: chrome.danger },

  money: {
    display: 'grid',
    gap: '24px',
    gridTemplateColumns: {
      default: 'minmax(0, 260px) minmax(0, 1fr)',
      '@media (max-width: 900px)': '1fr',
    },
    alignItems: 'start',
  },
  big: { fontSize: '30px', fontWeight: 500, letterSpacing: '-0.02em', fontVariantNumeric: 'tabular-nums' },
  note: { marginTop: '4px', marginRight: 0, marginBottom: '12px', marginLeft: 0, fontSize: '12.5px', color: chrome.text3 },
  /** The same note, under the chart instead of over the stack. */
  afterChart: { marginTop: '10px', marginRight: 0, marginBottom: 0, marginLeft: 0, fontSize: '12.5px', color: chrome.text3 },
  stack: { display: 'flex', height: '10px', borderRadius: '5px', overflow: 'hidden', backgroundColor: chrome.surface2 },
  slice: { display: 'block', height: '100%' },
  kv: { marginTop: '12px', marginRight: 0, marginBottom: 0, marginLeft: 0, display: 'flex', flexDirection: 'column' },
  kvRow: { display: 'flex', justifyContent: 'space-between', gap: '12px', paddingBlock: '7px', fontSize: '13px' },
  kvKey: { display: 'flex', alignItems: 'center', gap: '8px', color: chrome.text2 },
  kvValue: { margin: 0, fontFamily: font.mono, fontVariantNumeric: 'tabular-nums' },
  swatch: { width: '9px', height: '9px', borderRadius: '2px' },
  /**
   * What `+ div` used to draw. The row knows whether it is the first one, which
   * is the same fact the sibling selector was reading — and the total's heavier
   * rule no longer needs `!important` to win, because nothing is competing.
   */
  ruled: { borderTopWidth: '1px', borderTopStyle: 'solid', borderTopColor: chrome.border },
  total: {
    fontWeight: 600,
    borderTopWidth: '2px',
    borderTopStyle: 'solid',
    borderTopColor: chrome.text,
    marginTop: '4px',
  },

  /* Every accident is a place on the clock, so every accident is a button. */
  events: { listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column' },
  event: {
    display: 'flex',
    gap: '12px',
    alignItems: 'baseline',
    width: '100%',
    paddingBlock: '9px',
    paddingInline: '20px',
    font: 'inherit',
    fontSize: '13px',
    textAlign: 'left',
    backgroundColor: { default: 'transparent', ':hover': chrome.surface2 },
    borderWidth: 0,
    cursor: 'pointer',
    color: chrome.text,
  },
  when: { color: chrome.text3, width: '76px', flexGrow: 0, flexShrink: 0 },
  kind: { fontWeight: 500, width: '108px', flexGrow: 0, flexShrink: 0 },
  who: { marginLeft: 'auto', color: chrome.text3, fontSize: '12px' },
  bad: { color: chrome.danger },
  warn: { color: chrome.warn },
  cool: { color: state.frozenInk },
});

/**
 * The five node states, and the ten event kinds, as styles rather than as class
 * names built from the value. `state ${m.state}` was a string the CSS had to
 * agree with; this is a lookup that fails visibly when the trace grows a state
 * nobody has styled yet.
 */
const STATE: Record<string, stylex.StyleXStyles> = {
  alive: sx.alive,
  degraded: sx.degraded,
  frozen: sx.frozen,
  dead: sx.dead,
  reclaiming: sx.dead,
};

const KIND: Record<string, stylex.StyleXStyles> = {
  kill: sx.bad,
  oom: sx.bad,
  disk_full: sx.bad,
  failed: sx.bad,
  retry: sx.warn,
  rpc_timeout: sx.warn,
  degrade: sx.warn,
  spot_notice: sx.warn,
  freeze: sx.cool,
  thaw: sx.cool,
};
