'use client';

/**
 * The money, while it is being spent.
 *
 * Five buckets rather than one number, because they are five different kinds of
 * decision and adding them up hides the trade. Replication triples **capacity**
 * and adds to **build** in order to empty **incidents**; one figure cannot say
 * that, and a design argument that turns on it cannot be had against a total.
 *
 * Read it while the film plays. Capacity is flat from the first frame — you have
 * already bought a minute of every machine before a single call is made — build
 * creeps, consumption follows the work, and incidents are steps at the instants
 * things broke. Revenue lands at the end, or does not.
 *
 * **And it answers the film.** Point at a machine and this says what that
 * machine costs: its own slice inside every bar, its lines lifted to the top,
 * and everything it is not answerable for faded back. That connection is the
 * point of having both on one screen — "s0 is the expensive one" is a sentence
 * about a picture and a bill at the same time, and a viewer should not have to
 * hold the two in their head to make it.
 */
import { BUCKETS, money, type Bucket, type Ledger as L } from '../lib/ledger.ts';
import * as D from '../lib/design.ts';

const COLOUR: Record<Bucket, string> = {
  build: '#8E6BA8',
  capacity: '#3C6E9F',
  consumption: '#3E8E8A',
  incidents: D.ALARM,
};

const WHY: Record<Bucket, string> = {
  build: 'Engineering time to construct this design, carried whether or not the thing it protects against happens.',
  capacity: 'The fleet you reserved, priced for the whole period. An idle machine costs exactly as much as a busy one.',
  consumption: 'What the work actually burned: storage and egress. This is the line a better algorithm moves.',
  incidents: 'What failure cost: reruns, lost work, being late. Zero until something breaks, then large.',
};

export function LedgerStrip({
  l,
  open,
  onToggle,
  onHover,
}: {
  l: L;
  open: boolean;
  onToggle: () => void;
  /** Pointing at a line points at its machine, so the link runs both ways. */
  onHover?: (machine: string | null) => void;
}) {
  const scale = Math.max(l.finalCost, 0.0001);
  const focus = l.focus;

  return (
    <div className={`ledger${open ? ' open' : ''}${focus ? ' focused' : ''}`}>
      <button className="ledger-head" onClick={onToggle} aria-expanded={open}>
        <span className="ledger-pl">
          <span className="lbl">cost</span>
          <strong>{money(l.cost, l.currency)}</strong>
        </span>
        {/* What it will come to, beside what it has come to. A cost with nothing
            to be large against is a number nobody can read. */}
        <span className="ledger-pl">
          <span className="lbl">of</span>
          <strong className="muted">{money(l.finalCost, l.currency)}</strong>
        </span>

        {/* When a machine is being pointed at, its own figure stands beside the
            fleet's rather than replacing it: what matters is the proportion, and
            a share shown alone is a number with nothing to be large against. */}
        {focus && (
          <span className="ledger-pl mine">
            <span className="lbl">{focus.name}</span>
            <strong>{money(focus.cost, l.currency)}</strong>
            <span className="pct">{Math.round((focus.cost / Math.max(l.cost, 1e-9)) * 100)}%</span>
          </span>
        )}

        {/* One stacked bar: what has been spent, against what the whole run comes
            to. The pale remainder is what is still coming, and the bright notch
            inside each segment is the pointed-at machine's part of it. */}
        <span className="ledger-bar" title="cost so far, against the whole run">
          {BUCKETS.map((b) => (
            <span
              key={b}
              className="seg"
              style={{ width: `${(l.buckets[b] / scale) * 100}%`, background: COLOUR[b] }}
              title={`${b} ${money(l.buckets[b], l.currency)}`}
            >
              {focus && focus.buckets[b] > 0 && (
                <i style={{ width: `${(focus.buckets[b] / Math.max(l.buckets[b], 1e-9)) * 100}%` }} />
              )}
            </span>
          ))}
          <span className="rest" style={{ width: `${Math.max(0, ((l.finalCost - l.cost) / scale) * 100)}%` }} />
        </span>

        <span className="ledger-caret">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div className="ledger-body">
          <div className="ledger-buckets">
            {BUCKETS.map((b) => {
              const mine = focus?.buckets[b] ?? 0;
              return (
                <div key={b} className={`ledger-bk${focus ? (mine > 0 ? ' hot' : ' cold') : ''}`} title={WHY[b]}>
                  <span className="ledger-dot" style={{ background: COLOUR[b] }} />
                  <span className="name">{b}</span>
                  <span className="amt mono">{money(l.buckets[b], l.currency)}</span>
                  {focus && mine > 0 && (
                    <span className="of mono">
                      {focus.name} {money(mine, l.currency)}
                    </span>
                  )}
                  <p>{WHY[b]}</p>
                </div>
              );
            })}
          </div>

          <table>
            <thead>
              <tr>
                <th>line</th>
                <th style={{ textAlign: 'right' }}>quantity</th>
                <th style={{ textAlign: 'right' }}>so far</th>
                <th style={{ textAlign: 'right' }}>{focus ? focus.name : 'whole run'}</th>
              </tr>
            </thead>
            <tbody>
              {l.lines.slice(0, 16).map(({ line, sofar, mine, why }, i) => {
                const machine = line.bucket === 'capacity' ? line.what.split(' (')[0] : null;
                return (
                  <tr
                    key={i}
                    className={focus ? (mine > 0 ? 'hot' : 'cold') : ''}
                    onMouseEnter={() => machine && onHover?.(machine)}
                    onMouseLeave={() => machine && onHover?.(null)}
                  >
                    <td>
                      <span className="ledger-dot" style={{ background: COLOUR[line.bucket] }} />
                      {line.what}
                      {why && <em className="why"> — {why}</em>}
                    </td>
                    <td className="n" style={{ textAlign: 'right' }}>
                      {line.quantity.toPrecision(3)} <span className="muted">{line.unit}</span>
                    </td>
                    <td className="n" style={{ textAlign: 'right' }}>
                      {money(sofar, l.currency)}
                    </td>
                    <td className="n muted" style={{ textAlign: 'right' }}>
                      {focus
                        ? mine > 0
                          ? money(mine, l.currency)
                          : '—'
                        : money(line.amount, l.currency)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="ledger-fine">
            Every amount here is a line <code>losim bill</code> already computed; what is added
            is only when it arrives, and who it belongs to. The closing total is the
            bill&rsquo;s, exactly.
            {focus && (
              <>
                {' '}
                A dash means the line is nobody&rsquo;s in particular — the late-finish
                penalty belongs to the job, not to a machine.
              </>
            )}
          </p>
        </div>
      )}

      <style>{`
        .ledger { flex: none; }
        .ledger-head {
          display: flex; align-items: center; gap: 14px; width: 100%;
          padding: 7px 12px; font: inherit; color: var(--text); cursor: pointer;
          background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--r-lg); box-shadow: var(--shadow-1);
          transition: border-color .12s ease;
        }
        .ledger.open .ledger-head { border-radius: var(--r-lg) var(--r-lg) 0 0; border-bottom-color: transparent; }
        .ledger-head:hover { background: var(--surface-2); }
        .ledger.focused .ledger-head { border-color: var(--accent, ${D.DATA_EDGE}); }

        .ledger-pl { display: flex; align-items: baseline; gap: 6px; white-space: nowrap; }
        .ledger-pl .lbl {
          font-size: 10.5px; font-weight: 600; letter-spacing: .05em;
          text-transform: uppercase; color: var(--text-3);
        }
        .ledger-pl strong { font-size: 15px; font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }
        .ledger-pl strong.muted { font-size: 13px; color: var(--text-3); font-weight: 500; }

        .ledger-pl.mine {
          padding: 2px 9px 3px; border-radius: 999px;
          background: var(--surface-2); border: 1px solid var(--border);
        }
        .ledger-pl.mine .lbl { color: var(--text-2); text-transform: none; letter-spacing: 0; font-size: 12px; }
        .ledger-pl.mine strong { font-size: 13.5px; }
        .ledger-pl.mine .pct { font-size: 11.5px; color: var(--text-3); }

        .ledger-bar {
          flex: 1; display: flex; height: 8px; min-width: 80px;
          border-radius: 999px; overflow: hidden; background: var(--surface-2);
        }
        .ledger-bar > span { height: 100%; }
        .ledger-bar .seg { position: relative; }
        /* The machine's part of this bucket, drawn inside the bucket's own colour
           rather than beside it — a share has to be a share of something. */
        .ledger-bar .seg i {
          position: absolute; inset: 0 auto 0 0; display: block;
          background: rgba(255,255,255,.62);
          box-shadow: 1px 0 0 rgba(0,0,0,.25);
        }
        .ledger-bar .rest { background: var(--border); }
        .ledger-caret { color: var(--text-3); font-size: 11px; }

        .ledger-body {
          padding: 12px 14px 14px; background: var(--surface);
          border: 1px solid var(--border); border-top: 0;
          border-radius: 0 0 var(--r-lg) var(--r-lg); box-shadow: var(--shadow-1);
          max-height: 38vh; overflow-y: auto;
        }
        .ledger-buckets {
          display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
          gap: 10px; margin-bottom: 14px;
        }
        .ledger-bk {
          padding: 9px 10px; border: 1px solid var(--border);
          border-radius: var(--r); background: var(--surface-2);
          display: grid; grid-template-columns: auto 1fr auto; gap: 6px; align-items: center;
          transition: opacity .12s ease, border-color .12s ease;
        }
        .ledger-bk.cold { opacity: .38; }
        .ledger-bk.hot { border-color: var(--text-3); }
        .ledger-bk .name { font-weight: 600; font-size: 12.5px; }
        .ledger-bk .amt { font-size: 12.5px; }
        .ledger-bk .of {
          grid-column: 1 / -1; font-size: 11.5px; color: var(--text-2);
          padding-top: 1px;
        }
        .ledger-bk p {
          grid-column: 1 / -1; margin: 2px 0 0; font-size: 11px; line-height: 1.4;
          color: var(--text-3);
        }
        .ledger-dot {
          width: 8px; height: 8px; border-radius: 50%; display: inline-block;
          margin-right: 7px; vertical-align: 1px;
        }
        .ledger-body tbody tr.cold { opacity: .32; }
        .ledger-body tbody tr.hot td { background: var(--surface-2); }
        .ledger-body tbody tr:hover td { background: var(--surface-2); }
        .why { color: var(--text-3); font-style: normal; font-size: 11.5px; }
        .ledger-fine { font-size: 11px; color: var(--text-3); margin: 10px 0 0; }
      `}</style>
    </div>
  );
}
