'use client';

/**
 * A run, watched.
 *
 * The film, a scrubber shaped like the one everybody already knows, a panel that
 * opens on hover and stays open when pinned, and a recorder — all of them
 * reading the same `(trace, t)` and therefore incapable of disagreeing with each
 * other.
 *
 * The recorder is the same code path as playing (lib/record.ts): the only
 * difference is who advances the clock, which is why the film you download is
 * the film you watched.
 */
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { flushSync } from 'react-dom';

import { Dataflow } from './Dataflow.tsx';
import { LedgerStrip } from './Ledger.tsx';
import { MachinePanel } from './MachinePanel.tsx';
import { MessagePanel } from './MessagePanel.tsx';
import { Scrubber, type Chapter } from './Scrubber.tsx';
import { Spans } from './Spans.tsx';
import { Topology } from './Topology.tsx';
import type { Flight } from '../lib/frame.ts';
import { LedgerModel, money as money2 } from '../lib/ledger.ts';
import { HOLD_SECONDS } from '../lib/pace.ts';
import { Clock, FIT_SECONDS, RATES, refTime } from '../lib/playback.ts';
import { record, save, still, type Recording } from '../lib/record.ts';
import type { Run } from '../lib/runs.ts';
import { useTheme } from '../lib/theme.ts';

const FPS = 30;

/**
 * The longest film that will be written to a file, in seconds.
 *
 * A run under standing chaos, paced so nothing is quicker than the eye, is very
 * nearly three minutes. That is the right thing to *watch* — you can stop it —
 * and the wrong thing to hand somebody as a download they did not ask the length
 * of. Past this the pacing is squeezed to fit, keeping its shape: the quick parts
 * still get far more of the film than their share of the run, just less than a
 * whole second each.
 */
const MAX_RECORDING = 120;

/** How long this film runs, in the shortest form that is still a duration. */
function fmtFilm(seconds: number): string {
  if (seconds >= 90) return `${Math.floor(seconds / 60)}:${String(Math.round(seconds % 60)).padStart(2, '0')}`;
  return `${seconds.toFixed(0)}s`;
}

/**
 * Nothing on screen for less than this, in real seconds.
 *
 * There is no dwell. Holding a message *in place* after it had arrived would
 * make the picture say something false — that the call was still going — and
 * would not help a call whose entire life is shorter than one frame anyway.
 * Instead **the clock slows down** while anything short is happening
 * (`lib/pace.ts`), so the message really does take a second to cross.
 * Ordering, overlap and every number stay exactly as the trace has them;
 * only the pace of the playhead changes.
 */
const HOLDS = [HOLD_SECONDS, 0.5, 0.25, 0] as const;

export function Film({
  run,
  against,
  clock: outer,
  transport = true,
}: {
  run: Run;
  against?: Run | null;
  /**
   * The console's clock, when there is one.
   *
   * Without one, the film owns its own clock, and the ledger can only accrue
   * underneath the film — no other page has a cursor on it. Given one from
   * outside, this becomes one drawing of a shared instant rather than the
   * only place that instant exists.
   */
  clock?: Clock;
  /**
   * Whether to draw the transport. False when the console's own bar is carrying
   * it, so there are not two identical rows of buttons on one screen.
   */
  transport?: boolean;
}) {
  const { trace, index } = run;
  const theme = useTheme();

  /**
   * One clock, and it runs to whichever of the two lasts longer.
   *
   * **Absolute reference milliseconds, not a share of each run.** Normalising
   * the two to "forty percent through" would make every comparison look like a
   * draw, and the thing a comparison is *for* is that at 2,400 one of them has
   * finished and the other has not. So the shorter run simply ends, and sits
   * there having ended, which is the argument.
   */
  const own = useMemo(
    () =>
      outer
        ? null
        : new Clock(Math.max(trace.duration, against?.trace.duration ?? 0), [
            ...index.moments(),
            // Both runs' moments when two are being compared, so the clock is slow
            // enough for whichever of them is doing something quick. Paced against
            // only its own, one film would race through the other's fast calls.
            ...(against ? against.index.moments() : []),
          ]),
    [outer, trace, against, index],
  );
  const clock = outer ?? (own as Clock);
  // Only what this component made is this component's to dispose.
  useEffect(() => () => own?.dispose(), [own]);

  const t = useSyncExternalStore(clock.subscribe, clock.now, clock.now);
  const playing = useSyncExternalStore(clock.subscribe, clock.isPlaying, () => false);
  const rateLabel = useSyncExternalStore(clock.subscribe, clock.label, () => '1x');

  const [hovered, setHovered] = useState<string | null>(null);
  /**
   * The message under the pointer, and where the pointer is in the stage.
   *
   * Kept here rather than in `Dataflow` because the panel is an HTML element
   * over the stage, and the stage is what knows how big it is.
   */
  const [message, setMessage] = useState<{ f: Flight; at: [number, number] } | null>(null);
  /** Clicked rather than pointed at: it stays, and it takes the pointer. */
  const [heldMessage, setHeldMessage] = useState<{ f: Flight; at: [number, number] } | null>(null);
  const [pinned, setPinned] = useState<string | null>(null);
  const [recording, setRecording] = useState<string | null>(null);
  const [made, setMade] = useState<Recording | null>(null);
  const [hold, setHold] = useState<number>(HOLDS[0]);
  const filmSeconds = useSyncExternalStore(clock.subscribe, clock.filmSeconds, () => 0);
  const stretch = useSyncExternalStore(clock.subscribe, clock.stretch, () => 1);
  // The pace belongs to the clock, so the toggle sets it there rather than being
  // read by the frame. Re-applied when the clock changes: a new run starts held.
  // Whoever owns the clock owns its pacing: with a console bar above, the hold
  // button up there is the one that means anything.
  useEffect(() => { if (!outer) clock.setHold(hold); }, [clock, hold, outer]);
  const [showLedger, setShowLedger] = useState(false);
  const [view, setView] = useState<'film' | 'spans' | 'topology'>('film');
  const [zone, setZone] = useState('');
  const [role, setRole] = useState('');
  const [task, setTask] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  /**
   * The shape of the stage, so the arrangement can be searched against it.
   *
   * Quantised to a tenth, because a layout that re-searched on every pixel of a
   * window drag would rearrange the fleet while someone was resizing — and the
   * difference between 2.10 and 2.13 never changes the answer anyway.
   */
  const [aspect, setAspect] = useState(12.4 / 5.6);
  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const w = el.clientWidth;
      const h = el.clientHeight;
      if (w > 0 && h > 0) setAspect(Math.round((w / h) * 10) / 10);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  // Half the stage each when two runs are up, so neither is arranged for room it
  // does not have.
  const share = against ? aspect / 2 : aspect;
  const layout = useMemo(() => index.refit([share * 5.6, 5.6]), [index, share]);

  // A moment is a thing people want to point at — "look at m1 at 2,400" — and
  // that sentence names a machine as well as an instant, so both are in the URL.
  // Read on open; written when the clock is parked, rather than sixty times a
  // second while it runs.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const at = q.get('t');
    if (at !== null && Number.isFinite(Number(at))) clock.seek(Number(at));
    const m = q.get('m');
    if (m && trace.byName.has(m)) setPinned(m);
    if (q.get('pnl') === '1') setShowLedger(true);
    // `fv`, not `view`: the console owns `view` and would be overwritten here.
    // Same shape as `sv` for the Spans sub-view, one level down.
    const v = q.get('fv');
    if (v === 'spans' || v === 'topology' || v === 'film') setView(v);
  }, [clock, trace]);

  useEffect(() => {
    if (playing) return;
    const url = new URL(window.location.href);
    url.searchParams.set('run', run.name);
    url.searchParams.set('t', String(Math.round(t)));
    if (pinned) url.searchParams.set('m', pinned);
    else url.searchParams.delete('m');
    if (showLedger) url.searchParams.set('pnl', '1');
    else url.searchParams.delete('pnl');
    if (view !== 'film') url.searchParams.set('fv', view);
    else url.searchParams.delete('fv');
    if (against) url.searchParams.set('vs', against.name);
    else url.searchParams.delete('vs');
    window.history.replaceState(null, '', url);
  }, [playing, t, run.name, pinned, showLedger, view, against]);

  // `layout` is in here on purpose: it is what the machine positions come from,
  // so a re-searched arrangement has to make a new frame.
  const frame = useMemo(() => index.frameAt(t), [index, t, layout]);
  /**
   * When a machine is too small to wear its labels.
   *
   * Counting machines is the wrong test: it would cost the badges on every
   * fleet over thirteen — including the sixteen- and twenty-five-machine runs,
   * where they fit perfectly well. What decides it is how large a machine is actually
   * drawn, which the layout already knows: across the whole gallery that runs
   * from 1.55 at two machines down to 0.47 at twenty-five, so nothing here is
   * cramped and the badges stay on. The floor is there for a fleet larger than
   * anything yet run.
   */
  const dense = layout.scaleFor < 0.4;

  const ledger = useMemo(() => (run.bill ? new LedgerModel(trace, run.bill) : null), [trace, run.bill]);

  // Which role each machine plays, from the same columns the picture is drawn
  // in — so "shufflers only" means exactly the column captioned SHUFFLE.
  const roleOf = useMemo(() => {
    const out = new Map<string, string>();
    layout.columns.forEach((names, i) => {
      const label = layout.columnLabel(i);
      for (const n of names) out.set(n, label);
    });
    return out;
  }, [layout]);
  const roles = useMemo(() => [...new Set(roleOf.values())], [roleOf]);
  const tasks = useMemo(
    () => [...new Set(index.tasks.values())].sort((a, b) => a - b),
    [index],
  );

  // The second run gets its own everything, on the same clock. Its layout is
  // searched against half the stage, so two films side by side are each fitted
  // to the room they actually have rather than to the room one of them had.
  const vsLayout = useMemo(
    () => against?.index.refit([(aspect / 2) * 5.6, 5.6]) ?? null,
    [against, aspect],
  );
  const vsFrame = useMemo(
    () => (against ? against.index.frameAt(t) : null),
    [against, t, vsLayout],
  );
  const vsLedger = useMemo(
    () => (against?.bill ? new LedgerModel(against.trace, against.bill) : null),
    [against],
  );
  const vsMoney = useMemo(() => vsLedger?.at(t) ?? null, [vsLedger, t]);

  const muted = useMemo(() => {
    const out = new Set<string>();
    if (!zone && !role) return out;
    for (const m of trace.machines) {
      if ((zone && m.zone !== zone) || (role && roleOf.get(m.name) !== role)) out.add(m.name);
    }
    return out;
  }, [trace, zone, role, roleOf]);

  const chapters: Chapter[] = useMemo(
    () =>
      trace
        .phases()
        .filter((p) => p.t1 > p.t0)
        .sort((a, b) => a.t0 - b.t0)
        .map((p) => ({ label: p.label, t0: p.t0, t1: p.t1 })),
    [trace],
  );
  const events = useMemo(() => index.events(), [index]);

  const shown = pinned ?? hovered;
  const shownMachine = shown ? frame.machines.find((m) => m.name === shown) : undefined;

  // The same selection the picture is drawn from, so the money cannot be about a
  // different machine than the one under the cursor.
  const money = useMemo(() => ledger?.at(t, shown) ?? null, [ledger, t, shown]);

  // ------------------------------------------------------------- keyboard

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && /^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName)) return;
      // Escape is this component's either way; the transport keys belong to
      // whichever component is showing the transport, or they fire twice.
      if (e.key === 'Escape') {
        setHeldMessage((held) => {
          if (!held) setPinned(null);
          return null;
        });
        return;
      }
      if (!transport) return;
      if (e.key === ' ') {
        e.preventDefault();
        clock.toggle();
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        clock.step(e.shiftKey ? 10 : 1);
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        clock.step(e.shiftKey ? -10 : -1);
      } else if (e.key === '[' || e.key === ']') {
        e.preventDefault();
        const now = clock.now();
        const times = events.map((x) => Number(x.t ?? 0));
        const next =
          e.key === ']'
            ? times.find((x) => x > now + 0.5)
            : [...times].reverse().find((x) => x < now - 0.5);
        if (next !== undefined) {
          clock.pause();
          clock.seek(next);
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [clock, events, transport]);

  // ------------------------------------------------------------ recording

  const download = useCallback(async () => {
    clock.pause();
    // The recorder serialises the film's own SVG, and a hidden element has no
    // box to measure. Come back to the film rather than record nothing.
    flushSync(() => setView('film'));
    // **The film's own length, not a fixed thirty seconds.** Recording evenly
    // across the run would spend the frames in trace time and undo all of the
    // pacing — every quick message back to one frame, which is exactly the video
    // a lecture cannot use. Capped, because a chaotic run paced to a one-second
    // floor is minutes long and nobody wants that as a file by accident.
    const seconds = Math.min(MAX_RECORDING, Math.max(2, clock.filmSeconds()));
    const frames = Math.max(2, Math.round(seconds * FPS));
    const squeeze = clock.filmSeconds() / seconds;
    setMade(null);
    try {
      const made = await record({
        width: 1920,
        height: 1080,
        fps: FPS,
        frames,
        showFrame: (f) => {
          flushSync(() => clock.seekFilm((f / (frames - 1)) * seconds * squeeze));
        },
        svgOf: () => svgRef.current!,
        onProgress: (done, total) => setRecording(`${Math.round((done / total) * 100)}%`),
      });
      setMade(made);
      save(made, run.name);
    } catch (e) {
      setRecording(`failed: ${(e as Error).message}`);
      return;
    }
    setRecording(null);
  }, [clock, run.name]);

  /**
   * This instant, saved. The film is for showing a run; a still is for a slide,
   * and a lecture needs far more of the second than of the first.
   */
  const snap = useCallback(
    async (as: 'png' | 'svg') => {
      const svg = svgRef.current;
      if (!svg) return;
      setRecording(as === 'svg' ? 'saving svg…' : 'saving png…');
      try {
        await still(svg, `${run.name}-${Math.round(t)}ms`, as);
        setRecording(null);
      } catch (e) {
        setRecording(`failed: ${(e as Error).message}`);
      }
    },
    [run.name, t],
  );

  return (
    <div className="film">
      <div className="views">
        <div className="seg" role="group" aria-label="view">
          {(['film', 'spans', 'topology'] as const).map((v) => (
            <button key={v} aria-pressed={view === v} onClick={() => setView(v)}>
              {v}
            </button>
          ))}
        </div>
        <span className="muted vhint">
          {view === 'film'
            ? 'what is true right now'
            : view === 'spans'
              ? 'why — the distributed call stack'
              : 'who called whom, over the whole run'}
        </span>

        {/* Filters set machines aside rather than removing them, so the picture
            never jumps and a filtered machine is still visibly among a fleet. */}
        {view === 'film' && (
          <div className="filters">
            <select value={zone} onChange={(e) => setZone(e.target.value)} aria-label="zone">
              <option value="">every zone</option>
              {[...new Set(trace.machines.map((m) => m.zone))].sort().map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </select>
            <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="role">
              <option value="">every role</option>
              {roles.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
            {tasks.length > 0 && (
              <select
                value={task === null ? '' : String(task)}
                onChange={(e) => setTask(e.target.value === '' ? null : Number(e.target.value))}
                aria-label="task"
              >
                <option value="">every task</option>
                {tasks.map((n) => (
                  <option key={n} value={n}>
                    task {n}
                  </option>
                ))}
              </select>
            )}
            {(zone || role || task !== null) && (
              <button
                className="btn"
                onClick={() => {
                  setZone('');
                  setRole('');
                  setTask(null);
                }}
              >
                clear
              </button>
            )}
          </div>
        )}
      </div>

      {view === 'spans' && (
        <Spans
          trace={trace}
          theme={theme}
          t={t}
          onSeek={(to) => {
            clock.pause();
            clock.seek(to);
          }}
          hovered={shown}
          onHoverMachine={(n) => !pinned && setHovered(n)}
        />
      )}

      {view === 'topology' && (
        <Topology
          trace={trace}
          layout={layout}
          theme={theme}
          t={t}
          hovered={shown}
          onHover={(n) => !pinned && setHovered(n)}
        />
      )}

      <div className={`stage${pinned ? ' docked' : ''}${against ? ' twin' : ''}`} hidden={view !== 'film'}>
        <div className="canvas" ref={stageRef}>
        {against && <div className="who">{run.name}</div>}
        <Dataflow
          ref={svgRef}
          layout={layout}
          frame={frame}
          theme={theme}
          dense={dense}
          hovered={shown}
          onHover={(n) => setHovered(n)}
          // Clicking the one already pinned closes it, the same as a message.
          onPinMachine={(n) => setPinned(pinned === n ? null : n)}
          onMessage={(f, at) => {
            if (!f) {
              setMessage(null);
              return;
            }
            const box = stageRef.current?.getBoundingClientRect();
            setMessage({ f, at: box ? [at[0] - box.left, at[1] - box.top] : at });
          }}
          onPinMessage={(f, at) => {
            const box = stageRef.current?.getBoundingClientRect();
            const where: [number, number] = box ? [at[0] - box.left, at[1] - box.top] : at;
            // Clicking the one already pinned closes it, which is what a second
            // press on the same thing means everywhere else in this viewer.
            setHeldMessage((was) => (was && was.f.id === f.id && was.f.returning === f.returning
              ? null
              : { f, at: where }));
          }}
          muted={muted}
          task={task}
        />
        {frame.phase && <div className="phase">{frame.phase}</div>}
        {(heldMessage ?? message) && (
          <MessagePanel
            f={(heldMessage ?? message)!.f}
            at={(heldMessage ?? message)!.at}
            within={[stageRef.current?.clientWidth ?? 0, stageRef.current?.clientHeight ?? 0]}
            pinned={!!heldMessage}
            onClose={() => setHeldMessage(null)}
          />
        )}
        </div>

        {against && vsFrame && vsLayout && (
          <div className="canvas vs">
            <div className="who">{against.name}</div>
            <Dataflow
              layout={vsLayout}
              frame={vsFrame}
              theme={theme}
              dense={vsLayout.scaleFor < 0.4}
              hovered={null}
              onHover={() => {}}
              task={task}
            />
            {vsFrame.phase && <div className="phase">{vsFrame.phase}</div>}
            {t > against.trace.duration && (
              <div className="ended">
                finished at {refTime(against.trace.duration)}
              </div>
            )}
          </div>
        )}
        {against && t > trace.duration && (
          <div className="ended left">finished at {refTime(trace.duration)}</div>
        )}

        {shownMachine && (
          <div className="dock">
            <MachinePanel
              trace={trace}
              m={shownMachine}
              t={t}
              money={money}
              pinned={pinned === shownMachine.name}
              onPin={() => setPinned(pinned === shownMachine.name ? null : shownMachine.name)}
              onClose={() => {
                setPinned(null);
                setHovered(null);
              }}
            />
          </div>
        )}
      </div>

      {/* Two designs, one number each. Cheaper wins, and the gap is the whole
          argument: `mr-locality` against `mr-locality-blind` is a claim about
          what the second one's egress costs, and this is where it is settled. */}
      {against && vsMoney && money && (
        <div className="verdict card">
          <span>
            <strong>{run.name}</strong> {money2(money.cost, money.currency)}
          </span>
          <span className="muted">against</span>
          <span>
            <strong>{against.name}</strong> {money2(vsMoney.cost, vsMoney.currency)}
          </span>
          <span className="gap">
            {money.cost <= vsMoney.cost ? run.name : against.name} is cheaper by{' '}
            {money2(Math.abs(money.cost - vsMoney.cost), money.currency)}
          </span>
        </div>
      )}

      {money && (
        <LedgerStrip
          l={money}
          open={showLedger}
          onToggle={() => setShowLedger(!showLedger)}
          onHover={(name) => !pinned && setHovered(name)}
        />
      )}

      {transport ? (
      <div className="playbar card">
        <button
          className="btn icon primary"
          onClick={() => clock.toggle()}
          title={playing ? 'pause (space)' : 'play (space)'}
          aria-label={playing ? 'pause' : 'play'}
        >
          {playing ? <Pause /> : <Play />}
        </button>
        <button className="btn icon" onClick={() => clock.step(-1)} title="back one frame (←)">
          ◀
        </button>
        <button className="btn icon" onClick={() => clock.step(1)} title="on one frame (→)">
          ▶
        </button>

        <span className="clock mono">
          {refTime(t)} <span className="muted">/ {refTime(trace.duration)}</span>
          {hold > 0 && (
            // What you are actually watching, said out loud. The run took five
            // seconds and the film takes fifty-five; leaving somebody to work
            // that out from a clock that moves at a changing rate is how a
            // deliberate choice comes to look like a bug.
            <span className="muted"> · {fmtFilm(filmSeconds)} film</span>
          )}
        </span>

        <Scrubber
          t={t}
          duration={trace.duration}
          chapters={chapters}
          events={events}
          onSeek={(to) => {
            clock.pause();
            clock.seek(to);
          }}
        />

        <div className="seg" role="group" aria-label="speed">
          {RATES.map((r) => (
            <button
              key={r}
              aria-pressed={rateLabel === `${r}x`}
              onClick={() => clock.setRate(r)}
              title={
                r === 1
                  ? 'the film at its own pace — nothing on screen for less than a second'
                  : `${r}x that pace. Above 1x the quick messages go back under a second.`
              }
            >
              {r}x
            </button>
          ))}
          <button
            aria-pressed={rateLabel === 'fit'}
            onClick={() => clock.fit()}
            title={
              `the whole film in ${FIT_SECONDS} seconds. It keeps the pacing — the quick parts still `
              + 'get far more than their share of the run — but squeezed to fit, so the one-second '
              + 'floor only holds at 1x.'
            }
          >
            fit
          </button>
        </div>

        <button
          className="btn"
          onClick={() => setHold(HOLDS[(HOLDS.indexOf(hold as never) + 1) % HOLDS.length])}
          aria-pressed={hold > 0}
          title={
            hold > 0
              ? `The clock slows down where things are quick, so nothing is on screen for less than ${hold}s. `
                + `This film runs ${fmtFilm(filmSeconds)} — ${stretch.toFixed(0)}x the run itself. `
                + 'Every reading is still at its true instant; only the pace changes. '
                + 'Press for a shorter hold, and a shorter film.'
              : 'One simulated second per real second — where most calls are quicker than a single frame. '
                + 'Press to slow the quick parts down again.'
          }
        >
          {hold > 0 ? '\u25c9' : '\u25cb'} hold {hold > 0 ? `${hold}s` : 'off'}
        </button>

        {view === 'film' && (
          <span className="seg" role="group" aria-label="save this instant">
            <button onClick={() => snap('png')} disabled={!!recording} title="this frame as a PNG, 1920x1080">
              png
            </button>
            <button onClick={() => snap('svg')} disabled={!!recording} title="this frame as vector SVG — type stays type on a projector">
              svg
            </button>
          </span>
        )}

        <button className="btn" onClick={download} disabled={!!recording}>
          {recording ?? (made ? 'download again' : 'download film')}
        </button>
      </div>
      ) : (
        // The console's bar has the clock. What is left is what only exists
        // where the picture is: this instant as a file, and the film as one.
        <div className="savebar">
          {view === 'film' && (
            <span className="seg" role="group" aria-label="save this instant">
              <button onClick={() => snap('png')} disabled={!!recording} title="this frame as a PNG, 1920x1080">
                png
              </button>
              <button onClick={() => snap('svg')} disabled={!!recording} title="this frame as vector SVG — type stays type on a projector">
                svg
              </button>
            </span>
          )}
          <button className="btn" onClick={download} disabled={!!recording}>
            {recording ?? (made ? 'download again' : 'download film')}
          </button>
        </div>
      )}

      <style>{`
        .film { display: flex; flex-direction: column; gap: 8px; min-height: 0; flex: 1; }
        .savebar { display: flex; align-items: center; gap: 8px; flex: none; }
        .views { display: flex; align-items: center; gap: 10px; flex: none; }
        .vhint { font-size: 11.5px; }
        .filters { display: flex; gap: 6px; margin-left: auto; align-items: center; }
        .stage[hidden] { display: none; }
        .stage {
          position: relative; flex: 1; min-height: 0;
          background: var(--paper); border: 1px solid var(--border);
          border-radius: var(--r-lg); overflow: hidden; box-shadow: var(--shadow-1);
        }
        .stage svg { width: 100%; height: 100%; }
        .phase {
          position: absolute; top: 12px; left: 14px;
          font-size: 12px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase;
          color: var(--pencil); background: rgba(255,255,255,.72);
          border: 1px solid var(--rule); border-radius: 999px; padding: 3px 11px;
          backdrop-filter: blur(6px);
        }
        /* Over the picture while hovering, **beside** it once pinned.
           A hover is a peek and must not move the film under the cursor; a pin
           says "I want to watch this one", and a watched machine should not have
           to be watched through a panel covering the reducers. Docked, the stage
           narrows — and because the arrangement is searched against the stage's
           real shape, the film re-fits into what is left rather than being
           cropped by it. */
        .stage { display: flex; }
        .canvas { flex: 1; min-width: 0; position: relative; }
        .stage.twin .canvas.vs { border-left: 1px solid var(--border); }
        .who {
          position: absolute; top: 12px; left: 50%; transform: translateX(-50%);
          font-size: 11.5px; font-weight: 600; letter-spacing: .04em;
          text-transform: uppercase; color: var(--text-3); z-index: 2;
        }
        /* A run that has ended says so and stays on screen. Blanking it would
           hide the very fact the comparison is about. */
        .ended {
          position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%);
          font-size: 11.5px; color: var(--text-3); background: var(--surface);
          border: 1px solid var(--border); border-radius: 999px; padding: 2px 10px;
          z-index: 2;
        }
        .ended.left { left: 25%; }
        .verdict {
          display: flex; align-items: baseline; gap: 12px; padding: 7px 12px;
          flex: none; font-size: 12.5px; flex-wrap: wrap;
        }
        .verdict .gap { margin-left: auto; color: var(--text-2); }
        .canvas svg { width: 100%; height: 100%; }
        .dock {
          position: absolute; top: 12px; right: 12px; bottom: 12px;
          z-index: 5; display: flex; pointer-events: none;
        }
        .dock > * { pointer-events: auto; }
        .stage.docked .dock {
          position: static; flex: none; padding: 12px 12px 12px 0;
          pointer-events: auto;
        }

        .playbar {
          display: flex; align-items: center; gap: 8px;
          padding: 8px 12px; flex: none; flex-wrap: wrap;
        }
        .clock { font-size: 12.5px; min-width: 120px; text-align: center; }
        @media (max-width: 900px) { .clock { display: none; } }
      `}</style>
    </div>
  );
}

function Play() {
  return (
    <svg width="12" height="13" viewBox="0 0 12 13" aria-hidden>
      <path d="M1.5 1.2 10.6 6.5 1.5 11.8Z" fill="currentColor" />
    </svg>
  );
}

function Pause() {
  return (
    <svg width="11" height="13" viewBox="0 0 11 13" aria-hidden>
      <rect x="0.6" y="1" width="3.3" height="11" rx="1" fill="currentColor" />
      <rect x="7.1" y="1" width="3.3" height="11" rx="1" fill="currentColor" />
    </svg>
  );
}
