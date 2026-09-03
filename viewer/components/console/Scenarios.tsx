'use client';

/**
 * Authoring a scenario: the machines, and what runs on them.
 *
 * The Java says what *can* run. This says **where it runs and what goes wrong**,
 * which is the half of a distributed system this course is actually about and
 * the half that has only ever been reachable by copying somebody else's YAML and
 * editing it until it stopped complaining.
 *
 * Three things are read off the lab rather than written down here, and each of
 * them used to be a thing you had to already know: the **classes** (from the
 * compiled bytecode, so a service that does not exist cannot be placed), the
 * **instance types** and the **regions** (losim's own catalogues, so a zone
 * cannot be misspelled into being its own region).
 *
 * The form is all on one page with the file beside it. Not a wizard: a wizard
 * hides the shape of what is being built, and the shape — four machines, three
 * of them in one zone and one across an ocean — is the thing worth seeing.
 * And the YAML is shown in full the whole time, because the file *is* the
 * scenario, and what a student has to be able to read by the end of this course
 * is the one their classmate sent them.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { useConsole } from '../../lib/console.tsx';
import { Lab } from '../Lab.tsx';
import {
  distances, expand, firstDraft, linkOf, perHour, toYaml, unplaced,
  type Chaos, type Draft, type Pool,
} from '../../lib/author.ts';
import {
  palette as fetchPalette, project, run as startRun, saveScenario, type Palette,
} from '../../lib/lab.ts';

export function Scenarios() {
  const { reload, nudge, setHasLab, openAt, watching } = useConsole();
  /**
   * Listing what exists, or writing a new one.
   *
   * The list is the page, because a lab accumulates scenarios and the thing you
   * do most often is run one of them again. Writing is the occasional act, so it
   * is behind a button rather than in front of the list.
   */
  const [mode, setMode] = useState<'list' | 'new'>('list');
  const [palette, setPalette] = useState<Palette | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(true);
  const [saying, setSaying] = useState<string | null>(null);
  const [refused, setRefused] = useState<string | null>(null);

  // What the lab's code offers. Compiling it is the lab's business; this waits.
  useEffect(() => {
    let live = true;
    setBusy(true);
    setPalette(null);
    fetchPalette()
      .then((p) => {
        if (!live) return;
        setPalette(p);
        if (p && p.compiled) setDraft(firstDraft(p));
      })
      .finally(() => live && setBusy(false));
    return () => { live = false; };
  }, []);

  const edit = useCallback((f: (d: Draft) => void) => {
    setDraft((was) => {
      if (!was) return was;
      const next: Draft = structuredClone(was);
      f(next);
      return next;
    });
    setRefused(null);
  }, []);

  const machines = useMemo(() => (draft ? expand(draft) : []), [draft]);
  const yaml = useMemo(() => (draft ? toYaml(draft) : ''), [draft]);
  const links = useMemo(
    () => (draft && palette ? distances(draft, palette.regions) : null),
    [draft, palette],
  );

  /**
   * Whether there is anything to place.
   *
   * Compiling is a different question. A lab of plain Java compiles perfectly and
   * offers no job and no service, and a draft over that is a fleet of workers
   * that run nothing wrapped around an empty job.
   */
  const canAuthor = !!palette && palette.compiled
    && (palette.jobs.length > 0 || palette.services.length > 0);

  /** Write it, then run it. A scenario nobody ran is a file, not a result. */
  const create = useCallback(async () => {
    if (!draft || !palette) return;
    setSaying('writing…');
    setRefused(null);
    const wrote = await saveScenario(draft.name, yaml);
    if (wrote.error) {
      setRefused(wrote.error);
      setSaying(null);
      return;
    }
    setSaying('running…');
    const started = await startRun(wrote.scenario!);
    if (started.error) {
      setRefused(started.error);
      setSaying(null);
      return;
    }
    // This page follows whatever is running, so the output appears where the
    // button was — watching a build happen is the point. It has to be told to
    // look, though: it noticed the last run when it mounted.
    nudge();
    setSaying(null);
    // Back to the list, which is where the run it just started is now showing
    // its output. Staying on a form whose Create button has already fired would
    // invite pressing it twice.
    setMode('list');
    // The run about to be written will be in the index; ask for it again rather
    // than patching a list that is a directory listing anyway.
    void reload();
  }, [draft, palette, yaml, reload, nudge]);

  return (
    <>
      <Head
        title="Scenarios"
        sub={
          mode === 'list' ? (
            <>
              Your Java says what <em>can</em> run. A scenario says where it runs and what goes
              wrong — the fleet, the distances, and the weather. Write as many as you like: what
              changes between two runs is almost never the code.
            </>
          ) : (
            <>
              What this lab compiles to is read off the classes, so nothing here can name a
              service that is not there. Nothing is written until you press create.
            </>
          )
        }
        actions={
          mode === 'list' ? (
            <button
              className="btn"
              disabled={!canAuthor}
              title={
                canAuthor
                  ? 'write a new scenario'
                  : 'there is nothing to place yet — this lab offers no job and no service'
              }
              onClick={() => {
                setRefused(null);
                if (palette) setDraft(firstDraft(palette));
                setMode('new');
              }}
            >
              + New scenario
            </button>
          ) : (
            <button className="btn" onClick={() => { setMode('list'); setRefused(null); }}>
              Cancel
            </button>
          )
        }
      />

      {busy && <Panel><p className="muted">reading the lab…</p></Panel>}

      {mode === 'list' && !busy && palette && !palette.compiled && (
        <Panel title="This lab does not compile">
          <p className="muted">
            Nothing can be placed until it does — the list of services is read off the classes,
            and there are none. This is javac, unedited:
          </p>
          <pre className="log">{palette.log || '(the lab said nothing)'}</pre>
        </Panel>
      )}

      {/* Compiling is not the same question as having something to place. A lab of
          plain Java compiles perfectly and offers no job and no service, and the
          skeleton draft would then propose a fleet of workers that run nothing
          around a `job: ""` — a scenario that cannot exist, offered as a default.
          The palette is the predicate: it is empty for exactly the labs where
          there is nothing to author. */}
      {mode === 'list' && !busy && palette && palette.compiled && !canAuthor && (
        <Panel title="Nothing to place yet">
          <p className="muted">
            This lab compiles — {palette.other} class{palette.other === 1 ? '' : 'es'} — but none
            of them is a job losim can start or a gRPC service a machine can serve. A scenario
            says <em>where the code runs</em>, so there has to be code that runs somewhere first.
          </p>
        </Panel>
      )}

      {mode === 'new' && !busy && palette && canAuthor && draft && (
        <div className="two">
          <div className="col">
            <Machines draft={draft} palette={palette} edit={edit} />
            <Placing draft={draft} palette={palette} edit={edit} />
            <Weather draft={draft} palette={palette} machines={machines} edit={edit} />
          </div>

          <div className="col sticky">
            <Panel title="Knowable now">
              <dl className="kv">
                <div>
                  <dt>machines</dt>
                  <dd>{machines.length}</dd>
                </div>
                <div>
                  <dt>zones</dt>
                  <dd>{new Set(machines.map((m) => m.zone)).size}</dd>
                </div>
                <div>
                  <dt>on the catalogue’s prices</dt>
                  <dd>{perHour(draft, palette).toFixed(4)} / hour</dd>
                </div>
              </dl>
              {links && (
                <>
                  <h3 className="sub">Distances in this fleet</h3>
                  <dl className="kv">
                    {(Object.keys(links) as (keyof typeof links)[])
                      .filter((k) => links[k] > 0)
                      .map((k) => (
                        <div key={k}>
                          <dt className={k === 'across an ocean' ? 'far' : ''}>{k}</dt>
                          <dd>{links[k]} pair{links[k] === 1 ? '' : 's'}</dd>
                        </div>
                      ))}
                  </dl>
                </>
              )}
              <p className="note">
                A rate, not a bill. What the run costs is what <code>losim bill</code> says
                afterwards, against a price list this page has never seen — a second number here
                that looked like a prediction would be a second accountant.
              </p>
            </Panel>

            {refused && (
              <Panel title="The lab refused it">
                <pre className="log bad">{refused}</pre>
                <p className="note">
                  That is the loader’s own sentence, with the line it was written on — the same
                  one a run would have given you.
                </p>
              </Panel>
            )}

            <Panel>
              <div className="field">
                <label htmlFor="scname">Save as</label>
                <input
                  id="scname"
                  value={draft.name}
                  onChange={(e) => edit((d) => { d.name = e.target.value; })}
                />
                <span className="hint">
                  Written to <code>scenarios/{draft.name}.yaml</code>
                  {palette.scenarios.includes(`${draft.name}.yaml`) && (
                    <> — <strong>which already exists and will be replaced</strong></>
                  )}
                </span>
              </div>
              <button
                className="btn primary wide"
                onClick={() => void create()}
                disabled={!!saying || !draft.job || !draft.name.trim()}
              >
                {saying ?? 'Create and run'}
              </button>
              {!draft.job && (
                <p className="note">
                  Nothing here implements <code>losim.api.Job</code>, so there is nothing to
                  start. A job is an ordinary class with a <code>run(Cluster)</code> in it.
                </p>
              )}
            </Panel>
          </div>
        </div>
      )}

      <style>{`
        .two { display: grid; gap: 20px; grid-template-columns: minmax(0, 1.5fr) minmax(0, 380px); align-items: start; }
        .col { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
        .col.sticky { position: sticky; top: 84px; }
        @media (max-width: 1180px) { .two { grid-template-columns: 1fr; } .col.sticky { position: static; } }

        .yaml {
          margin: 0; padding: 14px 20px 18px; overflow-x: auto;
          font-family: var(--mono); font-size: 12px; line-height: 1.6;
          color: var(--text-2); white-space: pre;
        }
        .log {
          margin: 0; padding: 12px 14px; border-radius: var(--r-sm);
          background: var(--surface-2); font-family: var(--mono); font-size: 12px;
          white-space: pre-wrap; overflow-x: auto; color: var(--text-2);
        }
        .log.bad { color: var(--danger); }
        .kv { margin: 0; display: flex; flex-direction: column; }
        .kv div { display: flex; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 13px; }
        .kv div + div { border-top: 1px solid var(--border); }
        .kv dt { color: var(--text-2); }
        .kv dt.far { color: var(--warn); font-weight: 500; }
        .kv dd { margin: 0; font-family: var(--mono); font-variant-numeric: tabular-nums; }
        h3.sub { margin: 16px 0 0; font-size: 12px; font-weight: 500; color: var(--text-3); text-transform: none; letter-spacing: 0; }
        .note { font-size: 11.5px; color: var(--text-3); margin: 12px 0 0; }
        .btn.wide { width: 100%; justify-content: center; margin-top: 12px; }
        .field { display: flex; flex-direction: column; gap: 5px; }
        .field label { font-size: 12.5px; color: var(--text-2); }
        .field .hint { font-size: 11.5px; color: var(--text-3); }
      `}</style>

      {/* Always mounted, whichever mode the page is in: this is what tells the
          console there is a lab behind the page at all, and it is what follows a
          run that is going. Hidden while writing, so the form has the screen. */}
      <div hidden={mode !== 'list'}>
      <Panel title="Every scenario here" note="press ▶ to build and run">
        <Lab
          watch={watching}
          onLab={setHasLab}
          onRan={async (name, href) => {
            // Opened by where it is rather than by name: the index is asked
            // again too, but a run that finished a second ago is on disk before
            // it is in the list, and it is the run you were waiting for.
            await openAt(name, href, 'overview');
            await reload();
          }}
        />
      </Panel>
      </div>

    </>
  );
}

/* ---------------------------------------------------------------- the machines
 *
 * The part a student actually authors. Each block is a pool — machines that grow
 * and shrink together — and a single machine is a pool of one, which is why the
 * count starts at 1 and there is no separate kind of thing for it.
 */
function Machines({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  const ms = expand(draft);
  return (
    <Panel
      title="Machines"
      note={`${ms.length} in ${new Set(ms.map((m) => m.zone)).size} zones`}
      actions={
        <button
          className="btn"
          onClick={() =>
            edit((d) => {
              d.pools.push({
                name: `pool${d.pools.length + 1}`,
                count: 1,
                instance: palette.instances[0]?.name ?? 'm5.large',
                zones: [palette.regions[0]?.zones[0] ?? 'eu-central-1a'],
                runs: [],
              });
            })
          }
        >
          + Pool
        </button>
      }
    >
      <p className="lead">
        Each block is a <strong>pool</strong>: machines that grow and shrink together, dealt
        round-robin over the zones you give it. A pool of one keeps its own name — which is what
        a fault has to be aimed at.
      </p>

      <div className="pools">
        {draft.pools.map((p, i) => (
          <PoolCard
            key={i}
            p={p}
            i={i}
            palette={palette}
            only={draft.pools.length === 1}
            first={i === 0}
            edit={edit}
          />
        ))}
      </div>

      <div className="flag">
        <span>ⓘ</span>
        <span>
          The job runs on the <strong>first machine in the file</strong> —{' '}
          <code>{ms[0]?.name ?? '—'}</code>. Move a pool to the top to move it.
        </span>
      </div>

      <style>{`
        .lead { font-size: 13px; margin: 0 0 14px; }
        .pools { display: flex; flex-direction: column; gap: 12px; }
        .flag {
          display: flex; gap: 10px; align-items: flex-start; margin-top: 14px;
          padding: 11px 14px; border-radius: var(--r-sm);
          background: var(--accent-soft); color: var(--text-2); font-size: 12.5px;
        }
      `}</style>
    </Panel>
  );
}

function PoolCard({
  p, i, palette, only, first, edit,
}: {
  p: Pool;
  i: number;
  palette: Palette;
  only: boolean;
  first: boolean;
  edit: (f: (d: Draft) => void) => void;
}) {
  const inst = palette.instances.find((x) => x.name === p.instance);
  const region = palette.regions.find((r) => p.zones.some((z) => r.zones.includes(z)))
    ?? palette.regions[0];
  return (
    <div className="pool">
      <header>
        <input
          className="nm"
          value={p.name}
          onChange={(e) => edit((d) => { d.pools[i].name = e.target.value; })}
          aria-label="pool name"
        />
        <span className="chip">
          {p.count === 1 ? '1 machine' : `${p.count} machines`}
        </span>
        {first && <span className="chip">the job runs here</span>}
        <span className="acts">
          {i > 0 && (
            <button
              className="btn"
              title="move it up — the job runs on the first machine in the file"
              onClick={() => edit((d) => {
                [d.pools[i - 1], d.pools[i]] = [d.pools[i], d.pools[i - 1]];
              })}
            >
              ↑
            </button>
          )}
          <button
            className="btn"
            disabled={only}
            title={only ? 'a scenario needs at least one machine' : 'remove this pool'}
            onClick={() => edit((d) => { d.pools.splice(i, 1); })}
          >
            Remove
          </button>
        </span>
      </header>

      <div className="row">
        <div className="field">
          <label>How many</label>
          <input
            type="number"
            min={1}
            max={24}
            value={p.count}
            onChange={(e) => edit((d) => {
              d.pools[i].count = Math.max(1, Math.min(24, Number(e.target.value) || 1));
            })}
          />
        </div>
        <div className="field grow">
          <label>Instance</label>
          <select
            value={p.instance}
            onChange={(e) => edit((d) => { d.pools[i].instance = e.target.value; })}
          >
            {palette.instances.map((x) => (
              <option key={x.name} value={x.name}>
                {x.name} — {x.vcpu} vCPU, {(x.memoryMb / 1024).toFixed(0)} GB
                {x.burstable ? ' (burstable)' : ''}
              </option>
            ))}
          </select>
          {inst?.burstable && (
            <span className="hint warn">
              Burstable: full speed until its credits run out, then a fraction of a core. This is
              where stragglers come from.
            </span>
          )}
        </div>
        <div className="field grow">
          <label>Region</label>
          <select
            value={region?.name ?? ''}
            onChange={(e) => edit((d) => {
              const r = palette.regions.find((x) => x.name === e.target.value);
              if (r) d.pools[i].zones = [r.zones[0]];
            })}
          >
            {palette.regions.map((r) => (
              <option key={r.name} value={r.name}>
                {r.name} — {r.where}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="zones">
        <span className="lbl">Zones</span>
        {(region?.zones ?? []).map((z) => (
          <button
            key={z}
            className={`zone${p.zones.includes(z) ? ' on' : ''}`}
            onClick={() => edit((d) => {
              const zs = d.pools[i].zones;
              const at = zs.indexOf(z);
              if (at >= 0) { if (zs.length > 1) zs.splice(at, 1); }
              else zs.push(z);
            })}
          >
            {z}
          </button>
        ))}
        <span className="hint">
          {p.zones.length > 1
            ? `dealt round-robin over ${p.zones.length} zones — the pool survives one of them going`
            : 'all in one zone, where talking is free and a zone failure takes the pool'}
        </span>
      </div>

      <style>{`
        .pool {
          border: 1px solid var(--border); border-radius: var(--r);
          padding: 14px 16px 16px; background: var(--surface-2);
          display: flex; flex-direction: column; gap: 12px;
        }
        .pool > header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
        .pool .nm {
          width: 150px; height: 30px; padding: 0 10px;
          font: inherit; font-family: var(--mono); font-size: 13px; font-weight: 500;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .pool .acts { margin-left: auto; display: flex; gap: 6px; }
        .row { display: flex; gap: 12px; flex-wrap: wrap; }
        .field { display: flex; flex-direction: column; gap: 4px; min-width: 90px; }
        .field.grow { flex: 1; min-width: 180px; }
        .field label { font-size: 11.5px; color: var(--text-3); }
        .field input, .field select {
          height: 32px; padding: 0 10px; font: inherit; font-size: 13px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .hint { font-size: 11px; color: var(--text-3); }
        .hint.warn { color: var(--warn); }
        .zones { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
        .zones .lbl { font-size: 11.5px; color: var(--text-3); }
        .zone {
          height: 26px; padding: 0 11px; font: inherit; font-size: 11.5px;
          font-family: var(--mono); cursor: pointer;
          color: var(--text-3); background: var(--surface);
          border: 1px solid var(--border); border-radius: 999px;
        }
        .zone.on { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
      `}</style>
    </div>
  );
}

/* ------------------------------------------------------- placing the services
 *
 * The two-names problem, made visible. A machine *runs* a Java class and
 * thereby *serves* the gRPC service that class implements; a scenario names the
 * first and a job finds its peers by the second. Both are on every row here, so
 * nobody has to hold the pair in their head — and neither can be typed wrongly,
 * because both were read off the compiled classes.
 */
function Placing({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  const orphans = unplaced(draft, palette);
  return (
    <Panel
      title="Services"
      note={`${palette.services.length}`}
    >
      <p className="lead">
        What your code offers a machine, read off the classes it compiles to. Tick a pool to put
        a service on it — a service can be on several, and that is how a fleet of workers is
        made.
      </p>

      <div className="scroll">
        <table>
          <thead>
            <tr>
              <th>Runs · Java class</th>
              <th>Serves · .proto</th>
              <th>Methods</th>
              {draft.pools.map((p, i) => (
                <th key={i} className="on">{p.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {palette.services.map((s) => (
              <tr key={s.cls}>
                <td className="id">
                  {s.cls}
                  {s.source && <div className="src">{s.source}</div>}
                </td>
                <td className="id">{s.qualified}</td>
                <td className="dim">
                  {s.methods.map((m) => (
                    <span key={m.name} className={m.idempotent ? 'm' : 'm unsafe'}
                          title={m.idempotent
                            ? 'the .proto declares it idempotent, so it is safe to retry'
                            : 'no idempotency_level in the .proto — retrying it is refused'}>
                      {m.name}
                    </span>
                  ))}
                </td>
                {draft.pools.map((p, i) => (
                  <td key={i} className="on">
                    <input
                      type="checkbox"
                      checked={p.runs.includes(s.cls)}
                      aria-label={`run ${s.cls} on ${p.name}`}
                      onChange={(e) => edit((d) => {
                        const runs = d.pools[i].runs;
                        if (e.target.checked) runs.push(s.cls);
                        else runs.splice(runs.indexOf(s.cls), 1);
                      })}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!palette.services.length && (
        <p className="muted">
          Nothing here extends a generated <code>ImplBase</code>, so no machine can be given
          anything. {palette.other} other classes compiled — a service is one that implements a
          service from your <code>.proto</code>.
        </p>
      )}

      {orphans.length > 0 && (
        <div className="flag warn">
          <span>⚠</span>
          <span>
            <strong>
              {orphans.length} service{orphans.length === 1 ? '' : 's'} on no machine
            </strong>{' '}
            — {orphans.map((o) => o.cls).join(', ')}. The scenario will still run; nothing will
            ever call them.
          </span>
        </div>
      )}

      <div className="jobs">
        <div className="field">
          <label htmlFor="job">Job</label>
          <select
            id="job"
            value={draft.job}
            onChange={(e) => edit((d) => { d.job = e.target.value; })}
          >
            {!palette.jobs.length && <option value="">nothing implements losim.api.Job</option>}
            {palette.jobs.map((j) => (
              <option key={j} value={j}>{j}</option>
            ))}
          </select>
          <span className="hint">
            The one class that drives the run. It is not placed on a machine — it runs on the
            first one.
          </span>
        </div>
        <div className="field">
          <label htmlFor="seed">Seed</label>
          <input
            id="seed"
            type="number"
            value={draft.seed}
            onChange={(e) => edit((d) => { d.seed = Number(e.target.value) || 1; })}
          />
          <span className="hint">Same seed, same weather.</span>
        </div>
        <div className="field">
          <label htmlFor="exp">Expected run</label>
          <input
            id="exp"
            type="number"
            value={draft.expectedRunRefSeconds}
            onChange={(e) => edit((d) => {
              d.expectedRunRefSeconds = Math.max(1, Number(e.target.value) || 1);
            })}
          />
          <span className="hint">In reference seconds — the clock the scenario is written in.</span>
        </div>
      </div>

      <style>{`
        .lead { font-size: 13px; margin: 0 0 14px; }
        .scroll { overflow-x: auto; margin: 0 -20px; padding: 0 20px; }
        td.id, .id { font-family: var(--mono); font-size: 12.5px; }
        .src { font-size: 11px; color: var(--text-3); margin-top: 2px; }
        th.on, td.on { text-align: center; width: 84px; }
        .m {
          display: inline-block; margin-right: 6px; padding: 1px 7px;
          font-family: var(--mono); font-size: 11px; border-radius: 999px;
          background: var(--surface-2); border: 1px solid var(--border);
        }
        .m.unsafe { color: var(--warn); border-color: currentColor; }
        .flag {
          display: flex; gap: 10px; align-items: flex-start; margin-top: 14px;
          padding: 11px 14px; border-radius: var(--r-sm); font-size: 12.5px;
          background: var(--accent-soft); color: var(--text-2);
        }
        .flag.warn { background: #fff8e8; color: #6b4d09; }
        @media (prefers-color-scheme: dark) { .flag.warn { background: #2a2211; color: #e6c684; } }
        .jobs { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 18px;
                padding-top: 16px; border-top: 1px solid var(--border); }
        .field { display: flex; flex-direction: column; gap: 4px; min-width: 150px; flex: 1; }
        .field label { font-size: 11.5px; color: var(--text-3); }
        .field input, .field select {
          height: 32px; padding: 0 10px; font: inherit; font-size: 13px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .hint { font-size: 11px; color: var(--text-3); }
      `}</style>
    </Panel>
  );
}

/* --------------------------------------------------------------- the weather
 *
 * A design tested on a day nothing went wrong is a design nobody has tested.
 *
 * Three kinds, and the difference between the first two is the whole point: a
 * fault at 300 refMs teaches a fleet to survive 300 refMs, and a *rate* teaches
 * it to survive whenever — which is the harder and more honest thing, and the
 * reason a sweep is twenty seeds rather than one lucky afternoon.
 */
function Weather({
  draft, palette, machines, edit,
}: {
  draft: Draft;
  palette: Palette;
  machines: { name: string; pool: string }[];
  edit: (f: (d: Draft) => void) => void;
}) {
  const pools = [...new Set(draft.pools.map((p) => p.name))];
  const rpcs = palette.services.flatMap((s) =>
    s.methods.map((m) => ({
      method: `${s.qualified}.${m.name}`,
      idempotent: m.idempotent,
    })),
  );
  const seen = new Set<string>();
  const methods = rpcs.filter((r) => (seen.has(r.method) ? false : seen.add(r.method)));

  return (
    <Panel title="Weather" note="optional, and the reason to have run this twice">
      <section>
        <header>
          <h3>A machine dies, at a moment</h3>
          <button
            className="btn"
            disabled={!machines.length}
            onClick={() => edit((d) => {
              d.kills.push({ atRefMs: 300, target: machines[0]?.name ?? '', restartAfterRefMs: 2000 });
            })}
          >
            + Fault
          </button>
        </header>
        {!draft.kills.length && (
          <p className="none">Nothing dies. Every run of this will be the good afternoon.</p>
        )}
        {draft.kills.map((f, i) => (
          <div className="rule" key={i}>
            <span>at</span>
            <input type="number" value={f.atRefMs}
                   onChange={(e) => edit((d) => { d.kills[i].atRefMs = Number(e.target.value) || 0; })} />
            <span>refMs, kill</span>
            <select value={f.target}
                    onChange={(e) => edit((d) => { d.kills[i].target = e.target.value; })}>
              {machines.map((m) => (
                <option key={m.name} value={m.name}>{m.name}</option>
              ))}
            </select>
            <span>and bring it back after</span>
            <input type="number" value={f.restartAfterRefMs}
                   onChange={(e) => edit((d) => {
                     d.kills[i].restartAfterRefMs = Math.max(0, Number(e.target.value) || 0);
                   })} />
            <span>refMs</span>
            <button className="btn" onClick={() => edit((d) => { d.kills.splice(i, 1); })}>×</button>
            {f.restartAfterRefMs === 0 && (
              <span className="aside">0 — it never comes back, which is a different exercise</span>
            )}
          </div>
        ))}
      </section>

      <section>
        <header>
          <h3>A standing rate of failure</h3>
          <button
            className="btn"
            onClick={() => edit((d) => {
              d.chaos.push({ kind: 'freeze', everyRefMs: 700, among: pools[0] ?? '', forRefMs: 150, factor: 2 });
            })}
          >
            + Chaos
          </button>
        </header>
        {!draft.chaos.length && (
          <p className="none">
            No standing weather. A scripted fault teaches a fleet to survive one instant; a rate
            teaches it to survive whenever.
          </p>
        )}
        {draft.chaos.map((c, i) => (
          <div className="rule" key={i}>
            <select value={c.kind}
                    onChange={(e) => edit((d) => { d.chaos[i].kind = e.target.value as Chaos['kind']; })}>
              <option value="freeze">freeze</option>
              <option value="kill">kill</option>
              <option value="degrade">degrade</option>
            </select>
            <span>one of</span>
            <select value={c.among}
                    onChange={(e) => edit((d) => { d.chaos[i].among = e.target.value; })}>
              {pools.map((p) => <option key={p} value={p}>{p}</option>)}
              {machines.map((m) => <option key={m.name} value={m.name}>{m.name}</option>)}
            </select>
            <span>every</span>
            <input type="number" value={c.everyRefMs}
                   onChange={(e) => edit((d) => {
                     d.chaos[i].everyRefMs = Math.max(1, Number(e.target.value) || 1);
                   })} />
            <span>refMs</span>
            {c.kind !== 'kill' && (
              <>
                <span>for</span>
                <input type="number" value={c.forRefMs}
                       onChange={(e) => edit((d) => {
                         d.chaos[i].forRefMs = Math.max(0, Number(e.target.value) || 0);
                       })} />
                <span>refMs</span>
              </>
            )}
            {c.kind === 'degrade' && (
              <>
                <span>×</span>
                <input type="number" value={c.factor}
                       onChange={(e) => edit((d) => {
                         d.chaos[i].factor = Math.max(1, Number(e.target.value) || 1);
                       })} />
                <span>slower</span>
              </>
            )}
            <button className="btn" onClick={() => edit((d) => { d.chaos.splice(i, 1); })}>×</button>
          </div>
        ))}
      </section>

      <section>
        <header>
          <h3>Retry a method</h3>
          <button
            className="btn"
            disabled={!methods.length}
            onClick={() => edit((d) => {
              const safe = methods.find((m) => m.idempotent) ?? methods[0];
              d.retries.push({
                method: safe.method, attempts: 3, backoffRefMs: 40, unsafe: !safe.idempotent,
              });
            })}
          >
            + Retry
          </button>
        </header>
        {!draft.retries.length && (
          <p className="none">
            Nothing is retried. A call that fails, fails — which is what makes a fault visible in
            the first place.
          </p>
        )}
        {draft.retries.map((r, i) => {
          const safe = methods.find((m) => m.method === r.method)?.idempotent ?? false;
          return (
            <div className="rule" key={i}>
              <select
                value={r.method}
                onChange={(e) => edit((d) => {
                  d.retries[i].method = e.target.value;
                  d.retries[i].unsafe = !(methods.find((m) => m.method === e.target.value)?.idempotent);
                })}
              >
                {methods.map((m) => (
                  <option key={m.method} value={m.method}>
                    {m.method}{m.idempotent ? '' : ' — not declared idempotent'}
                  </option>
                ))}
              </select>
              <span>up to</span>
              <input type="number" value={r.attempts}
                     onChange={(e) => edit((d) => {
                       d.retries[i].attempts = Math.max(1, Number(e.target.value) || 1);
                     })} />
              <span>times, backing off</span>
              <input type="number" value={r.backoffRefMs}
                     onChange={(e) => edit((d) => {
                       d.retries[i].backoffRefMs = Math.max(0, Number(e.target.value) || 0);
                     })} />
              <span>refMs</span>
              <button className="btn" onClick={() => edit((d) => { d.retries.splice(i, 1); })}>×</button>
              {!safe && (
                <span className="aside warn">
                  its <code>.proto</code> declares no <code>idempotency_level</code>, so this is
                  written <code>unsafe: true</code> — running it twice is not known to be safe
                </span>
              )}
            </div>
          );
        })}
      </section>

      <style>{`
        section { padding-top: 16px; }
        section + section { border-top: 1px solid var(--border); margin-top: 4px; }
        section > header { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
        section h3 {
          margin: 0; font-size: 13.5px; font-weight: 500; color: var(--text);
          text-transform: none; letter-spacing: 0;
        }
        section > header .btn { margin-left: auto; }
        .none { font-size: 12.5px; color: var(--text-3); margin: 0; }
        .rule {
          display: flex; align-items: center; gap: 7px; flex-wrap: wrap;
          font-size: 12.5px; color: var(--text-2); padding: 7px 0;
        }
        .rule + .rule { border-top: 1px solid var(--border); }
        .rule input, .rule select {
          height: 28px; padding: 0 8px; font: inherit; font-size: 12.5px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .rule input[type=number] { width: 74px; font-family: var(--mono); }
        .rule .btn { height: 26px; width: 26px; padding: 0; justify-content: center; margin-left: 4px; }
        .aside { flex-basis: 100%; font-size: 11px; color: var(--text-3); }
        .aside.warn { color: var(--warn); }
      `}</style>
    </Panel>
  );
}
