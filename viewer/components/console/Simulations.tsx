'use client';

/**
 * Authoring a simulation: the nodes, and what runs on them.
 *
 * The Java says what *can* run. This says **where it runs and what goes wrong**,
 * which is the half of a distributed system this course is actually about and
 * the half that has only ever been reachable by copying somebody else's YAML and
 * editing it until it stopped complaining.
 *
 * Three things are read off the lab rather than written down here, so you do not
 * have to already know them: the **files** and what each of them serves (from
 * the compiled bytecode, so a service that does not exist cannot be placed), the
 * **instance types** and the **regions** (losim's own catalogues, so a zone
 * cannot be misspelled into being its own region).
 *
 * The form is all on one page with the file beside it. Not a wizard: a wizard
 * hides the shape of what is being built, and the shape — four nodes, three of
 * them in one zone and one across an ocean — is the thing worth seeing. And the
 * YAML is shown in full the whole time, because the file *is* the simulation,
 * and what a student has to be able to read by the end of this course is the one
 * their classmate sent them.
 *
 * **What goes wrong is drawn where it happens.** There is no weather section:
 * a node's failures are on the node's own card and an rpc's are inside the entry
 * that placed it, because that is where the file writes them and because a
 * failure written beside the cluster had to name its target by string — which
 * was the one thing this form could get wrong and produce a file that would not
 * load.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';

import { Head, Panel } from './Shell.tsx';
import { useConsole } from '../../lib/console.tsx';
import { Lab } from '../Lab.tsx';
import {
  distances, entryOf, firstDraft, nodes as nodesIn, perHour, toYaml, unplaced,
  BASE_UNITS, FAILURE_KINDS, PAIRED, RATEABLE, RPC_FAILURE_KINDS,
  type CostRule, type Draft, type Failure, type Pool, type RpcFailure, type Runs,
} from '../../lib/author.ts';
import {
  openSimulation, palette as fetchPalette, saveSimulation, type Palette,
} from '../../lib/lab.ts';

export function Simulations() {
  const { nudge, startBuild, go } = useConsole();
  /**
   * Listing what exists, writing a new one, or editing one that already is.
   *
   * The list is the page, because a lab accumulates simulations and the thing
   * you do most often is run one of them again. Writing and editing are the
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

  const nodes = useMemo(() => (draft ? nodesIn(draft) : []), [draft]);
  const yaml = useMemo(() => (draft ? toYaml(draft) : ''), [draft]);
  const links = useMemo(
    () => (draft && palette ? distances(draft, palette.regions) : null),
    [draft, palette],
  );

  /**
   * Whether there is anything to place.
   *
   * Compiling is a different question. A lab of plain Java compiles perfectly
   * and offers nothing a node can be given, and a draft over that is a cluster
   * of nodes that run nothing.
   */
  const canAuthor = !!palette && palette.compiled && palette.services.length > 0;

  /** Where the simulation starts, or nothing — which is a file that will not run. */
  const entry = useMemo(() => (draft ? entryOf(draft) : null), [draft]);

  /** Write it, then simulate it. A file nobody ran is a file, not a result. */
  const create = useCallback(async () => {
    if (!draft || !palette) return;
    setSaying('writing…');
    setRefused(null);
    const wrote = await saveSimulation(draft.name, yaml);
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
    await startBuild(wrote.simulation!);
    setSaying(null);
    setMode('list');
    go('runs');
  }, [draft, palette, yaml, startBuild, go]);

  /**
   * Load an existing simulation back into this same form.
   *
   * The server does the reading — the loader checks it, then a second walk of
   * the same parse tree fills in exactly what this form has a control for.
   * Anything it does not comes back as a refusal naming the key, not a Draft
   * missing something silently: there is nowhere in this form to notice that.
   */
  const openForEdit = useCallback(async (name: string) => {
    setRefused(null);
    const said = await openSimulation(name);
    if (said.error || !said.draft) {
      setRefused(said.error ?? 'could not read that simulation');
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
   * it there is how a variant of an existing simulation gets written, and the
   * original is left exactly as it was.
   */
  const saveEdit = useCallback(async () => {
    if (!draft || !editing) return;
    setSaying('saving…');
    setRefused(null);
    const wrote = await saveSimulation(draft.name, yaml);
    if (wrote.error) {
      setRefused(wrote.error);
      setSaying(null);
      return;
    }
    setSaying(null);
    setEditing(null);
    setMode('list');
    // The list shows names and paths, not content, so nothing there is stale —
    // but a simulation just replaced, or one just written beside it, is worth the
    // same nudge a new one gets.
    nudge();
  }, [draft, editing, yaml, nudge]);

  return (
    <>
      <Head
        title="Simulations"
        sub={
          mode === 'list' ? (
            <>
              Your Java says what <em>can</em> run. A simulation says where it runs and what
              goes wrong — the nodes, the distances, and what fails on which of them. Write as
              many as you like: what changes between two results is almost never the code.
            </>
          ) : mode === 'new' ? (
            <>
              What this lab compiles to is read off the classes, so nothing here can name a
              file that is not there or a service it does not implement. Nothing is written
              until you press create.
            </>
          ) : (
            <>
              The same form, filled in from what is already written. Nothing is written back
              until you press save — and a simulation the form could not write back exactly as
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
                  ? 'write a new simulation'
                  : 'there is nothing to place yet — nothing here implements a gRPC service'
              }
              onClick={() => {
                setRefused(null);
                if (palette) setDraft(firstDraft(palette));
                setMode('new');
              }}
            >
              + New simulation
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

      {/* Compiling is not the same question as having something to place. A lab
          of plain Java compiles perfectly and offers nothing a node can be given,
          and the skeleton draft would then propose a cluster of nodes that run
          nothing — a simulation that cannot exist, offered as a default. The
          palette is the predicate: it is empty for exactly the labs where there
          is nothing to author. */}
      {mode === 'list' && !busy && palette && palette.compiled && !canAuthor && (
        <Panel title="Nothing to place yet">
          <p className="muted">
            This lab compiles — {palette.other} class{palette.other === 1 ? '' : 'es'} — but none
            of them is a gRPC service a node can be given. A simulation says <em>where the code
            runs</em>, so there has to be code that runs somewhere first.
          </p>
        </Panel>
      )}

      {/* New needs the palette to have anything to place; Edit needs only the
          draft it already opened with — the server refused before this ever
          rendered if that draft could not fully represent the file. */}
      {((mode === 'new' && canAuthor) || mode === 'edit') && !busy && palette && draft && (
        <div className="two">
          <div className="col">
            <Nodes draft={draft} palette={palette} nodes={nodes} edit={edit} />
            <Scale draft={draft} edit={edit} />
            <Network draft={draft} palette={palette} edit={edit} />
            <TheInput draft={draft} edit={edit} />
            <Costs draft={draft} palette={palette} edit={edit} />
            <Retries draft={draft} palette={palette} edit={edit} />
          </div>

          <div className="col sticky">
            <Panel title="Knowable now">
              <dl className="kv">
                <div>
                  <dt>nodes</dt>
                  <dd>{nodes.length}</dd>
                </div>
                <div>
                  <dt>zones</dt>
                  <dd>{new Set(nodes.map((m) => m.zone)).size}</dd>
                </div>
                <div>
                  <dt>on the catalogue’s prices</dt>
                  <dd>{perHour(draft, palette).toFixed(4)} / hour</dd>
                </div>
              </dl>
              {links && (
                <>
                  <h3 className="sub">Distances in this cluster</h3>
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
              {/* Editable in both modes. Opening one simulation and saving it under
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
                  Written to <code>simulations/{draft.name}.yaml</code>
                  {editing && draft.name !== editing.name.replace(/\.ya?ml$/, '') ? (
                    <> — a new file. <code>{editing.name}</code> is left as it was.</>
                  ) : palette.simulations.includes(`${draft.name}.yaml`) ? (
                    <> — <strong>which already exists and will be replaced</strong></>
                  ) : null}
                </span>
              </div>
              <button
                className="btn primary wide"
                onClick={() => void (editing ? saveEdit() : create())}
                disabled={!!saying || !entry || !draft.name.trim()}
              >
                {saying ?? (editing ? 'Save' : 'Create and simulate')}
              </button>
              {!entry && (
                <p className="note">
                  No node runs <code>losim.Job</code>, so there is nothing to start. It is a
                  gRPC service like any other: a class extending
                  {' '}<code>losim.pb.JobGrpc.JobImplBase</code>, placed on a node like
                  everything else. Exactly one node has one.
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
      <Panel title="Every simulation here" note="press ▶ to build and simulate">
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
        ? `a model of ${(draft.scale * BASE_UNITS).toLocaleString()} units`
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
              })}
            />
            <span className="x">×</span>
          </div>
          <span className="hint">
            {modelled
              ? `${draft.scale}× the biggest run the engine can measure — ${(draft.scale * BASE_UNITS).toLocaleString()} units.`
              : 'One run of itself. Nothing is projected, so nothing can be projected wrongly.'}
          </span>
        </div>
        <div className="quick">
          {[1, 10, 100, 1000].map((n) => (
            <button
              key={n}
              className={`chip${draft.scale === n ? ' on' : ''}`}
              onClick={() => edit((d) => { d.scale = n; })}
            >
              {n === 1 ? 'just run it' : `${n}×`}
            </button>
          ))}
        </div>
      </div>

      {/* No second control saying what to do at that size. Above 1 a simulation
          is a model of something bigger and there is nothing else it could be:
          the engine picks the ladder, the cluster sizes and the clock, and any
          resource whose law does not hold is refused rather than extrapolated.
          A `mode:` was a second way to say what this number already says, and
          two controls can disagree. */}

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

/* ---------------------------------------------------------------- the nodes
 *
 * The part a student actually authors. Each block is a pool — nodes that grow
 * and shrink together — and a single node is a pool of one, which is why the
 * count starts at 1 and there is no separate kind of thing for it.
 */
function Nodes({
  draft, palette, nodes, edit,
}: {
  draft: Draft;
  palette: Palette;
  nodes: { name: string; pool: string }[];
  edit: (f: (d: Draft) => void) => void;
}) {
  const ms = nodesIn(draft);
  const orphans = unplaced(draft, palette);
  return (
    <Panel
      title="Nodes"
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
                failures: [],
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
        Each block is a <strong>pool</strong>: nodes that grow and shrink together, dealt
        round-robin over the zones you give it. What it runs, where it sits and what happens to
        it are all on the block, because they are all facts about the same nodes.
      </p>

      <div className="pools">
        {draft.pools.map((p, i) => (
          <PoolCard
            key={i}
            p={p}
            i={i}
            palette={palette}
            nodes={nodes}
            only={draft.pools.length === 1}
            edit={edit}
          />
        ))}
      </div>

      {orphans.length > 0 && (
        <div className="flag warn">
          <span>⚠</span>
          <span>
            <strong>
              {orphans.length} service{orphans.length === 1 ? '' : 's'} on no node
            </strong>{' '}
            — {orphans.map((o) => o.file).join(', ')}. The simulation will still run; nothing
            will ever call them.
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
  p, i, palette, nodes, only, edit,
}: {
  p: Pool;
  i: number;
  palette: Palette;
  nodes: { name: string; pool: string }[];
  only: boolean;
  edit: (f: (d: Draft) => void) => void;
}) {
  const inst = palette.instances.find((x) => x.name === p.instance);
  // The names the loader will give this pool's nodes, which is what an
  // exception has to be keyed by and what the far end of a partition points at.
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
            // leave its nodes called `workers0`. One deliberately set apart
            // stays put.
            if (d.pools[i].prefix === d.pools[i].name) d.pools[i].prefix = e.target.value;
            d.pools[i].name = e.target.value;
          })}
          aria-label="pool name"
        />
        <span className="chip">
          {p.count === 1 ? '1 node' : `${p.count} nodes`}
        </span>
        {p.runs.some((r) => r.service === 'losim.Job') && (
          <span className="chip entry">the simulation starts here</span>
        )}
        <span className="acts">
          <button
            className="btn"
            disabled={only}
            title={only ? 'a simulation needs at least one node' : 'remove this pool'}
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
              aria-label="node name prefix"
            />
            <span className="hint">
              {p.prefix ? `${p.prefix}0, ${p.prefix}1, …` : 'nodes are named prefix0, prefix1'}
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

      {/* What this pool runs. One control, because there is one kind of thing to
          place: `losim.Job` is a service like any other and is in this list with
          the rest of them. Read off the compiled classes, so nothing here can
          name a file that is not there or a service it does not implement. */}
      <div className="serves">
        <span className="lbl">Runs</span>
        {palette.services.map((sv) => {
          const on = p.runs.some((r) => r.file === sv.file);
          const unsafe = sv.rpcs.filter((m) => !m.idempotent).map((m) => m.name);
          return (
            <button
              key={sv.file}
              className={`svc${sv.entry ? ' entry' : ''}${on ? ' on' : ''}`}
              title={`${sv.file} serves ${sv.service} — ${sv.rpcs.map((m) => m.name).join(', ')}`
                     + (unsafe.length ? `\nnot declared idempotent: ${unsafe.join(', ')}` : '')}
              onClick={() => edit((d) => {
                const runs = d.pools[i].runs;
                const at = runs.findIndex((r) => r.file === sv.file);
                if (at >= 0) { runs.splice(at, 1); return; }
                // A simulation starts in one place, and the loader refuses two.
                // Placing the entry here takes it off wherever it was, which is
                // what somebody pressing this means — the alternative is a file
                // that is refused with a line number for obeying the form.
                if (sv.entry) {
                  for (const q of d.pools) {
                    const was = q.runs.findIndex((r) => r.service === 'losim.Job');
                    if (was >= 0) q.runs.splice(was, 1);
                  }
                }
                runs.push({ service: sv.service, file: sv.file, failures: {} });
              })}
            >
              {sv.entry ? '▶ ' : ''}{sv.file.replace(/^.*\//, '')}
            </button>
          );
        })}
        {!palette.services.length && (
          <span className="hint">
            Nothing here extends a generated <code>ImplBase</code>, so there is nothing a
            node can be given.
          </span>
        )}
      </div>

      {/* And what is wrong with it *here*. Inside the entry that placed it,
          because that is where the file writes it and because it is the whole
          point: the same service can be the bad replica on one node and fine on
          its peers, and a design that routes around it is a different design
          from one that does not. */}
      {p.runs.map((r, k) => (
        <RpcFailures
          key={r.file}
          r={r}
          rpcs={palette.services.find((sv) => sv.file === r.file)?.rpcs.map((m) => m.name) ?? []}
          set={(f) => edit((d) => f(d.pools[i].runs[k]))}
        />
      ))}

      {/* Caps, and the third state. An empty box is not zero: it is whatever the
          instance type says, which is what a pool that never mentions these
          gets. A `0` is a node that cannot hold anything — legal, and a
          different simulation — so the two must not be typed by the same gesture. */}
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
                + 'a node that holds too much fills up and says so.'}
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

      {/* One node unlike the rest. A pool of eight where one is half the size
          is the cheapest straggler there is, and it cannot be said at pool level
          — that is the whole point of it. Only offered where there is more than
          one node to be the exception to. */}
      {names.length > 1 && (
        <div className="excs">
          <div className="exhead">
            <span className="lbl">Exceptions</span>
            <button
              className="btn"
              onClick={() => edit((d) => {
                const taken = new Set(d.pools[i].overrides.map((o) => o.node));
                const free = names.find((nm) => !taken.has(nm)) ?? names[0];
                d.pools[i].overrides.push({
                  node: free, instance: '', zone: '', memoryMb: null, diskMb: null,
                  failures: [],
                });
              })}
              disabled={p.overrides.length >= names.length}
            >
              + Exception
            </button>
          </div>
          {!p.overrides.length && (
            <span className="hint">
              Every node in this pool is the same. Add one to make a straggler, or a
              node too small for the work it is given.
            </span>
          )}
          {p.overrides.map((o, k) => (
            <div className="exc" key={k}>
              <select
                value={o.node}
                aria-label="which node"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].node = e.target.value; })}
              >
                {names.map((nm) => <option key={nm} value={nm}>{nm}</option>)}
                {/* An override the loader would silently ignore: it names no
                    node in this pool. Kept rather than dropped, and shown as
                    what it is. */}
                {!names.includes(o.node) && <option value={o.node}>{o.node} — no such node</option>}
              </select>
              <select
                value={o.instance}
                aria-label="instance for this node"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].instance = e.target.value; })}
              >
                <option value="">same instance</option>
                {palette.instances.map((x) => (
                  <option key={x.name} value={x.name}>{x.name}</option>
                ))}
              </select>
              <select
                value={o.zone}
                aria-label="zone for this node"
                onChange={(e) => edit((d) => { d.pools[i].overrides[k].zone = e.target.value; })}
              >
                <option value="">same zone</option>
                {palette.regions.flatMap((r) => r.zones).map((z) => (
                  <option key={z} value={z}>{z}</option>
                ))}
              </select>
              <input
                type="number" min={0} step="any" placeholder="memory MB"
                aria-label="memory cap for this node"
                value={o.memoryMb ?? ''}
                onChange={(e) => edit((d) => {
                  const v = e.target.value.trim();
                  d.pools[i].overrides[k].memoryMb = v === '' ? null : Math.max(0, Number(v) || 0);
                })}
              />
              <input
                type="number" min={0} step="any" placeholder="disk MB"
                aria-label="disk cap for this node"
                value={o.diskMb ?? ''}
                onChange={(e) => edit((d) => {
                  const v = e.target.value.trim();
                  d.pools[i].overrides[k].diskMb = v === '' ? null : Math.max(0, Number(v) || 0);
                })}
              />
              <button className="btn" onClick={() => edit((d) => { d.pools[i].overrides.splice(k, 1); })}>×</button>
              {!names.includes(o.node) && (
                <span className="hint warn">
                  This pool has no node called <code>{o.node}</code>, so the run ignores
                  this line. Point it at one, or remove it.
                </span>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Every zone there is, not just the ones near the first. A pool dealt over
          two continents is a legal simulation and an instructive one, and the form
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

      {/* What happens to these nodes. Each of them, separately: a rate written
          on a pool of six is six nodes each failing on their own draw, which
          follows from the block being the node and is the reading that survives
          the pool being resized. */}
      <Failures
        title="What happens to it"
        fs={p.failures}
        here={saying(names)}
        others={nodes.map((m) => m.name).filter((nm) => !mine.has(nm))}
        set={(f) => edit((d) => f(d.pools[i].failures))}
      />

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
 * Left at zero — which is what a simulation gets by saying nothing — every call
 * returns the instant it is made. That is not a neutral default: it is a cluster
 * in which no deadline can ever fire, no placement can ever be wrong, and no
 * message can ever go missing, which between them are most of what makes a
 * system distributed rather than one program in several pieces.
 *
 * So the numbers are here, next to the placement they give a cost to, and the
 * panel says out loud when they and the cluster disagree.
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
          <span className="hint">refMs for a call between two nodes in one zone.</span>
        </div>
        <div className="field">
          <label htmlFor="crosszone">Across zones</label>
          <input id="crosszone" type="number" min={0} step="any" value={n.crossZoneRefMs}
                 onChange={(e) => edit((d) => {
                   d.net.crossZoneRefMs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span className="hint">
            refMs when they are not. The only thing that makes where you put a node matter.
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

      {/* The two ways the numbers and the cluster can disagree. Both are legal and
          both are almost always a mistake, so they are said rather than fixed. */}
      {apart > 0 && n.crossZoneRefMs <= n.sameZoneRefMs && (
        <div className="flag warn">
          <span>⚠</span>
          <span>
            <strong>
              {apart} pair{apart === 1 ? '' : 's'} of nodes are in different zones, and
              reaching across costs no more than staying put
            </strong>{' '}
            — so nothing in this simulation can be placed wrong, and moving a node cannot be
            shown to help. Put a bigger number in <em>Across zones</em> to make placement a
            decision.
          </span>
        </div>
      )}
      {apart === 0 && n.crossZoneRefMs > 0 && (
        <div className="flag">
          <span>·</span>
          <span>
            Every node here is in one zone, so <em>Across zones</em> never applies. Deal a
            pool over more zones above and it starts to.
          </span>
        </div>
      )}
      {n.loss > 0 && (
        <div className="flag">
          <span>·</span>
          <span>
            A call that is dropped looks exactly like one to a node that has died — the caller
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

/* -------------------------------------------------------------- the failures
 *
 * What goes wrong, drawn where it happens. There is no section for it: a node's
 * failures are on the node's own card and an rpc's are inside the entry that
 * placed it, because that is where the file writes them.
 *
 * One class of mistake goes away with the move rather than being caught. A
 * failure used to name its target by string, so this form had to hold the
 * loader's naming rules — a pool of one keeps the pool's name, a prefixed pool
 * does not — and get them right or write a file that would not load. The block
 * is the node now. The only name left is the far end of a partition, because
 * reachability is a property of a pair.
 */

/** Which of the six extra fields a kind actually obeys — the rest are refused. */
function Failures({
  title, fs, here, others, set,
}: {
  title: string;
  fs: Failure[];
  /** What these nodes are called, for the sentence when there are none. */
  here: string;
  /** Every node that is not one of these, for the far end of a pair. */
  others: string[];
  set: (f: (fs: Failure[]) => void) => void;
}) {
  const add = (rate: boolean) => set((list) => {
    list.push({
      kind: rate ? 'freeze' : 'kill',
      atRefMs: rate ? 0 : 300,
      perRefMs: rate ? 700 : 0,
      // A pair needs a far end, and one that is not this node. Prefilled so
      // switching kind never lands on a node cut off from itself.
      other: others[0] ?? '',
      forRefMs: 500, factor: 3, noticeRefMs: 200, restartAfterRefMs: 2000,
    });
  });
  return (
    <div className="wx">
      <div className="wxhead">
        <span className="lbl">{title}</span>
        <button className="btn" onClick={() => add(false)}>+ At a moment</button>
        <button className="btn" onClick={() => add(true)}>+ At a rate</button>
      </div>

      {!fs.length && (
        <span className="hint">
          Nothing happens to {here}. Every run of this will be the good afternoon.
        </span>
      )}

      {fs.map((f, k) => {
        const rate = f.perRefMs > 0;
        const put = (g: (x: Failure) => void) => set((list) => g(list[k]));
        return (
          <div className="rule" key={k}>
            {/* An instant or a rate, and never both: one is a moment and the
                other a mean gap drawn exponentially, and an entry with both
                would be two failures written as one. */}
            <select
              value={rate ? 'per' : 'at'}
              aria-label="when"
              onChange={(e) => put((x) => {
                if (e.target.value === 'per') { x.perRefMs = x.perRefMs || 700; x.atRefMs = 0; }
                else { x.atRefMs = x.atRefMs || 300; x.perRefMs = 0; }
              })}
            >
              <option value="at">at</option>
              <option value="per">every</option>
            </select>
            <input
              type="number"
              value={rate ? f.perRefMs : f.atRefMs}
              onChange={(e) => put((x) => {
                const v = Math.max(0, Number(e.target.value) || 0);
                if (rate) x.perRefMs = v; else x.atRefMs = v;
              })}
            />
            <span>refMs,</span>
            {/* The kind decides which control follows it, because each kind obeys
                a different one — and the values behind the others are kept, so
                changing your mind twice does not lose what you typed. */}
            <select
              value={f.kind}
              aria-label="what happens"
              onChange={(e) => put((x) => {
                x.kind = e.target.value as Failure['kind'];
                // The four that happen once cannot stand as a rate: they either
                // bring the node back or take it away for good. Switching to one
                // while a rate is set would write a file the loader refuses.
                if (!RATEABLE.includes(x.kind) && x.perRefMs > 0) {
                  x.atRefMs = x.atRefMs || 300;
                  x.perRefMs = 0;
                }
              })}
            >
              {FAILURE_KINDS
                .filter((kind) => !rate || RATEABLE.includes(kind))
                .map((kind) => <option key={kind} value={kind}>{kind}</option>)}
            </select>
            {/* The far end, and only these two have one. It is a pair of nodes
                that stops reaching each other, not a node that stops — so the
                other end may be anywhere in the system. */}
            {PAIRED.includes(f.kind) && (
              <>
                <span>{f.kind === 'heal' ? 'and' : 'from'}</span>
                <select value={f.other} onChange={(e) => put((x) => { x.other = e.target.value; })}>
                  {others.map((nm) => <option key={nm} value={nm}>{nm}</option>)}
                  {!others.includes(f.other) && <option value={f.other}>{f.other}</option>}
                </select>
              </>
            )}
            {f.kind === 'kill' && (
              <>
                <span>and bring it back after</span>
                <input type="number" value={f.restartAfterRefMs}
                       onChange={(e) => put((x) => {
                         x.restartAfterRefMs = Math.max(0, Number(e.target.value) || 0);
                       })} />
                <span>refMs</span>
              </>
            )}
            {f.kind === 'freeze' && (
              <>
                <span>for</span>
                <input type="number" value={f.forRefMs}
                       onChange={(e) => put((x) => {
                         x.forRefMs = Math.max(0, Number(e.target.value) || 0);
                       })} />
                <span>refMs</span>
              </>
            )}
            {f.kind === 'degrade' && (
              <>
                <span>×</span>
                <input type="number" value={f.factor}
                       onChange={(e) => put((x) => {
                         x.factor = Math.max(1.01, Number(e.target.value) || 2);
                       })} />
                <span>slower</span>
              </>
            )}
            {f.kind === 'spotReclaim' && (
              <>
                <span>after warning it for</span>
                <input type="number" value={f.noticeRefMs}
                       onChange={(e) => put((x) => {
                         x.noticeRefMs = Math.max(0, Number(e.target.value) || 0);
                       })} />
                <span>refMs</span>
              </>
            )}
            <button className="btn" onClick={() => set((list) => { list.splice(k, 1); })}>×</button>
            {rate && (
              <span className="aside">
                a rate, not a moment: it keeps happening for as long as the simulation does,
                and it is drawn separately for each node here — so a sweep of seeds shows the
                spread rather than one lucky afternoon
              </span>
            )}
            {!rate && f.kind === 'kill' && f.restartAfterRefMs === 0 && (
              <span className="aside">0 — it never comes back, which is a different exercise</span>
            )}
            {f.kind === 'freeze' && (
              <span className="aside">
                it stops answering and then thaws — the calls that were waiting find out late,
                which is the whole difference from a kill
              </span>
            )}
            {PAIRED.includes(f.kind) && (
              <span className="aside">
                both stay alive, both keep serving everybody else, and one caller sees nothing
              </span>
            )}
          </div>
        );
      })}

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
        .hint { font-size: 11px; color: var(--text-3); }
      `}</style>
    </div>
  );
}

/**
 * What goes wrong with one service's rpcs, on this node and not its peers.
 *
 * The rpc names are a dropdown over what the `.proto` says the service serves,
 * so the form cannot write one it does not — which is refused at run time, by
 * the bound server, and would otherwise be a failure that quietly never fires.
 *
 * Rates only. A failure that begins at an instant and stays is a property of the
 * node, and `degrade` already says it.
 */
function RpcFailures({
  r, rpcs, set,
}: {
  r: Runs;
  rpcs: string[];
  set: (f: (r: Runs) => void) => void;
}) {
  const rows = Object.entries(r.failures).flatMap(([rpc, fs]) => fs.map((f, k) => ({ rpc, f, k })));
  const free = rpcs.find((n) => !(r.failures[n] ?? []).length) ?? rpcs[0];
  if (!rpcs.length) return null;
  return (
    <div className="rpcwx">
      <div className="wxhead">
        <span className="lbl">
          <code>{r.service}</code> here
        </span>
        <button
          className="btn"
          onClick={() => set((x) => {
            const list = x.failures[free] ?? (x.failures[free] = []);
            list.push({ kind: 'status', status: 'UNAVAILABLE', factor: 6, perCalls: 20 });
          })}
        >
          + One call in N goes wrong
        </button>
      </div>
      {rows.map(({ rpc, f, k }) => {
        const put = (g: (x: RpcFailure) => void) => set((x) => g(x.failures[rpc][k]));
        const move = (to: string) => set((x) => {
          const [was] = x.failures[rpc].splice(k, 1);
          if (!x.failures[rpc].length) delete x.failures[rpc];
          (x.failures[to] ?? (x.failures[to] = [])).push(was);
        });
        return (
          <div className="rule" key={`${rpc}${k}`}>
            <span>one call in</span>
            <input type="number" value={f.perCalls}
                   onChange={(e) => put((x) => {
                     x.perCalls = Math.max(1, Math.round(Number(e.target.value) || 1));
                   })} />
            <span>to</span>
            <select value={rpc} aria-label="which rpc" onChange={(e) => move(e.target.value)}>
              {rpcs.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
            <select value={f.kind} aria-label="what goes wrong"
                    onChange={(e) => put((x) => { x.kind = e.target.value as RpcFailure['kind']; })}>
              {RPC_FAILURE_KINDS.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
            {f.kind === 'status' && (
              <input value={f.status} aria-label="gRPC status code"
                     onChange={(e) => put((x) => { x.status = e.target.value.trim().toUpperCase(); })} />
            )}
            {f.kind === 'slow' && (
              <>
                <span>×</span>
                <input type="number" value={f.factor}
                       onChange={(e) => put((x) => {
                         x.factor = Math.max(1.01, Number(e.target.value) || 2);
                       })} />
                <span>its declared duration</span>
              </>
            )}
            <button className="btn" onClick={() => set((x) => {
              x.failures[rpc].splice(k, 1);
              if (!x.failures[rpc].length) delete x.failures[rpc];
            })}>×</button>
            {f.kind === 'drop' && (
              <span className="aside">
                the request never arrives, so the caller finds out by running out of time —
                which is what a lost packet actually looks like from the other end
              </span>
            )}
            {f.kind === 'status' && (
              <span className="aside">
                the handler is never reached; the caller gets that code and has to handle it
              </span>
            )}
          </div>
        );
      })}
      <style>{`
        .rpcwx { display: flex; flex-direction: column; gap: 6px;
                 padding: 9px 0 0 14px; border-left: 2px solid var(--border); }
        .rpcwx .wxhead { display: flex; align-items: center; gap: 8px; }
        .rpcwx .lbl { font-size: 11.5px; color: var(--text-3); }
        .rpcwx .btn { height: 26px; margin-left: auto; }
        .rpcwx .rule {
          display: flex; align-items: center; gap: 7px; flex-wrap: wrap;
          font-size: 12.5px; color: var(--text-2); padding: 5px 0;
        }
        .rpcwx input, .rpcwx select {
          height: 28px; padding: 0 8px; font: inherit; font-size: 12.5px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .rpcwx input[type=number] { width: 66px; font-family: var(--mono); }
        .rpcwx .btn:last-of-type { height: 26px; width: 26px; padding: 0; justify-content: center; }
        .rpcwx .aside { flex-basis: 100%; font-size: 11px; color: var(--text-3); }
      `}</style>
    </div>
  );
}

/** "w0" for one node, "any of w0…w3" for a pool. */
function saying(names: string[]): string {
  return names.length === 1
    ? names[0]
    : `any of ${names[0]}…${names[names.length - 1]}`;
}

/* ----------------------------------------------------------------- the input
 *
 * How big the workload is, at full size, and what one item of it is called.
 *
 * Three fields, because there is one number the engine varies. What was here
 * before was a block of rows named by a driver class's own declaration, so the
 * form had to ask the code what to draw before it could draw anything — and a
 * lab whose code did not compile could not size its input at all.
 */
function TheInput({
  draft, edit,
}: {
  draft: Draft;
  edit: (f: (d: Draft) => void) => void;
}) {
  const set = (f: (w: Draft['input']) => void) => edit((d) => f(d.input));
  return (
    <Panel
      title="The workload"
      note={draft.scale > 1
        ? `${draft.input.count.toLocaleString()} ${draft.input.unit}s, modelled`
        : `${draft.input.count.toLocaleString()} ${draft.input.unit}s`}
    >
      <div className="rule">
        <span>losim hands</span>
        <input type="number" min={1} step={1} value={draft.input.count}
               onChange={(e) => set((w) => {
                 w.count = Math.max(1, Math.round(Number(e.target.value) || 1));
               })} />
        <input className="unit" value={draft.input.unit} aria-label="what one item is called"
               onChange={(e) => set((w) => { w.unit = e.target.value; })} />
        <span>to <code>Job.Load</code>, before the clock starts</span>
      </div>
      <p className="aside">
        Singular — frame, line, order. It is the same word{' '}
        <code>Losim.current().units(n)</code> counts and <code>perUnit</code> prices, so a
        blank one leaves three numbers counting something nobody named.
      </p>
      <div className="rule">
        <span>read from</span>
        <input className="src" value={draft.input.source} placeholder="nothing — generated from the seed"
               aria-label="where the workload is read from"
               onChange={(e) => set((w) => { w.source = e.target.value.trim(); })} />
      </div>
      <p className="aside">
        {draft.input.source
          ? 'A file or a folder, from the project root. It has to be there: the loader stats it '
            + 'and refuses a simulation that would spend its setup reading something that is not.'
          : 'Left empty, Load generates the workload from Losim.current().seed() — reproducible '
            + 'from the seed, different across a sweep, and free, because Load is off the clock.'}
      </p>
      {draft.scale > 1 && (
        <p className="aside">
          A model of {draft.scale}× the run: the engine measures a fraction of these and
          projects from what it saw. <code>Run</code> is never told which fraction, which is
          the property the whole thing depends on.
        </p>
      )}
      <style>{`
        .rule {
          display: flex; align-items: center; gap: 7px; flex-wrap: wrap;
          font-size: 12.5px; color: var(--text-2); padding: 5px 0;
        }
        .rule input {
          height: 28px; padding: 0 8px; font: inherit; font-size: 12.5px;
          color: var(--text); background: var(--surface);
          border: 1px solid var(--border); border-radius: var(--r-sm);
        }
        .rule input[type=number] { width: 96px; font-family: var(--mono); }
        .rule .unit { width: 92px; font-family: var(--mono); }
        .rule .src { flex: 1; min-width: 220px; font-family: var(--mono); }
        .aside { font-size: 11px; color: var(--text-3); margin: 2px 0 10px; }
      `}</style>
    </Panel>
  );
}

/* ----------------------------------------------------------------- the costs
 *
 * What each rpc takes on the reference machine, under the **file** that serves
 * it. Rows for whatever the system actually places, so the panel is a table to
 * fill in rather than a block to remember — and a system left at zero says so,
 * because one where every call is instant has no queueing, no contention and no
 * critical path, and looks from the outside like a design that is simply very
 * fast.
 */
function Costs({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  // Only what is placed: a cost for a file no node runs is refused at load, so
  // the form must not be able to write one.
  const placed = [...new Set(draft.pools.flatMap((p) => p.runs.map((r) => r.file)))];
  const rows = placed.flatMap((file) => {
    const service = palette.services.find((s) => s.file === file);
    return (service?.rpcs ?? []).map((m) => {
      const has = draft.simulatedDuration.find((c) => c.runs === file && c.rpc === m.name);
      return {
        runs: file, rpc: m.name,
        fixedRefMs: has?.fixedRefMs ?? 0, perUnitRefNs: has?.perUnitRefNs ?? 0,
      };
    });
  });
  const set = (runs: string, rpc: string, f: (c: CostRule) => void) => edit((d) => {
    let row = d.simulatedDuration.find((c) => c.runs === runs && c.rpc === rpc);
    if (!row) { row = { runs, rpc, fixedRefMs: 0, perUnitRefNs: 0 }; d.simulatedDuration.push(row); }
    f(row);
  });
  const priced = rows.some((r) => r.fixedRefMs > 0 || r.perUnitRefNs > 0);

  return (
    <Panel title="What a call takes" note="reference milliseconds, by the file that serves it">
      {!rows.length && (
        <p className="none">
          Nothing is placed yet, so there is nothing to price. Give a pool something to run.
        </p>
      )}
      {rows.map((r) => (
        <div className="rule" key={`${r.runs}.${r.rpc}`}>
          <span><code>{r.runs.replace(/^.*\//, '')}</code>.{r.rpc}</span>
          <span>takes</span>
          <input type="number" min={0} step="any" value={r.fixedRefMs}
                 onChange={(e) => set(r.runs, r.rpc, (c) => {
                   c.fixedRefMs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span>refMs, plus</span>
          <input type="number" min={0} step="any" value={r.perUnitRefNs}
                 onChange={(e) => set(r.runs, r.rpc, (c) => {
                   c.perUnitRefNs = Math.max(0, Number(e.target.value) || 0);
                 })} />
          <span>refNs a unit</span>
        </div>
      ))}
      {rows.length > 0 && !priced && (
        <p className="aside warn">
          Every call is instant. Nothing queues, nothing contends, and the timeline is empty —
          which is most of what a distributed system is interesting for.
        </p>
      )}
    </Panel>
  );
}

/* --------------------------------------------------------------- the retries
 *
 * Not a failure: a property of every caller in the system, keyed by the gRPC
 * method rather than by a node. So it stays a panel of its own, and says which
 * of the two it is.
 */
function Retries({
  draft, palette, edit,
}: {
  draft: Draft;
  palette: Palette;
  edit: (f: (d: Draft) => void) => void;
}) {
  const rpcs = palette.services.flatMap((s) =>
    s.rpcs.map((m) => ({ method: `${s.service}.${m.name}`, idempotent: m.idempotent })));
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
                flat: every attempt waits the same. A cluster that does not ease off a
                struggling node is how one slow node becomes an outage.
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
