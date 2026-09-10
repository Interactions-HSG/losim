'use client';

/**
 * What the run had cost, by the time it had got here.
 *
 * The bill on the command line is a total. A total cannot say *when* the money
 * was decided, and when is the whole lesson: build and capacity are settled by
 * drawing the nodes, before a single byte moves, while consumption arrives
 * with the work and incidents land at the instant something breaks. Drag the
 * clock and watch which of the four actually moves.
 *
 * Every number here comes from `losim bill`, accrued over the run by
 * `lib/ledger.ts`. Nothing is priced in this app. A viewer with prices of its
 * own would be a second accountant, and two accountants disagree.
 */
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import * as stylex from '@stylexjs/stylex';

import { colourOf, Donut, Legend, short, StackedBars, type Bar } from './Chart.tsx';
import { Head, Panel, Tile } from './Shell.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { BUCKETS, LedgerModel, money, type Bucket } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import { openUrl, type Run } from '../../lib/runs.ts';
import { A, Code, P, Table, Td, Th } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome, font } from '../../lib/tokens.stylex.ts';

const DIMS = {
  bucket: 'Bucket',
  node: 'Node',
  zone: 'Zone',
  instance: 'Instance type',
} as const;
type Dim = keyof typeof DIMS;

/**
 * Money that belongs to no node.
 *
 * Build is the biggest line in most of these runs and it is not any node's:
 * it is what the design cost to write. Rolling it silently into the nodes
 * would make every per-node number wrong in the same direction, so it is
 * shown as what it is.
 */
const NOBODY = 'the design itself';

/**
 * An amount with the currency left off.
 *
 * `money()` is right where a number stands alone. In a grid of forty of them the
 * repeated `CHF` is forty times the same word, and it is what makes the columns
 * wrap — so the currency is said once in the heading and the cells are numbers.
 */
function amt(v: number): string {
  return v < 10 ? v.toFixed(4) : v.toFixed(2);
}

/**
 * Whether the reason a line is somebody's just says the line's label again.
 *
 * "calls that did not answer in time — *calls it did not answer in time*" is
 * one fact printed twice, and a page that does that teaches somebody to stop
 * reading it. Where the two really do differ the reason is the interesting
 * half, so this only drops it when it adds nothing.
 */
function echoes(what: string, why: string): boolean {
  if (!why) return true;
  const words = (t: string) => new Set(t.toLowerCase().match(/[a-z]{4,}/g) ?? []);
  const a = words(why);
  if (!a.size) return true;
  const b = words(what);
  let shared = 0;
  for (const w of a) if (b.has(w)) shared++;
  return shared / a.size >= 0.6;
}

/** One run, cut whichever way is being asked for, as it stood at `t`. */
function cut(run: Run, model: LedgerModel, dim: Dim, t: number): Record<string, number> {
  const l = model.at(t);
  if (dim === 'bucket') return { ...l.buckets };
  const out: Record<string, number> = {};
  let claimed = 0;
  for (const m of run.trace.nodes) {
    const mine = model.at(t, m.name).focus?.cost ?? 0;
    if (mine <= 0) continue;
    claimed += mine;
    const key = dim === 'node' ? m.name : dim === 'zone' ? m.zone : m.instance;
    out[key] = (out[key] ?? 0) + mine;
  }
  const rest = l.cost - claimed;
  if (rest > 1e-9) out[NOBODY] = rest;
  return out;
}

export function Cost() {
  const { run, runs, ledger, go, open } = useConsole();
  const now = useNow();
  const [dim, setDim] = useState<Dim>('bucket');
  /** Whose own bill is open, when somebody has asked for one. */
  const [whose, setWhose] = useState<string | null>(null);
  /** Other runs to put beside this one. Their traces are fetched when ticked. */
  const [beside, setBeside] = useState<string[]>([]);
  const [find, setFind] = useState('');
  const [loaded, setLoaded] = useState<Map<string, { run: Run; model: LedgerModel }>>(new Map());
  const [loading, setLoading] = useState<string[]>([]);

  const want = useCallback(
    async (name: string) => {
      if (loaded.has(name)) return;
      const ref = runs.find((r) => r.name === name);
      if (!ref) return;
      setLoading((l) => [...l, name]);
      try {
        const other = await openUrl(ref.name, ref.href);
        if (other.bill) {
          const model = new LedgerModel(other.trace, other.bill);
          setLoaded((m) => new Map(m).set(name, { run: other, model }));
        }
      } catch {
        // A run that will not open is a run that stays unticked; the box springs back.
        setBeside((b) => b.filter((x) => x !== name));
      } finally {
        setLoading((l) => l.filter((x) => x !== name));
      }
    },
    [loaded, runs],
  );

  useEffect(() => {
    for (const name of beside) void want(name);
  }, [beside, want]);

  const l = useMemo(() => ledger?.at(now) ?? null, [ledger, now]);
  const parts = useMemo(
    () => (run && ledger ? cut(run, ledger, dim, now) : {}),
    [run, ledger, dim, now],
  );

  /**
   * Every node's own share of the bill, at the clock.
   *
   * The grouped bar answers "which node costs the most"; this answers the
   * question underneath it — *what for*. They are different questions and a
   * stacked bar cannot be read to the rappen, so the numbers are printed.
   *
   * Nothing is re-priced here: `LedgerModel` already knows whose each line is
   * and why, and this only asks it once per node.
   */
  const mine = useMemo(() => {
    if (!run || !ledger) return [];
    const rows = run.trace.nodes.map((m) => {
      const at = ledger.at(now, m.name);
      return {
        node: m,
        focus: at.focus!,
        /** Its own lines, largest first — `at()` has already sorted them that way. */
        lines: at.lines.filter((r) => r.mine > 0),
      };
    });
    return rows.sort((a, b) => b.focus.cost - a.focus.cost);
  }, [run, ledger, now]);

  /** What the cluster carries between them, so the remainder can say it is nobody's. */
  const claimed = useMemo(() => mine.reduce((a, r) => a + r.focus.cost, 0), [mine]);

  /**
   * The bars: this run, and whichever others are ticked, each as it stood the
   * same number of reference milliseconds into itself.
   *
   * Not "the same fraction through". Normalising two runs to forty percent makes
   * every comparison a draw, and the point of a comparison is that at 2,400 one
   * of them has finished and the other has not.
   */
  const bars: Bar[] = useMemo(() => {
    if (!run || !ledger) return [];
    const rows: Bar[] = [
      { label: run.name, sub: refTime(Math.min(now, run.trace.duration)), parts, here: true },
    ];
    for (const name of beside) {
      const got = loaded.get(name);
      if (!got) continue;
      rows.push({
        label: name,
        sub: refTime(Math.min(now, got.run.trace.duration)),
        parts: cut(got.run, got.model, dim, now),
      });
    }
    return rows;
  }, [run, ledger, parts, beside, loaded, dim, now]);

  /** Fixed across the clock and across the ticks: what each of these runs finally cost. */
  const barMax = useMemo(() => {
    let top = ledger?.finalCost ?? 0;
    for (const name of beside) {
      const got = loaded.get(name);
      if (got) top = Math.max(top, got.model.finalCost);
    }
    return top;
  }, [ledger, beside, loaded]);

  const keys = useMemo(() => {
    const seen = new Set<string>();
    for (const b of bars) for (const k of Object.keys(b.parts)) seen.add(k);
    return dim === 'bucket' ? [...BUCKETS] : [...seen].sort();
  }, [bars, dim]);

  const colour = useCallback(
    (k: string) =>
      dim === 'bucket'
        ? (COLOUR[k as Bucket] ?? colourOf(0))
        : k === NOBODY
          ? 'var(--text-3)'
          : colourOf(keys.indexOf(k)),
    [dim, keys],
  );

  if (!run) return null;
  if (!l || !ledger) {
    return (
      <>
        <Head title="Cost" sub={run.name} />
        <Panel>
          <P style={ui.muted}>
            There is no bill beside <Code>{run.name}</Code>, so there is nothing to report. Bills
            are written by <Code>losim bill --json</Code> next to the trace, and{' '}
            <Code>losim dev viewer traces</Code> writes one for every run it sweeps.
          </P>
        </Panel>
      </>
    );
  }

  const fixed = l.buckets.build + l.buckets.capacity;
  const biggest = BUCKETS.reduce((a, b) => (l.buckets[b] > l.buckets[a] ? b : a), BUCKETS[0]);

  return (
    <>
      <Head
        crumbs={
          <>
            <A href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</A>
            {' / '}
            <A href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</A>
            {' / Cost'}
          </>
        }
        title="Cost"
        sub={
          <>
            {run.name} as it stood {refTime(now)} in, from <Code>losim bill</Code>. Tick another
            run below and it is drawn at the same instant of its own clock.
          </>
        }
      />

      <div {...stylex.props(sx.tiles)}>
        <Tile
          k="Billed so far"
          v={money(l.cost, l.currency)}
          n={`of ${money(l.finalCost, l.currency)} for the whole run`}
        />
        <Tile
          k="Decided before it ran"
          v={money(fixed, l.currency)}
          n={`${((fixed / Math.max(l.cost, 1e-9)) * 100).toFixed(0)}% of it — build and capacity`}
        />
        <Tile
          k="Largest bucket"
          v={biggest}
          n={money(l.buckets[biggest], l.currency)}
        />
        <Tile
          k="Incidents"
          v={money(l.buckets.incidents, l.currency)}
          n={l.buckets.incidents > 0 ? 'something broke, and this is what it cost' : 'nothing has broken yet'}
        />
      </div>

      <div {...stylex.props(sx.two)}>
        <div {...stylex.props(sx.col)}>
          <Panel flush>
            <div {...stylex.props(sx.tools)}>
              <span {...stylex.props(sx.lb)}>Group by</span>
              <div {...stylex.props(ui.seg)} role="group" aria-label="group by">
                {(Object.keys(DIMS) as Dim[]).map((d) => (
                  <button
                    key={d}
                    {...stylex.props(ui.segButton, dim === d && ui.segOn)}
                    aria-pressed={dim === d}
                    onClick={() => setDim(d)}
                  >
                    {DIMS[d]}
                  </button>
                ))}
              </div>
              {loading.length > 0 && <span {...stylex.props(sx.note)}>opening {loading.join(', ')}…</span>}
            </div>
            <StackedBars
              bars={bars}
              keys={keys}
              colour={colour}
              yMax={barMax}
              height={270}
              currency={l.currency}
            />
            <div {...stylex.props(sx.pad)}>
              <Legend keys={keys} colour={colour} />
              <P style={sx.note}>
                Grouped by <strong>{DIMS[dim].toLowerCase()}</strong>, cut off at the clock.
                {dim === 'bucket'
                  ? ' The four are printed apart rather than summed because they are four different kinds of decision, and one number cannot say that.'
                  : ` Build belongs to no node — it is what the design cost to write — so it is shown as “${NOBODY}” rather than shared out and making every other figure wrong in the same direction.`}
              </P>
            </div>
          </Panel>

          <Panel
            title="Every line, so far"
            note={`${l.lines.length} of ${ledger.at(Number.MAX_SAFE_INTEGER).lines.length} have begun`}
            flush
          >
            <div {...stylex.props(sx.scroll)}>
              <Table>
                <thead>
                  <tr>
                    <Th>Bucket</Th>
                    <Th>What</Th>
                    <Th style={sx.right}>Quantity</Th>
                    <Th style={sx.right}>Unit price</Th>
                    <Th style={sx.right}>So far</Th>
                    <Th style={sx.right}>Of</Th>
                  </tr>
                </thead>
                <tbody>
                  {l.lines.slice(0, 40).map((row, i) => (
                    <tr key={i}>
                      <Td>
                        <i {...stylex.props(sx.dot)} style={{ background: COLOUR[row.line.bucket] }} />
                        {row.line.bucket}
                      </Td>
                      <Td>{row.line.what}</Td>
                      <Td num style={sx.right}>
                        {short(row.line.quantity)} <span {...stylex.props(ui.muted)}>{row.line.unit}</span>
                      </Td>
                      <Td num style={sx.right}>{short(row.line.unitPrice)}</Td>
                      <Td num style={[sx.right, sx.strong]}>{money(row.sofar, l.currency)}</Td>
                      <Td num style={[sx.right, ui.muted]}>{money(row.line.amount, l.currency)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </div>
            {l.lines.length > 40 && (
              <P style={[sx.pad, sx.note]}>{l.lines.length - 40} more, the smallest of them.</P>
            )}
          </Panel>
        </div>

        <div {...stylex.props(sx.col)}>
          <Panel title="At the clock" note={DIMS[dim].toLowerCase()}>
            <div {...stylex.props(sx.ring)}>
              <Donut
                parts={parts}
                colour={colour}
                middle={money(l.cost, l.currency)}
                sub={`of ${money(l.finalCost, l.currency)}`}
              />
              <Legend keys={Object.keys(parts)} colour={colour} />
            </div>
          </Panel>

          <Panel title="Beside" note="another run, on the same clock">
            <input
              {...stylex.props(sx.find)}
              placeholder="Filter"
              value={find}
              onChange={(e) => setFind(e.target.value)}
              aria-label="filter runs to compare with"
            />
            <div {...stylex.props(sx.beside)}>
              {runs
                .filter(
                  (r) =>
                    r.name !== run.name
                    && r.cost !== undefined
                    && (!find || r.name.toLowerCase().includes(find.trim().toLowerCase())),
                )
                .map((r, i) => (
                  <label key={r.name} {...stylex.props(sx.pick, i > 0 && sx.ruled)}>
                    <input
                      type="checkbox"
                      checked={beside.includes(r.name)}
                      onChange={(e) =>
                        setBeside((b) =>
                          e.target.checked ? [...b, r.name] : b.filter((x) => x !== r.name),
                        )
                      }
                    />
                    <span {...stylex.props(sx.nm)}>{r.name}</span>
                    <span {...stylex.props(sx.total)}>{money(r.cost ?? 0, r.currency ?? l.currency)}</span>
                  </label>
                ))}
            </div>
            <P style={sx.note}>
              Ticking one opens its trace, so it can be accrued rather than only totalled. The
              totals beside each name are what the whole run cost.
            </P>
          </Panel>

          <Panel title="Open another">
            <div {...stylex.props(sx.jump)}>
              {runs.filter((r) => r.from === 'yours' && r.name !== run.name).slice(0, 6).map((r) => (
                <button key={r.name} {...stylex.props(ui.btn)} onClick={() => void open(r.name, 'cost')}>
                  {r.name}
                </button>
              ))}
            </div>
          </Panel>
        </div>
      </div>

        <Panel
          title="What each node costs"
          note={`${mine.filter((r) => r.focus.cost > 0).length} of ${run.trace.nodes.length} carry any of it · ${l.currency}`}
          flush
        >
          <div {...stylex.props(sx.scroll)}>
            <Table>
              <thead>
                <tr>
                  <Th>Node</Th>
                  <Th>Where</Th>
                  {BUCKETS.map((b) => (
                    <Th key={b} style={sx.right}>
                      <i {...stylex.props(sx.dot, sx.tight)} style={{ background: COLOUR[b] }} />
                      {b}
                    </Th>
                  ))}
                  <Th style={sx.right}>So far</Th>
                  <Th style={sx.right}>Share</Th>
                </tr>
              </thead>
              <tbody>
                {mine.map((r) => {
                  const open = whose === r.node.name;
                  return (
                    <Fragment key={r.node.name}>
                      <tr
                        {...stylex.props(sx.row, open && sx.open)}
                        onClick={() => setWhose(open ? null : r.node.name)}
                        aria-expanded={open}
                      >
                        <Td style={sx.id}>
                          <span {...stylex.props(sx.tw)} aria-hidden>{open ? '\u25be' : '\u25b8'}</span>
                          {r.node.name}
                        </Td>
                        <Td style={[ui.muted, sx.where]}>
                          {r.node.instance} · {r.node.zone}
                        </Td>
                        {BUCKETS.map((b) => (
                          <Td key={b} num style={sx.right}>
                            {r.focus.buckets[b] > 1e-9 ? amt(r.focus.buckets[b]) : '\u2014'}
                          </Td>
                        ))}
                        <Td num style={[sx.right, sx.strong]}>{amt(r.focus.cost)}</Td>
                        <Td num style={[sx.right, ui.muted]}>
                          {((r.focus.cost / Math.max(l.cost, 1e-9)) * 100).toFixed(0)}%
                        </Td>
                      </tr>
                      {open && (
                        <tr>
                          <Td colSpan={3 + BUCKETS.length + 2} style={sx.whyCell}>
                            {r.lines.length ? (
                              <ul {...stylex.props(sx.why)}>
                                {r.lines.map((row, i) => (
                                  <li key={i} {...stylex.props(sx.reason)}>
                                    <i {...stylex.props(sx.dot)} style={{ background: COLOUR[row.line.bucket] }} />
                                    <span {...stylex.props(sx.what)}>{row.line.what}</span>
                                    {!echoes(row.line.what, row.why) && (
                                      <span {...stylex.props(sx.cause)}>{row.why}</span>
                                    )}
                                    <span {...stylex.props(sx.amt, ui.mono)}>{amt(row.mine)}</span>
                                    <span {...stylex.props(sx.of, ui.mono)}>of {amt(row.sofar)}</span>
                                  </li>
                                ))}
                              </ul>
                            ) : (
                              <P style={ui.muted}>
                                Nothing is charged to {r.node.name} by {refTime(now)}.
                              </P>
                            )}
                          </Td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
                {l.cost - claimed > 1e-9 && (
                  <tr>
                    <Td style={[sx.rest, sx.nobody]}>{NOBODY}</Td>
                    <Td colSpan={1 + BUCKETS.length} style={[ui.muted, sx.rest]}>
                      what the design cost to write, and any penalty the job as a whole earned —
                      splitting these over the nodes would invent a claim nothing supports
                    </Td>
                    <Td num style={[sx.right, sx.strong, sx.rest]}>{amt(l.cost - claimed)}</Td>
                    <Td num style={[sx.right, ui.muted, sx.rest]}>
                      {(((l.cost - claimed) / Math.max(l.cost, 1e-9)) * 100).toFixed(0)}%
                    </Td>
                  </tr>
                )}
              </tbody>
            </Table>
          </div>
          <P style={[sx.pad, sx.note]}>
            Click a node for its own lines, and why each one is charged to it. Every amount is
            a line <Code>losim bill</Code> already computed — only the claim about who is
            answerable for it is added here.
          </P>
        </Panel>

    </>
  );
}

const sx = stylex.create({
  tiles: { display: 'grid', gap: '16px', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))' },
  two: {
    display: 'grid',
    gap: '20px',
    gridTemplateColumns: {
      default: 'minmax(0, 1.55fr) minmax(0, 1fr)',
      '@media (max-width: 1180px)': '1fr',
    },
    alignItems: 'start',
  },
  col: { display: 'flex', flexDirection: 'column', gap: '20px', minWidth: 0 },

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
  pad: { marginBlock: 0, paddingTop: 0, paddingInline: '20px', paddingBottom: '16px' },
  note: { fontSize: '12.5px', color: chrome.text3 },
  scroll: { overflowX: 'auto', paddingTop: 0, paddingInline: '20px', paddingBottom: '8px' },
  right: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  /** The figure that is this row's point, against the ones that give it context. */
  strong: { color: chrome.text, fontWeight: 500 },
  id: { fontFamily: font.mono, fontWeight: 500 },
  dot: { display: 'inline-block', width: '8px', height: '8px', borderRadius: '2px', marginRight: '7px' },
  /** The same dot in a heading, where the column is narrower than the words. */
  tight: { marginRight: '5px' },
  ring: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px' },

  /* Every row is a question — "and what is that for?" — so every row opens. */
  row: { cursor: 'pointer', backgroundColor: { default: null, ':hover': chrome.surface2 } },
  open: { backgroundColor: chrome.surface2 },
  tw: { display: 'inline-block', width: '14px', color: chrome.text3 },
  /** The line nobody is answerable for, set apart from the nodes above it. */
  rest: { color: chrome.text3, fontSize: '12.5px' },
  nobody: { fontStyle: 'italic' },
  where: { whiteSpace: 'nowrap' },

  whyCell: { paddingTop: 0, paddingRight: 0, paddingBottom: '12px', paddingLeft: '34px' },
  why: { listStyle: 'none', margin: 0, padding: 0 },
  reason: { display: 'flex', alignItems: 'baseline', gap: '10px', paddingBlock: '5px', fontSize: '12.5px' },
  what: { color: chrome.text2 },
  cause: { color: chrome.text3, fontStyle: 'italic' },
  amt: { marginLeft: 'auto', fontWeight: 500 },
  of: { color: chrome.text3, width: '84px', textAlign: 'right', whiteSpace: 'nowrap' },

  find: {
    width: '100%',
    height: '32px',
    paddingBlock: 0,
    paddingInline: '12px',
    marginBottom: '6px',
    font: 'inherit',
    fontSize: '13px',
    color: chrome.text,
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: '999px',
  },
  beside: { display: 'flex', flexDirection: 'column', maxHeight: '260px', overflowY: 'auto' },
  pick: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    paddingBlock: '7px',
    paddingInline: '2px',
    fontSize: '12.5px',
    cursor: 'pointer',
  },
  /** What `label + label` used to draw: a rule between, not above the first. */
  ruled: { borderTopWidth: '1px', borderTopStyle: 'solid', borderTopColor: chrome.border },
  nm: { fontFamily: font.mono, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  total: { marginLeft: 'auto', fontFamily: font.mono, color: chrome.text3, whiteSpace: 'nowrap' },
  jump: { display: 'flex', flexWrap: 'wrap', gap: '8px' },
});
