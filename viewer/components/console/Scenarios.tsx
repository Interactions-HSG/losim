'use client';

/**
 * Authoring a scenario: the machines, and what runs on them.
 *
 * The Java says what *can* run. This says **where it runs and what goes wrong**,
 * which is the half of a distributed system this course is actually about and
 * the half that has only ever been reachable by copying somebody else's YAML and
 * editing it until it stopped complaining.
 *
 * Three things are read off the lab rather than written down here, so you do
 * not have to already know them: the **classes** (from the compiled
 * bytecode, so a service that does not exist cannot be placed), the
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
  distances, expand, firstDraft, perHour, toYaml, unplaced,
  BASE_RECORDS, FAULT_KINDS, PAIRED,
  type Chaos, type Draft, type Fault, type Pool,
} from '../../lib/author.ts';
import {
  openScenario, palette as fetchPalette, saveScenario, type Palette,
} from '../../lib/lab.ts';

export function Scenarios() {
  const { nudge, startBuild, go } = useConsole();
  /**
   * Listing what exists, writing a new one, or editing one that already is.
   *
   * The list is the page, because a lab accumulates scenarios and the thing you
   * do most often is run one of them again. Writing and editing are the
   * occasional acts, so they are behind buttons rather than in front of the list.
   */
  const [mode, setMode] = useState<'list' | 'new' | 'edit'>('list');
  const [palette, setPalette] = useState<Palette | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  /** The file an edit writes back to — absent while composing a new one. */
  const [editing, setEditing] = useState<{ name: string } | null>(null);
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
    // The console follows the build from here — it outlives this page, because
    // pressing Create moves you to Runs immediately rather than watching text
    // scroll under the button. `startBuild` refuses the way the server does,
    // through the global banner, if something else is already running.
    await startBuild(wrote.scenario!);
    setSaying(null);
    setMode('list');
    go('runs');
  }, [draft, palette, yaml, startBuild, go]);

  /**
   * Load an existing scenario back into this same form.
   *
   * The server does the reading — the loader checks it, then a second walk of
   * the same parse tree fills in exactly what this form has a control for.
   * Anything it does not comes back as a refusal naming the key, not a Draft
   * missing something silently: there is nowhere in this form to notice that.
   */
  const openForEdit = useCallback(async (name: string) => {
    setRefused(null);
    const said = await openScenario(name);
    if (said.error || !said.draft) {
      setRefused(said.error ?? 'could not read that scenario');
      return;
    }
    setDraft(said.draft);
    setEditing({ name });
    setMode('edit');
  }, []);

  /**
   * Write it back, through the same loader a run uses — and no run this time.
   *
   * To whatever the name box says, not to the file it was opened from: renaming
   * it there is how a variant of an existing scenario gets written, and the
   * original is left exactly as it was.
   */
  const saveEdit = useCallback(async () => {
    if (!draft || !editing) return;
    setSaying('saving…');
    setRefused(null);
    const wrote = await saveScenario(draft.name, yaml);
    if (wrote.error) {
      setRefused(wrote.error);
      setSaying(null);
      return;
    }
    setSaying(null);
    setEditing(null);
    setMode('list');
    // The list shows names and paths, not content, so nothing there is stale —
    // but a scenario just replaced, or one just written beside it, is worth the
    // same nudge a new one gets.
    nudge();
  }, [draft, editing, yaml, nudge]);

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
          ) : mode === 'new' ? (
            <>
              What this lab compiles to is read off the classes, so nothing here can name a
              service that is not there. Nothing is written until you press create.
            </>
          ) : (
            <>
              The same form, filled in from what is already written. Nothing is written back
              until you press save — and a scenario the form could not write back exactly as
              it found it is refused before it opens at all, rather than opened with
              something quietly missing from it.
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
            <button
              className="btn"
              onClick={() => { setMode('list'); setRefused(null); setEditing(null); }}
            >
              Cancel
            </button>
          )
        }
      />

      {busy && <Panel><p className="muted">reading the lab…</p></Panel>}

      {mode === 'list' && refused && (
        <Panel title="This one can't open here">
          <pre className="log bad">{refused}</pre>
          <p className="note">
            The loader's own sentence, with the line it was written on. Edit the file directly,
            then open it here again once that's out — this form only ever refuses to open one;
            it never opens one with something quietly missing.
          </p>
        </Panel>
      )}

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

      {/* New needs the palette to have anything to place; Edit needs only the
          draft it already opened with — the server refused before this ever
          rendered if that draft could not fully represent the file. */}
      {((mode === 'new' && canAuthor) || mode === 'edit') && !busy && palette && draft && (
        <div className="two">
          <div className="col">
            <Machines draft={draft} palette={palette} machines={machines} edit={edit} />
            <Scale draft={draft} edit={edit} />
            <Network draft={draft} palette={palette} edit={edit} />
            <Retries draft={draft} palette={palette} edit={edit} />
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
              {/* Editable in both modes. Opening one scenario and saving it under
                  another name is how a second design gets written — the first
                  one, changed in one place — and a disabled box made that the one
                  thing the form could not do. */}
              <div className="field">
                <label htmlFor="scname">Save as</label>
                <input
                  id="scname"
                  value={draft.name}
                  onChange={(e) => edit((d) => { d.name = e.target.value; })}
                />
                <span className="hint">
                  Written to <code>scenarios/{draft.name}.yaml</code>
                  {editing && draft.name !== editing.name.replace(/\.ya?ml$/, '') ? (
                    <> — a new file. <code>{editing.name}</code> is left as it was.</>
                  ) : palette.scenarios.includes(`${draft.name}.yaml`) ? (
                    <> — <strong>which already exists and will be replaced</strong></>
                  ) : null}
                </span>
              </div>
              <label className="check">
                <input
                  type="checkbox"
                  checked={draft.tightMargin}
                  onChange={(e) => edit((d) => { d.tightMargin = e.target.checked; })}
                />
                <span>this one is meant to be thin</span>
              </label>
              <span className="hint">
                A note in the trace and nothing else — nothing in the run reads it. For
                exercises where the gap between working and not working is meant to be narrow.
              </span>
              <button
                className="btn primary wide"
                onClick={() => void (editing ? saveEdit() : create())}
                disabled={!!saying || !draft.job || !draft.name.trim()}
              >
                {saying ?? (editing ? 'Save' : 'Create and run')}
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
        .field input {
          height: 32px; padding: 0 10px; font: inherit; font-size: 13px;
          font-family: var(--mono);
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .hint, .field .hint { font-size: 11.5px; color: var(--text-3); }
        .check { display: flex; align-items: center; gap: 7px; margin-top: 14px; font-size: 12.5px; }
      `}</style>

      {/* Always mounted, whichever mode the page is in: this is what tells the
          console there is a lab behind the page at all. Hidden while writing or
          editing, so the form has the screen. Pressing ▶ leaves this page — the
          console follows the build, not this panel — so there is no log here to
          watch. */}
      <div hidden={mode !== 'list'}>
      <Panel title="Every scenario here" note="press ▶ to build and run">
        <Lab onEdit={(name) => void openForEdit(name)} />
      </Panel>
      </div>

    </>
  );
}

/* ------------------------------------------------------------------ the scale
 *
 * One number, and it is the only one anybody could honestly supply.
 *
 * **How many times bigger is the design than the run measuring it.** Nobody
 * knows what a probe ladder should be, how many workers to vary, or how fast the
 * clock can be run before its own timings drift — those are properties of the
 * run, which is the thing that has not happened yet. Asking for them was asking
 * for guesses, and a simulator you have to predict before using is not one.
 *
 * At 1 there is no model: the run is the thing, and every number on screen is
 * what happened. Above it the engine climbs its own ladder, fits a law per
 * resource and projects — and refuses the ones whose law does not hold, which is
 * the honest half of it.
 */
function Scale({ draft, edit }: { draft: Draft; edit: (f: (d: Draft) => void) => void }) {
  const modelled = draft.scale > 1;
  return (
    <Panel
      title="Scale"
      note={modelled
        ? `a model of ${(draft.scale * BASE_RECORDS).toLocaleString()} records`
        : 'one run, nothing projected'}
    >
      <p className="lead">
        How much bigger is this design than the run you can afford to watch?
      </p>

      <div className="row">
        <div className="field">
          <label htmlFor="scale">Scale</label>
          <div className="mult">
            <input
              id="scale"
              type="number"
              min={1}
              step={1}
              value={draft.scale}
              onChange={(e) => edit((d) => {
                d.scale = Math.max(1, Math.round(Number(e.target.value) || 1));
                if (d.scale <= 1) d.mode = 'direct';
              })}
            />
            <span className="x">×</span>
          </div>
          <span className="hint">
            {modelled
              ? `${draft.scale}× the biggest run the engine can measure — ${(draft.scale * BASE_RECORDS).toLocaleString()} records.`
              : 'One run of itself. Nothing is projected, so nothing can be projected wrongly.'}
          </span>
        </div>
        <div className="quick">
          {[1, 10, 100, 1000].map((n) => (
            <button
              key={n}
              className={`chip${draft.scale === n ? ' on' : ''}`}
              onClick={() => edit((d) => {
                d.scale = n;
                if (n <= 1) d.mode = 'direct';
              })}
            >
              {n === 1 ? 'just run it' : `${n}×`}
            </button>
          ))}
        </div>
      </div>

      {modelled && (
        <div className="field">
          <label htmlFor="mode">And at that size</label>
          <select
            id="mode"
            value={draft.mode}
            onChange={(e) => edit((d) => { d.mode = e.target.value as Draft['mode']; })}
          >
            <option value="scaled">measure a small run and project to it</option>
            <option value="direct">actually run all of it</option>
          </select>
          <span className="hint">
            {draft.mode === 'scaled'
              ? 'The engine picks the ladder, the fleet sizes and the clock. What comes back '
                + 'is a projection with an error bar, and any resource whose law does not hold '
                + 'is refused rather than extrapolated.'
              : 'Every record runs. Honest, and a long afternoon at a real size — which is '
                + 'what the other option exists to avoid.'}
          </span>
        </div>
      )}

      <style>{`
        .lead { font-size: 13px; margin: 0 0 14px; }
        .row { display: flex; gap: 20px; align-items: flex-start; flex-wrap: wrap; margin-bottom: 14px; }
        .field { display: flex; flex-direction: column; gap: 4px; min-width: 150px; }
        .field label { font-size: 11.5px; color: var(--text-3); }
        .mult { display: flex; align-items: center; gap: 6px; }
        .mult .x { font-size: 14px; color: var(--text-3); }
        .field input, .field select {
          height: 32px; padding: 0 10px; font: inherit; font-size: 13px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .field input { font-family: var(--mono); width: 110px; }
        .hint { font-size: 11px; color: var(--text-3); max-width: 46ch; }
        .quick { display: flex; gap: 6px; align-items: center; height: 32px; margin-top: 17px; }
        .chip {
          height: 26px; padding: 0 11px; font: inherit; font-size: 11.5px; cursor: pointer;
          color: var(--text-3); background: var(--surface);
          border: 1px solid var(--border); border-radius: 999px;
        }
        .chip.on { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
      `}</style>
    </Panel>
  );
}

/* ---------------------------------------------------------------- the machines
 *
 * The part a student actually authors. Each block is a pool — machines that grow
 * and shrink together — and a single machine is a pool of one, which is why the
 * count starts at 1 and there is no separate kind of thing for it.
 */
function Machines({
  draft, palette, machines, edit,
}: {
  draft: Draft;
  palette: Palette;
  machines: { name: string; pool: string }[];
  edit: (f: (d: Draft) => void) => void;
}) {
  const ms = expand(draft);
  const orphans = unplaced(draft, palette);
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
                prefix: `pool${d.pools.length + 1}`,
                instance: palette.instances[0]?.name ?? 'm5.large',
                zones: [palette.regions[0]?.zones[0] ?? 'eu-central-1a'],
                runs: [],
                memoryMb: null,
                diskMb: null,
                overrides: [],
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
        round-robin over the zones you give it. What it runs, where it sits and what happens to
        it are all on the block, because they are all facts about the same machines.
      </p>

      <div className="pools">
        {draft.pools.map((p, i) => (
          <PoolCard
            key={i}
            p={p}
            i={i}
            draft={draft}
            palette={palette}
            machines={machines}
            only={draft.pools.length === 1}
            first={i === 0}
            edit={edit}
          />
        ))}
      </div>

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

      <style>{`
        .lead { font-size: 13px; margin: 0 0 14px; }
        .pools { display: flex; flex-direction: column; gap: 12px; }
        .flag {
          display: flex; gap: 10px; align-items: flex-start; margin-top: 14px;
          padding: 11px 14px; border-radius: var(--r-sm);
          background: var(--accent-soft); color: var(--text-2); font-size: 12.5px;
        }
        .flag.warn { background: #fff8e8; color: #6b4d09; }
        @media (prefers-color-scheme: dark) { .flag.warn { background: #2a2211; color: #e6c684; } }
      `}</style>
    </Panel>
  );
}

function PoolCard({
  p, i, draft, palette, machines, only, first, edit,
}: {
  p: Pool;
  i: number;
  draft: Draft;
  palette: Palette;
  machines: { name: string; pool: string }[];
  only: boolean;
  first: boolean;
  edit: (f: (d: Draft) => void) => void;
}) {
  const inst = palette.instances.find((x) => x.name === p.instance);
  // The names the loader will give this pool's machines, which is what an
  // exception has to be keyed by and what a fault has to point at.
  const count = Math.max(1, Math.round(p.count));
  const numbered = count > 1 || p.prefix !== p.name;
  const names = Array.from({ length: count }, (_, k) => (numbered ? `${p.prefix}${k}` : p.name));
  const mine = new Set(names);
  return (
    <div className="pool">
      <header>
        <input
          className="nm"
          value={p.name}
          onChange={(e) => edit((d) => {
            // The prefix follows the name while the two are the same, because
            // that is the ordinary case and nobody renaming `workers` means to
            // leave its machines called `workers0`. One deliberately set apart
            // stays put.
            if (d.pools[i].prefix === d.pools[i].name) d.pools[i].prefix = e.target.value;
            d.pools[i].name = e.target.value;
          })}
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
        {(p.count > 1 || p.prefix !== p.name) && (
          <div className="field">
            <label>Called</label>
            <input
              value={p.prefix}
              onChange={(e) => edit((d) => { d.pools[i].prefix = e.target.value; })}
              aria-label="machine name prefix"
            />
            <span className="hint">
              {p.prefix ? `${p.prefix}0, ${p.prefix}1, …` : 'machines are named prefix0, prefix1'}
            </span>
          </div>
        )}
        <div className="field grow">
          <label>Instance</label>
          <select
            value={p.instance}
            onChange={(e) => edit((d) => { d.pools[i].instance = e.target.value; })}
          >
            {palette.instances.map((x) => (
              <option key={x.name} value={x.name}>
                {x.name} — {x.vcpu} vCPU, {(x.memoryMb / 1024).toFixed(0)} GB
              </option>
            ))}
          </select>
          {/* Width is the only thing about an instance that changes how it runs:
              the thread pool is sized by it, and declared work is multiplied by
              2 ÷ vcpu. Said here because it is the one number on this control
              that has a consequence, and one core is easy to pick by accident. */}
          {inst && inst.vcpu < 2 && (
            <span className="hint warn">
              One core: half the reference machine. It runs one call at a time, and declared work
              takes twice as long — this is how you make a straggler.
            </span>
          )}
        </div>
      </div>

      {/* What this pool runs, and whether the job starts on it. Both are the same
          question — what code is on this machine — so they are the same control.
          The list is read off the compiled classes, so nothing here can name a
          service that is not there. */}
      <div className="serves">
        <span className="lbl">Runs</span>
        {palette.services.map((sv) => {
          const on = p.runs.includes(sv.cls);
          const unsafe = sv.methods.filter((m) => !m.idempotent).map((m) => m.name);
          return (
            <button
              key={sv.cls}
              className={`svc${on ? ' on' : ''}`}
              title={`serves ${sv.qualified} — ${sv.methods.map((m) => m.name).join(', ')}`
                     + (unsafe.length ? `\nnot declared idempotent: ${unsafe.join(', ')}` : '')}
              onClick={() => edit((d) => {
                const runs = d.pools[i].runs;
                const at = runs.indexOf(sv.cls);
                if (at >= 0) runs.splice(at, 1); else runs.push(sv.cls);
              })}
            >
              {sv.cls}
            </button>
          );
        })}
        {palette.jobs.map((j) => (
          <button
            key={j}
            className={`svc job${draft.job === j && first ? ' on' : ''}`}
            title="the job drives the run, and losim starts it on the first machine in the file"
            onClick={() => edit((d) => {
              d.job = j;
              // The job is not placed — it runs on the first machine there is. So
              // saying "it starts here" is saying "this pool is first", and the
              // form moves it rather than writing a key the loader does not have.
              if (i > 0) {
                const [moved] = d.pools.splice(i, 1);
                d.pools.unshift(moved);
              }
            })}
          >
            ▶ {j}
          </button>
        ))}
        {!palette.services.length && !palette.jobs.length && (
          <span className="hint">
            Nothing here extends a generated <code>ImplBase</code> or implements{' '}
            <code>losim.api.Job</code>, so there is nothing a machine can be given.
          </span>
        )}
      </div>

      {/* Caps, and the third state. An empty box is not zero: it is whatever the
          instance type says, which is what a pool that never mentions these
          gets. A `0` is a machine that cannot hold anything — legal, and a
          different scenario — so the two must not be typed by the same gesture. */}
      <div className="row">
        <div className="field grow">
          <label>Memory cap</label>
          <input
            type="number"
            min={0}
            step="any"
            placeholder={inst ? `${inst.memoryMb} — the instance’s own` : 'the instance’s own'}
            value={p.memoryMb ?? ''}
            onChange={(e) => edit((d) => {
              const v = e.target.value.trim();
              d.pools[i].memoryMb = v === '' ? null : Math.max(0, Number(v) || 0);
            })}
          />
          <span className="hint">
            {p.memoryMb === null
              ? 'MB. Empty is the instance’s own — nothing is written to the file.'
              : `MB, instead of the ${inst?.memoryMb ?? '?'} this instance comes with. Under it, `
                + 'a machine that holds too much fills up and says so.'}
          </span>
        </div>
        <div className="field grow">
          <label>Disk cap</label>
          <input
            type="number"
            min={0}
            step="any"
            placeholder={inst ? `${inst.storageGb * 1024} — the instance’s own` : 'the instance’s own'}
            value={p.diskMb ?? ''}
            onChange={(e) => edit((d) => {
              const v = e.target.value.trim();
              d.pools[i].diskMb = v === '' ? null : Math.max(0, Number(v) || 0);
            })}
          />
          <span className="hint">
            MB. Only a job that calls <code>wroteDisk</code> can ever reach it.
          </span>
        </div>
      </div>

      {/* One machine unlike the rest. A pool of eight where one is half the size
          is the cheapest straggler there is, and it cannot be said at pool level
          — that is the whole point of it. Only offered where there is more than
          one machine to be the exception to. */}
      {names.length > 1 && (
        <div className="excs">
          <div className="exhead">
            <span className="lbl">Exceptions</span>
            <button
              className="btn"
              onClick={() => edit((d) => {
                const taken = new Set(d.pools[i].overrides.map((o) => o.machine));
                const free = names.find((nm) => !taken.has(nm)) ?? names[0];
                d.pools[i].overrides.push({
                  machine: free, instance: '', zone: '', memoryMb: null, diskMb: null,
                });
              })}
              disabled={p.overrides.length >= names.length}
            >
              + Exception
            </button>
          </div>
          {!p.overrides.length && (
            <span className="hint">
              Every machine in this pool is the same. Add one to make a straggler, or a
              machine too small for the work it is given.
            </span>
          )}
          {p.overrides.map((o, k) => (
            <div className="exc" key={k}>
              <select
                value={o.machine}
                aria-label="which machine"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].machine = e.target.value; })}
              >
                {names.map((nm) => <option key={nm} value={nm}>{nm}</option>)}
                {/* An override the loader would silently ignore: it names no
                    machine in this pool. Kept rather than dropped, and shown as
                    what it is. */}
                {!names.includes(o.machine) && <option value={o.machine}>{o.machine} — no such machine</option>}
              </select>
              <select
                value={o.instance}
                aria-label="instance for this machine"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].instance = e.target.value; })}
              >
                <option value="">same instance</option>
                {palette.instances.map((x) => (
                  <option key={x.name} value={x.name}>{x.name}</option>
                ))}
              </select>
              <select
                value={o.zone}
                aria-label="zone for this machine"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].zone = e.target.value; })}
              >
                <option value="">same zone</option>
                {palette.regions.flatMap((r) => r.zones).map((z) => (
                  <option key={z} value={z}>{z}</option>
                ))}
              </select>
              <input
                type="number" min={0} step="any" placeholder="memory MB"
                aria-label="memory cap for this machine"
                value={o.memoryMb ?? ''}
                onChange={(e) => edit((d) => {
                  const v = e.target.value.trim();
                  d.pools[i].overrides[k].memoryMb = v === '' ? null : Math.max(0, Number(v) || 0);
                })}
              />
              <input
                type="number" min={0} step="any" placeholder="disk MB"
                aria-label="disk cap for this machine"
                value={o.diskMb ?? ''}
                onChange={(e) => edit((d) => {
                  const v = e.target.value.trim();
                  d.pools[i].overrides[k].diskMb = v === '' ? null : Math.max(0, Number(v) || 0);
                })}
              />
              <button className="btn" onClick={() => edit((d) => { d.pools[i].overrides.splice(k, 1); })}>×</button>
              {!names.includes(o.machine) && (
                <span className="hint warn">
                  This pool has no machine called <code>{o.machine}</code>, so the run ignores
                  this line. Point it at one, or remove it.
                </span>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Every zone there is, not just the ones near the first. A pool dealt over
          two continents is a legal scenario and an instructive one, and the form
          used to make it unreachable by filtering this list to one region. */}
      <div className="zones">
        <span className="lbl">Zones</span>
        {palette.regions.map((r) => (
          <span className="reg" key={r.name}>
            <span className="rn" title={r.where}>{r.name}</span>
            {r.zones.map((z) => (
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
                {z.replace(r.name, '') || z}
              </button>
            ))}
          </span>
        ))}
        <span className="hint">
          {p.zones.length > 1
            ? `dealt round-robin over ${p.zones.length} zones — the pool survives one of them `
              + 'going, and every call between two of them is charged as one that crossed'
            : 'all in one zone, where talking is free and a zone failure takes the pool'}
        </span>
      </div>

      <Weather p={p} i={i} draft={draft} palette={palette} machines={machines}
               names={names} mine={mine} edit={edit} />

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
        .excs { display: flex; flex-direction: column; gap: 7px;
                padding-top: 11px; border-top: 1px solid var(--border); }
        .exhead { display: flex; align-items: center; gap: 10px; }
        .exhead .lbl { font-size: 11.5px; color: var(--text-3); }
        .exhead .btn { margin-left: auto; height: 26px; }
        .exc { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
        .exc select, .exc input {
          height: 28px; padding: 0 8px; font: inherit; font-size: 12px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .exc input { width: 100px; font-family: var(--mono); }
        .exc .btn { height: 26px; width: 26px; padding: 0; justify-content: center; }
        .exc .hint { flex-basis: 100%; }
        .serves, .zones { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
        .serves .lbl, .zones .lbl { font-size: 11.5px; color: var(--text-3); min-width: 40px; }
        .svc, .zone {
          height: 26px; padding: 0 11px; font: inherit; font-size: 11.5px;
          font-family: var(--mono); cursor: pointer;
          color: var(--text-3); background: var(--surface);
          border: 1px solid var(--border); border-radius: 999px;
        }
        .svc.on, .zone.on { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
        .svc.job.on { color: var(--ok, #1a7f37); border-color: currentColor; background: transparent; }
        .reg { display: inline-flex; align-items: center; gap: 4px; }
        .reg .rn { font-size: 10.5px; color: var(--text-3); font-family: var(--mono); }
        .reg .zone { padding: 0 8px; }
      `}</style>
    </div>
  );
}

/* ------------------------------------------------------------------ the wire
 *
 * Left at zero — which is what a scenario gets by saying nothing — every call
 * returns the instant it is made. That is not a neutral default: it is a fleet
 * in which no deadline can ever fire, no placement can ever be wrong, and no
 * message can ever go missing, which between them are most of what makes a
 * system distributed rather than one program in several pieces.
 *
 * So the numbers are here, next to the placement they give a cost to, and the
 * panel says out loud when they and the fleet disagree.
 */
function Network({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  const n = draft.net;
  const links = distances(draft, palette.regions);
  const apart = links['same region'] + links['same continent'] + links['across an ocean'];
  const quiet = !n.sameZoneRefMs && !n.crossZoneRefMs && !n.jitterRefMs && !n.loss;

  return (
    <Panel title="Network" note={quiet ? 'instant and lossless' : undefined}>
      <p className="lead">
        What a gRPC call costs before your code has done anything with it. These are the four
        numbers <code>network:</code> is written in, and they apply to every call in the run.
      </p>

      <div className="jobs">
        <div className="field">
          <label htmlFor="samezone">Same zone</label>
          <input id="samezone" type="number" min={0} step="any" value={n.sameZoneRefMs}
                 onChange={(e) => edit((d) => {
                   d.net.sameZoneRefMs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span className="hint">refMs for a call between two machines in one zone.</span>
        </div>
        <div className="field">
          <label htmlFor="crosszone">Across zones</label>
          <input id="crosszone" type="number" min={0} step="any" value={n.crossZoneRefMs}
                 onChange={(e) => edit((d) => {
                   d.net.crossZoneRefMs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span className="hint">
            refMs when they are not. The only thing that makes where you put a machine matter.
          </span>
        </div>
        <div className="field">
          <label htmlFor="jitter">Jitter</label>
          <input id="jitter" type="number" min={0} step="any" value={n.jitterRefMs}
                 onChange={(e) => edit((d) => {
                   d.net.jitterRefMs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span className="hint">
            Spread around both, so no two calls take exactly as long and a timeout is a judgement.
          </span>
        </div>
        <div className="field">
          <label htmlFor="loss">Loss</label>
          <input id="loss" type="number" min={0} max={1} step="any" value={n.loss}
                 onChange={(e) => edit((d) => {
                   d.net.loss = Math.min(1, Math.max(0, Number(e.target.value) || 0));
                 })} />
          <span className="hint">
            0 to 1 — the chance a call never arrives. 0.01 is one in a hundred.
          </span>
        </div>
      </div>

      {/* The two ways the numbers and the fleet can disagree. Both are legal and
          both are almost always a mistake, so they are said rather than fixed. */}
      {apart > 0 && n.crossZoneRefMs <= n.sameZoneRefMs && (
        <div className="flag warn">
          <span>⚠</span>
          <span>
            <strong>
              {apart} pair{apart === 1 ? '' : 's'} of machines are in different zones, and
              reaching across costs no more than staying put
            </strong>{' '}
            — so nothing in this scenario can be placed wrong, and moving a machine cannot be
            shown to help. Put a bigger number in <em>Across zones</em> to make placement a
            decision.
          </span>
        </div>
      )}
      {apart === 0 && n.crossZoneRefMs > 0 && (
        <div className="flag">
          <span>·</span>
          <span>
            Every machine here is in one zone, so <em>Across zones</em> never applies. Deal a
            pool over more zones above and it starts to.
          </span>
        </div>
      )}
      {n.loss > 0 && (
        <div className="flag">
          <span>·</span>
          <span>
            A call that is dropped looks exactly like one to a machine that has died — the caller
            cannot tell the difference, and finding out that it cannot is the exercise.
          </span>
        </div>
      )}

      <style>{`
        .lead { font-size: 13px; margin: 0 0 14px; }
        .jobs { display: flex; gap: 16px; flex-wrap: wrap; }
        .field { display: flex; flex-direction: column; gap: 4px; min-width: 150px; flex: 1; }
        .field label { font-size: 11.5px; color: var(--text-3); }
        .field input {
          height: 32px; padding: 0 10px; font: inherit; font-size: 13px;
          font-family: var(--mono);
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .hint { font-size: 11px; color: var(--text-3); }
        .flag {
          display: flex; gap: 10px; align-items: flex-start; margin-top: 14px;
          padding: 11px 14px; border-radius: var(--r-sm); font-size: 12.5px;
          background: var(--accent-soft); color: var(--text-2);
        }
        .flag.warn { background: #fff8e8; color: #6b4d09; }
        @media (prefers-color-scheme: dark) { .flag.warn { background: #2a2211; color: #e6c684; } }
      `}</style>
    </Panel>
  );
}

/* --------------------------------------------------------------- the weather
 *
 * A design tested on a day nothing went wrong is a design nobody has tested.
 *
 * It sits on the machine it happens to, not in a panel of its own. A fault is a
 * fact about a machine in the same way its instance type is, and a form that put
 * them on opposite ends of the page made you hold a name in your head to connect
 * them — which is exactly the mistake a scenario aimed at a machine that is not
 * there is made of.
 *
 * Two kinds, and the difference between them is the whole point: a fault at 300
 * refMs teaches a fleet to survive 300 refMs, and a *rate* teaches it to survive
 * whenever — which is the harder and more honest thing, and the reason a sweep
 * is twenty seeds rather than one lucky afternoon.
 */
function Weather({
  p, i, draft, machines, names, mine, edit,
}: {
  p: Pool;
  i: number;
  draft: Draft;
  palette: Palette;
  machines: { name: string; pool: string }[];
  names: string[];
  mine: Set<string>;
  edit: (f: (d: Draft) => void) => void;
}) {
  // Filed under the machine it happens to. A pair fault is filed under the end
  // it is written from, because that is the one the file names first.
  const faults = draft.faults
    .map((f, k) => ({ f, k }))
    .filter(({ f }) => mine.has(f.target));
  const rates = draft.chaos
    .map((c, k) => ({ c, k }))
    .filter(({ c }) => c.among === p.name || mine.has(c.among));
  const here = names[0] ?? '';

  return (
    <div className="wx">
      <div className="wxhead">
        <span className="lbl">Weather</span>
        <button
          className="btn"
          disabled={!here}
          onClick={() => edit((d) => {
            d.faults.push({
              kind: 'kill', atRefMs: 300,
              target: here,
              // A pair fault needs two, and two that differ: `partition: [a, a]`
              // is a machine cut off from itself. Prefilled so switching kind
              // never lands on one.
              other: machines.find((m) => m.name !== here)?.name ?? here,
              forRefMs: 500, factor: 3, noticeRefMs: 200, restartAfterRefMs: 2000,
            });
          })}
        >
          + At a moment
        </button>
        <button
          className="btn"
          onClick={() => edit((d) => {
            d.chaos.push({ kind: 'freeze', everyRefMs: 700, among: p.name, forRefMs: 150, factor: 2 });
          })}
        >
          + At a rate
        </button>
      </div>

      {!faults.length && !rates.length && (
        <span className="hint">
          Nothing happens to {count(names)}. Every run of this will be the good afternoon.
        </span>
      )}

      {faults.map(({ f, k }) => (
        <div className="rule" key={`f${k}`}>
          <span>at</span>
          <input type="number" value={f.atRefMs}
                 onChange={(e) => edit((d) => { d.faults[k].atRefMs = Number(e.target.value) || 0; })} />
          <span>refMs,</span>
          {/* The kind decides which control follows it, because each kind obeys
              a different one — and the values behind the others are kept, so
              changing your mind twice does not lose what you typed. */}
          <select value={f.kind}
                  onChange={(e) => edit((d) => { d.faults[k].kind = e.target.value as Fault['kind']; })}>
            {FAULT_KINDS.map((kind) => (
              <option key={kind} value={kind}>{kind}</option>
            ))}
          </select>
          <select value={f.target}
                  onChange={(e) => edit((d) => { d.faults[k].target = e.target.value; })}>
            {names.map((nm) => <option key={nm} value={nm}>{nm}</option>)}
          </select>
          {/* The second machine, and only these two have one. It is a pair of
              machines that stops reaching each other, not a machine that
              stops — so the other end may be anywhere in the fleet. */}
          {PAIRED.includes(f.kind) && (
            <>
              <span>{f.kind === 'heal' ? 'and' : 'from'}</span>
              <select value={f.other}
                      onChange={(e) => edit((d) => { d.faults[k].other = e.target.value; })}>
                {machines.map((m) => (
                  <option key={m.name} value={m.name}>{m.name}</option>
                ))}
              </select>
            </>
          )}
          {f.kind === 'kill' && (
            <>
              <span>and bring it back after</span>
              <input type="number" value={f.restartAfterRefMs}
                     onChange={(e) => edit((d) => {
                       d.faults[k].restartAfterRefMs = Math.max(0, Number(e.target.value) || 0);
                     })} />
              <span>refMs</span>
            </>
          )}
          {f.kind === 'freeze' && (
            <>
              <span>for</span>
              <input type="number" value={f.forRefMs}
                     onChange={(e) => edit((d) => {
                       d.faults[k].forRefMs = Math.max(0, Number(e.target.value) || 0);
                     })} />
              <span>refMs</span>
            </>
          )}
          {f.kind === 'degrade' && (
            <>
              <span>×</span>
              <input type="number" value={f.factor}
                     onChange={(e) => edit((d) => {
                       d.faults[k].factor = Math.max(1, Number(e.target.value) || 1);
                     })} />
              <span>slower</span>
            </>
          )}
          {f.kind === 'spot_reclaim' && (
            <>
              <span>after warning it for</span>
              <input type="number" value={f.noticeRefMs}
                     onChange={(e) => edit((d) => {
                       d.faults[k].noticeRefMs = Math.max(0, Number(e.target.value) || 0);
                     })} />
              <span>refMs</span>
            </>
          )}
          <button className="btn" onClick={() => edit((d) => { d.faults.splice(k, 1); })}>×</button>
          {f.kind === 'kill' && f.restartAfterRefMs === 0 && (
            <span className="aside">0 — it never comes back, which is a different exercise</span>
          )}
          {f.kind === 'freeze' && (
            <span className="aside">
              it stops answering and then thaws — the calls that were waiting find out late,
              which is the whole difference from a kill
            </span>
          )}
          {f.kind === 'degrade' && (
            <span className="aside">
              a one-time degrade has no end: it stays this slow for the rest of the run
            </span>
          )}
          {f.kind === 'restart' && (
            <span className="aside">
              it goes and comes straight back, with a fresh instance of every service it
              serves — which is where a design that kept state in a field finds out
            </span>
          )}
          {f.kind === 'spot_reclaim' && (
            <span className="aside">
              the warning is the whole lesson: it is announced, then taken away that long
              after. A design that ignores the notice deserves what happens.
            </span>
          )}
          {f.kind === 'partition' && (
            <span className="aside">
              {f.target === f.other
                ? 'both ends are the same machine — a machine cannot be cut off from itself'
                : 'both stay alive and keep serving everybody else; these two stop reaching '
                  + 'each other. Nothing heals it — write a heal: at a later instant.'}
            </span>
          )}
          {f.kind === 'heal' && (
            <span className="aside">
              the other half of a partition. On its own it does nothing, because there is
              nothing to mend.
            </span>
          )}
        </div>
      ))}

      {rates.map(({ c, k }) => (
        <div className="rule" key={`c${k}`}>
          <select value={c.kind}
                  onChange={(e) => edit((d) => { d.chaos[k].kind = e.target.value as Chaos['kind']; })}>
            <option value="freeze">freeze</option>
            <option value="kill">kill</option>
            <option value="degrade">degrade</option>
          </select>
          <span>one of</span>
          <select value={c.among}
                  onChange={(e) => edit((d) => { d.chaos[k].among = e.target.value; })}>
            <option value={p.name}>{p.name} — any of them</option>
            {names.map((nm) => <option key={nm} value={nm}>{nm}</option>)}
          </select>
          <span>every</span>
          <input type="number" value={c.everyRefMs}
                 onChange={(e) => edit((d) => {
                   d.chaos[k].everyRefMs = Math.max(1, Number(e.target.value) || 1);
                 })} />
          <span>refMs</span>
          {c.kind !== 'kill' && (
            <>
              <span>for</span>
              <input type="number" value={c.forRefMs}
                     onChange={(e) => edit((d) => {
                       d.chaos[k].forRefMs = Math.max(0, Number(e.target.value) || 0);
                     })} />
              <span>refMs</span>
            </>
          )}
          {c.kind === 'degrade' && (
            <>
              <span>×</span>
              <input type="number" value={c.factor}
                     onChange={(e) => edit((d) => {
                       d.chaos[k].factor = Math.max(1, Number(e.target.value) || 1);
                     })} />
              <span>slower</span>
            </>
          )}
          <button className="btn" onClick={() => edit((d) => { d.chaos.splice(k, 1); })}>×</button>
          <span className="aside">
            a rate, not a moment: it keeps happening for as long as the run does, and a sweep
            of seeds shows the spread rather than one lucky afternoon
          </span>
        </div>
      ))}

      <style>{`
        .wx { display: flex; flex-direction: column; gap: 6px;
              padding-top: 11px; border-top: 1px solid var(--border); }
        .wxhead { display: flex; align-items: center; gap: 8px; }
        .wxhead .lbl { font-size: 11.5px; color: var(--text-3); }
        .wxhead .btn { height: 26px; }
        .wxhead .btn:first-of-type { margin-left: auto; }
        .rule {
          display: flex; align-items: center; gap: 7px; flex-wrap: wrap;
          font-size: 12.5px; color: var(--text-2); padding: 6px 0;
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
      `}</style>
    </div>
  );
}

/** "w0" for one machine, "any of w0…w3" for a pool. */
function count(names: string[]): string {
  return names.length === 1
    ? names[0]
    : `any of ${names[0]}…${names[names.length - 1]}`;
}

/* --------------------------------------------------------------- the retries
 *
 * Not weather: a property of every caller in the fleet, keyed by the gRPC method
 * rather than by a machine. So it stays a panel of its own, and says which of
 * the two it is.
 */
function Retries({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  const rpcs = palette.services.flatMap((s) =>
    s.methods.map((m) => ({ method: `${s.qualified}.${m.name}`, idempotent: m.idempotent })));
  const seen = new Set<string>();
  const methods = rpcs.filter((r) => (seen.has(r.method) ? false : seen.add(r.method)));

  return (
    <Panel
      title="Retries"
      note="every caller, by method"
      actions={
        <button
          className="btn"
          disabled={!methods.length}
          onClick={() => edit((d) => {
            const safe = methods.find((m) => m.idempotent) ?? methods[0];
            d.retries.push({
              method: safe.method, attempts: 3, backoffRefMs: 40, multiplier: 1,
              unsafe: !safe.idempotent,
            });
          })}
        >
          + Retry
        </button>
      }
    >
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
            <span>refMs, ×</span>
            <input type="number" min={1} step="any" value={r.multiplier}
                   onChange={(e) => edit((d) => {
                     d.retries[i].multiplier = Math.max(1, Number(e.target.value) || 1);
                   })} />
            <span>each time</span>
            <button className="btn" onClick={() => edit((d) => { d.retries.splice(i, 1); })}>×</button>
            {r.multiplier === 1 && r.attempts > 2 && (
              <span className="aside">
                flat: every attempt waits the same. A fleet that does not ease off a
                struggling machine is how one slow machine becomes an outage.
              </span>
            )}
            {!safe && (
              <span className="aside warn">
                its <code>.proto</code> declares no <code>idempotency_level</code>, so this is
                written <code>unsafe: true</code> — running it twice is not known to be safe
              </span>
            )}
          </div>
        );
      })}

      <style>{`
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
