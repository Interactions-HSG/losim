'use client';

/**
 * What the run had cost, by the time it had got here.
 *
 * The bill on the command line is a total. A total cannot say *when* the money
 * was decided, and when is the whole lesson: build and capacity are settled by
 * drawing the machines, before a single byte moves, while consumption arrives
 * with the work and incidents land at the instant something breaks. Drag the
 * clock and watch which of the four actually moves.
 *
 * Every number here comes from `losim bill`, accrued over the run by
 * `lib/ledger.ts`. Nothing is priced in this app. A viewer with prices of its
 * own would be a second accountant, and two accountants disagree.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';

import { colourOf, Donut, Legend, short, StackedBars, type Bar } from './Chart.tsx';
import { Head, Panel, Tile } from './Shell.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole, useNow } from '../../lib/console.tsx';
import { BUCKETS, LedgerModel, money, type Bucket } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import { openUrl, type Run } from '../../lib/runs.ts';

const DIMS = {
  bucket: 'Bucket',
  machine: 'Machine',
  zone: 'Zone',
  instance: 'Instance type',
} as const;
type Dim = keyof typeof DIMS;

/**
 * Money that belongs to no machine.
 *
 * Build is the biggest line in most of these runs and it is not any machine's:
 * it is what the design cost to write. Rolling it silently into the machines
 * would make every per-machine number wrong in the same direction, so it is
 * shown as what it is.
 */
const NOBODY = 'the design itself';

/** One run, cut whichever way is being asked for, as it stood at `t`. */
function cut(run: Run, model: LedgerModel, dim: Dim, t: number): Record<string, number> {
  const l = model.at(t);
  if (dim === 'bucket') return { ...l.buckets };
  const out: Record<string, number> = {};
  let claimed = 0;
  for (const m of run.trace.machines) {
    const mine = model.at(t, m.name).focus?.cost ?? 0;
    if (mine <= 0) continue;
    claimed += mine;
    const key = dim === 'machine' ? m.name : dim === 'zone' ? m.zone : m.instance;
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
          <p className="muted">
            There is no bill beside <code>{run.name}</code>, so there is nothing to report. Bills
            are written by <code>losim bill --json</code> next to the trace, and{' '}
            <code>./viewer/traces.sh</code> writes one for every run it sweeps.
          </p>
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
            <a href="#" onClick={(e) => { e.preventDefault(); go('runs'); }}>Runs</a>
            {' / '}
            <a href="#" onClick={(e) => { e.preventDefault(); go('overview'); }}>{run.name}</a>
            {' / Cost'}
          </>
        }
        title="Cost"
        sub={
          <>
            {run.name} as it stood {refTime(now)} in, from <code>losim bill</code>. Tick another
            run below and it is drawn at the same instant of its own clock.
          </>
        }
      />

      <div className="tiles">
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

      <div className="two">
        <div className="col">
          <Panel flush>
            <div className="tools">
              <span className="lb">Group by</span>
              <div className="seg" role="group" aria-label="group by">
                {(Object.keys(DIMS) as Dim[]).map((d) => (
                  <button key={d} aria-pressed={dim === d} onClick={() => setDim(d)}>
                    {DIMS[d]}
                  </button>
                ))}
              </div>
              {loading.length > 0 && <span className="note">opening {loading.join(', ')}…</span>}
            </div>
            <StackedBars
              bars={bars}
              keys={keys}
              colour={colour}
              yMax={barMax}
              height={270}
              currency={l.currency}
            />
            <div className="pad">
              <Legend keys={keys} colour={colour} />
              <p className="note">
                Grouped by <strong>{DIMS[dim].toLowerCase()}</strong>, cut off at the clock.
                {dim === 'bucket'
                  ? ' The four are printed apart rather than summed because they are four different kinds of decision, and one number cannot say that.'
                  : ` Build belongs to no machine — it is what the design cost to write — so it is shown as “${NOBODY}” rather than shared out and making every other figure wrong in the same direction.`}
              </p>
            </div>
          </Panel>

          <Panel
            title="Every line, so far"
            note={`${l.lines.length} of ${ledger.at(Number.MAX_SAFE_INTEGER).lines.length} have begun`}
            flush
          >
            <div className="scroll">
              <table>
                <thead>
                  <tr>
                    <th>Bucket</th>
                    <th>What</th>
                    <th className="r">Quantity</th>
                    <th className="r">Unit price</th>
                    <th className="r">So far</th>
                    <th className="r">Of</th>
                  </tr>
                </thead>
                <tbody>
                  {l.lines.slice(0, 40).map((row, i) => (
                    <tr key={i}>
                      <td>
                        <i className="dot" style={{ background: COLOUR[row.line.bucket] }} />
                        {row.line.bucket}
                      </td>
                      <td>{row.line.what}</td>
                      <td className="n">
                        {short(row.line.quantity)} <span className="muted">{row.line.unit}</span>
                      </td>
                      <td className="n">{short(row.line.unitPrice)}</td>
                      <td className="n b">{money(row.sofar, l.currency)}</td>
                      <td className="n muted">{money(row.line.amount, l.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {l.lines.length > 40 && (
              <p className="pad note">{l.lines.length - 40} more, the smallest of them.</p>
            )}
          </Panel>
        </div>

        <div className="col">
          <Panel title="At the clock" note={DIMS[dim].toLowerCase()}>
            <div className="ring">
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
              className="find"
              placeholder="Filter"
              value={find}
              onChange={(e) => setFind(e.target.value)}
              aria-label="filter runs to compare with"
            />
            <div className="beside">
              {runs
                .filter(
                  (r) =>
                    r.name !== run.name
                    && r.cost !== undefined
                    && (!find || r.name.toLowerCase().includes(find.trim().toLowerCase())),
                )
                .map((r) => (
                  <label key={r.name}>
                    <input
                      type="checkbox"
                      checked={beside.includes(r.name)}
                      onChange={(e) =>
                        setBeside((b) =>
                          e.target.checked ? [...b, r.name] : b.filter((x) => x !== r.name),
                        )
                      }
                    />
                    <span className="nm">{r.name}</span>
                    <span className="c">{money(r.cost ?? 0, r.currency ?? l.currency)}</span>
                  </label>
                ))}
            </div>
            <p className="note">
              Ticking one opens its trace, so it can be accrued rather than only totalled. The
              totals beside each name are what the whole run cost.
            </p>
          </Panel>

          <Panel title="No revenue, no profit">
            <p className="muted" style={{ margin: 0 }}>
              losim bills four buckets and stops there. What a run <em>earns</em> is not a property
              of the run — it depends on what the service is worth to somebody, which is a business
              question this course cannot answer. What a design <strong>costs</strong> is computed
              from what actually happened.
            </p>
          </Panel>

          <Panel title="Open another">
            <div className="jump">
              {runs.filter((r) => r.from === 'yours' && r.name !== run.name).slice(0, 6).map((r) => (
                <button key={r.name} className="btn" onClick={() => void open(r.name, 'cost')}>
                  {r.name}
                </button>
              ))}
            </div>
          </Panel>
        </div>
      </div>

      <style>{`
        .tiles { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); }
        .two { display: grid; gap: 20px; grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr); align-items: start; }
        .col { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
        @media (max-width: 1180px) { .two { grid-template-columns: 1fr; } }

        .tools { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 0 20px 12px; }
        .tools .lb { font-size: 12px; font-weight: 500; color: var(--text-3); }
        .pad { padding: 0 20px 16px; }
        .note { font-size: 12.5px; color: var(--text-3); }
        .scroll { overflow-x: auto; padding: 0 20px 8px; }
        th.r, td.n { text-align: right; font-variant-numeric: tabular-nums; }
        td.n { font-family: var(--mono); }
        td.n.b { color: var(--text); font-weight: 500; }
        .dot { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 7px; }

        .ring { display: flex; flex-direction: column; align-items: center; gap: 8px; }

        .find {
          width: 100%; height: 32px; padding: 0 12px; margin-bottom: 6px;
          font: inherit; font-size: 13px; color: var(--text);
          background: var(--surface); border: 1px solid var(--border); border-radius: 999px;
        }
        .beside { display: flex; flex-direction: column; max-height: 260px; overflow-y: auto; }
        .beside label {
          display: flex; align-items: center; gap: 10px; padding: 7px 2px; font-size: 12.5px;
          cursor: pointer;
        }
        .beside label + label { border-top: 1px solid var(--border); }
        .beside .nm { font-family: var(--mono); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .beside .c { margin-left: auto; font-family: var(--mono); color: var(--text-3); white-space: nowrap; }
        .jump { display: flex; flex-wrap: wrap; gap: 8px; }
      `}</style>
    </>
  );
}
