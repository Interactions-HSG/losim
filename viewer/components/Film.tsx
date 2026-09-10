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
import * as stylex from '@stylexjs/stylex';
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { flushSync } from 'react-dom';

import { Dataflow } from './Dataflow.tsx';
import { LedgerStrip } from './Ledger.tsx';
import { NodePanel } from './NodePanel.tsx';
import { MessagePanel } from './MessagePanel.tsx';
import { Scrubber } from './Scrubber.tsx';
import type { Flight } from '../lib/frame.ts';
import { LedgerModel, money as money2 } from '../lib/ledger.ts';
import { Clock, FIT_SECONDS, RATES, rateSays, refTime } from '../lib/playback.ts';
import { record, save, still, type Recording } from '../lib/record.ts';
import type { Run } from '../lib/runs.ts';
import { useTheme } from '../lib/theme.ts';

import { chrome, figure, radius, shadow } from '../lib/tokens.stylex.ts';
import { ui } from '../lib/ui.stylex.ts';

const FPS = 30;

/**
 * The longest film that will be written to a file, in seconds.
 *
 * A long run is the right thing to *watch* — you can stop it — and the wrong
 * thing to hand somebody as a download they did not ask the length of. Past
 * this the film is squeezed into the cap, evenly, the way `fit` squeezes it.
 */
const MAX_RECORDING = 120;

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
    () => (outer ? null : new Clock(Math.max(trace.duration, against?.trace.duration ?? 0))),
    [outer, trace, against],
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
  const [showLedger, setShowLedger] = useState(false);
  const [zone, setZone] = useState('');
  const [role, setRole] = useState('');
  const [task, setTask] = useState<number | null>(null);
  /** Which revealed key rides on the faces. Not a filter: it hides nothing. */
  const [show, setShow] = useState('');
  const svgRef = useRef<SVGSVGElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  /**
   * The shape of the stage, so the arrangement can be searched against it.
   *
   * Quantised to a tenth, because a layout that re-searched on every pixel of a
   * window drag would rearrange the cluster while someone was resizing — and the
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
  // that sentence names a node as well as an instant, so both are in the URL.
  // Read on open; written when the clock is parked, rather than sixty times a
  // second while it runs.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const at = q.get('t');
    if (at !== null && Number.isFinite(Number(at))) clock.seek(Number(at));
    const m = q.get('m');
    if (m && trace.byName.has(m)) setPinned(m);
    // Guarded like `m` above: a key this run never reveals is dropped rather
    // than held, so the picker cannot sit on a selection the film cannot draw.
    const k = q.get('show');
    if (k && index.revealedKeys().includes(k)) setShow(k);
    if (q.get('ledger') === '1') setShowLedger(true);
  }, [clock, trace, index]);

  useEffect(() => {
    if (playing) return;
    const url = new URL(window.location.href);
    url.searchParams.set('run', run.name);
    url.searchParams.set('t', String(Math.round(t)));
    if (pinned) url.searchParams.set('m', pinned);
    else url.searchParams.delete('m');
    if (showLedger) url.searchParams.set('ledger', '1');
    else url.searchParams.delete('ledger');
    if (against) url.searchParams.set('vs', against.name);
    else url.searchParams.delete('vs');
    window.history.replaceState(null, '', url);
  }, [playing, t, run.name, pinned, showLedger, against]);

  // Its own effect, and deliberately not gated on `playing` like the one above.
  // That gate is there because `t` moves sixty times a second and the address
  // bar is not a clock; a dropdown moves when somebody moves it. The two write
  // disjoint keys and each re-reads the live URL first, so neither overwrites
  // the other's — and nominating a key mid-play still yields a link that works.
  useEffect(() => {
    const url = new URL(window.location.href);
    if (show) url.searchParams.set('show', show);
    else url.searchParams.delete('show');
    window.history.replaceState(null, '', url);
  }, [show]);

  // `layout` is in here on purpose: it is what the node positions come from,
  // so a re-searched arrangement has to make a new frame.
  const frame = useMemo(() => index.frameAt(t), [index, t, layout]);
  /**
   * When a node is too small to wear its labels.
   *
   * Counting nodes is the wrong test: it would cost the badges on every
   * cluster over thirteen — including the sixteen- and twenty-five-node runs,
   * where they fit perfectly well. What decides it is how large a node is actually
   * drawn, which the layout already knows: across the whole gallery that runs
   * from 1.55 at two nodes down to 0.47 at twenty-five, so nothing here is
   * cramped and the badges stay on. The floor is there for a cluster larger than
   * anything yet run.
   */
  const dense = layout.scaleFor < 0.4;

  const ledger = useMemo(() => (run.bill ? new LedgerModel(trace, run.bill) : null), [trace, run.bill]);

  // Which role each node plays, from the same columns the picture is drawn
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
  // The union across both panes, not this run's keys: a key only one run
  // reveals is exactly the one worth nominating, and the other run's silence is
  // then the answer rather than a gap in the menu.
  const revealKeys = useMemo(
    () => [...new Set([...index.revealedKeys(), ...(against?.index.revealedKeys() ?? [])])],
    [index, against],
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
    for (const m of trace.nodes) {
      if ((zone && m.zone !== zone) || (role && roleOf.get(m.name) !== role)) out.add(m.name);
    }
    return out;
  }, [trace, zone, role, roleOf]);

  const events = useMemo(() => index.events(), [index]);

  const shown = pinned ?? hovered;
  const shownNode = shown ? frame.nodes.find((m) => m.name === shown) : undefined;

  // The same selection the picture is drawn from, so the money cannot be about a
  // different node than the one under the cursor.
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
    // **The film's own length, not a fixed thirty seconds**, so a downloaded
    // film runs at the speed the run did. Capped, because a long run is minutes
    // of video and nobody wants that as a file by accident.
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
    <div {...stylex.props(styles.film)}>
      <div {...stylex.props(styles.views)}>
        <span {...stylex.props(ui.muted, styles.vhint)}>what is true right now</span>

        {/* Filters set nodes aside rather than removing them, so the picture
            never jumps and a filtered node is still visibly among a cluster. */}
        <div {...stylex.props(styles.filters)}>
          <select
            {...stylex.props(ui.picker, styles.filter)}
            value={zone}
            onChange={(e) => setZone(e.target.value)}
            aria-label="zone"
          >
            <option value="">every zone</option>
            {[...new Set(trace.nodes.map((m) => m.zone))].sort().map((z) => (
              <option key={z} value={z}>
                {z}
              </option>
            ))}
          </select>
          <select
            {...stylex.props(ui.picker, styles.filter)}
            value={role}
            onChange={(e) => setRole(e.target.value)}
            aria-label="role"
          >
            <option value="">every role</option>
            {roles.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
          {tasks.length > 0 && (
            <select
              {...stylex.props(ui.picker, styles.filter)}
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
          {/* Hidden when the run reveals nothing, like the task picker above:
              a control whose only option is "no" is furniture. */}
          {revealKeys.length > 0 && (
            <select
              {...stylex.props(ui.picker, styles.filter)}
              value={show}
              onChange={(e) => setShow(e.target.value)}
              aria-label="value on the face"
              data-show-picker=""
            >
              <option value="">no value on the face</option>
              {revealKeys.map((k) => (
                <option key={k} value={k}>
                  {k}
                </option>
              ))}
            </select>
          )}
          {(zone || role || task !== null || show) && (
            <button
              {...stylex.props(ui.btn)}
              onClick={() => {
                setZone('');
                setRole('');
                setTask(null);
                setShow('');
              }}
            >
              clear
            </button>
          )}
        </div>
      </div>

      <div {...stylex.props(styles.stage)}>
        <div {...stylex.props(styles.canvas)} ref={stageRef}>
        {against && <div {...stylex.props(styles.who)}>{run.name}</div>}
        <Dataflow
          ref={svgRef}
          layout={layout}
          frame={frame}
          theme={theme}
          dense={dense}
          show={show}
          hovered={shown}
          onHover={(n) => setHovered(n)}
          // Clicking the one already pinned closes it, the same as a message.
          onPinNode={(n) => setPinned(pinned === n ? null : n)}
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
          <div {...stylex.props(styles.canvas, styles.vs)}>
            <div {...stylex.props(styles.who)}>{against.name}</div>
            <Dataflow
              layout={vsLayout}
              frame={vsFrame}
              theme={theme}
              dense={vsLayout.scaleFor < 0.4}
              show={show}
              hovered={null}
              onHover={() => {}}
              task={task}
            />
            {t > against.trace.duration && (
              <div {...stylex.props(styles.ended)}>
                finished at {refTime(against.trace.duration)}
              </div>
            )}
          </div>
        )}
        {against && t > trace.duration && (
          <div {...stylex.props(styles.ended, styles.endedLeft)}>finished at {refTime(trace.duration)}</div>
        )}

        {shownNode && (
          <div {...stylex.props(styles.dock, !!pinned && styles.dockStatic)}>
            <NodePanel
              trace={trace}
              m={shownNode}
              t={t}
              money={money}
              pinned={pinned === shownNode.name}
              onPin={() => setPinned(pinned === shownNode.name ? null : shownNode.name)}
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
        <div {...stylex.props(ui.card, styles.verdict)}>
          <span>
            <strong>{run.name}</strong> {money2(money.cost, money.currency)}
          </span>
          <span {...stylex.props(ui.muted)}>against</span>
          <span>
            <strong>{against.name}</strong> {money2(vsMoney.cost, vsMoney.currency)}
          </span>
          <span {...stylex.props(styles.gap)}>
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
      <div {...stylex.props(ui.card, styles.playbar)}>
        <button
          {...stylex.props(ui.btn, ui.icon, ui.primary)}
          onClick={() => clock.toggle()}
          title={playing ? 'pause (space)' : 'play (space)'}
          aria-label={playing ? 'pause' : 'play'}
        >
          {playing ? <Pause /> : <Play />}
        </button>
        <button {...stylex.props(ui.btn, ui.icon)} onClick={() => clock.step(-1)} title="back one frame (←)">
          ◀
        </button>
        <button {...stylex.props(ui.btn, ui.icon)} onClick={() => clock.step(1)} title="on one frame (→)">
          ▶
        </button>

        <span {...stylex.props(styles.clock, ui.mono)}>
          {refTime(t)} <span {...stylex.props(ui.muted)}>/ {refTime(trace.duration)}</span>
        </span>

        <Scrubber
          t={t}
          duration={trace.duration}
          events={events}
          onSeek={(to) => {
            clock.pause();
            clock.seek(to);
          }}
        />

        <div {...stylex.props(ui.seg)} role="group" aria-label="speed">
          {RATES.map((r) => (
            <button
              key={r}
              {...stylex.props(ui.segButton, rateLabel === `${r}x` && ui.segOn)}
              aria-pressed={rateLabel === `${r}x`}
              onClick={() => clock.setRate(r)}
              title={rateSays(r)}
            >
              {r}x
            </button>
          ))}
          <button
            {...stylex.props(ui.segButton, rateLabel === 'fit' && ui.segOn)}
            aria-pressed={rateLabel === 'fit'}
            onClick={() => clock.fit()}
            title={`the whole run in ${FIT_SECONDS} seconds, whatever it took.`}
          >
            fit
          </button>
        </div>

        <span {...stylex.props(ui.seg)} role="group" aria-label="save this instant">
          <button {...stylex.props(ui.segButton)} onClick={() => snap('png')} disabled={!!recording} title="this frame as a PNG, 1920x1080">
            png
          </button>
          <button {...stylex.props(ui.segButton)} onClick={() => snap('svg')} disabled={!!recording} title="this frame as vector SVG — type stays type on a projector">
            svg
          </button>
        </span>

        <button {...stylex.props(ui.btn)} onClick={download} disabled={!!recording}>
          {recording ?? (made ? 'download again' : 'download film')}
        </button>
      </div>
      ) : (
        // The console's bar has the clock. What is left is what only exists
        // where the picture is: this instant as a file, and the film as one.
        <div {...stylex.props(styles.savebar)}>
          <span {...stylex.props(ui.seg)} role="group" aria-label="save this instant">
            <button {...stylex.props(ui.segButton)} onClick={() => snap('png')} disabled={!!recording} title="this frame as a PNG, 1920x1080">
              png
            </button>
            <button {...stylex.props(ui.segButton)} onClick={() => snap('svg')} disabled={!!recording} title="this frame as vector SVG — type stays type on a projector">
              svg
            </button>
          </span>
          <button {...stylex.props(ui.btn)} onClick={download} disabled={!!recording}>
            {recording ?? (made ? 'download again' : 'download film')}
          </button>
        </div>
      )}

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

const NARROW = '@media (max-width: 900px)';

const styles = stylex.create({
  film: { display: 'flex', flexDirection: 'column', gap: '8px', minHeight: 0, flex: 1 },
  savebar: { display: 'flex', alignItems: 'center', gap: '8px', flex: 'none' },
  views: { display: 'flex', alignItems: 'center', gap: '10px', flex: 'none' },
  vhint: { fontSize: '11.5px' },
  filters: { display: 'flex', gap: '6px', marginLeft: 'auto', alignItems: 'center' },
  /**
   * The three filters were bare `<select>`s and drew as whatever the operating
   * system draws, beside a `ui.picker` that did not. Sized down from the shared
   * picker because they sit in a row of three on the film's own bar rather than
   * alone in a page header.
   */
  filter: { height: '28px', fontSize: '12.5px', maxWidth: '150px' },

  /**
   * Over the picture while hovering, beside it once pinned. A hover is a peek and
   * must not move the film under the cursor; a pin says "I want to watch this
   * one", and a watched node should not have to be watched through a panel
   * covering the rest. Docked, the stage narrows — and because the arrangement is
   * searched against the stage's real shape, the film re-fits into what is left
   * rather than being cropped by it.
   */
  stage: {
    position: 'relative',
    flex: 1,
    minHeight: 0,
    backgroundColor: figure.paper,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.lg,
    overflow: 'hidden',
    boxShadow: shadow.s1,
    display: 'flex',
  },
  canvas: { flex: 1, minWidth: 0, position: 'relative' },
  /** The second run, when two are being compared, with a rule between them. */
  vs: { borderLeftWidth: '1px', borderLeftStyle: 'solid', borderLeftColor: chrome.border },
  who: {
    position: 'absolute',
    top: '12px',
    left: '50%',
    transform: 'translateX(-50%)',
    fontSize: '11.5px',
    fontWeight: 600,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    color: chrome.text3,
    zIndex: 2,
  },
  /**
   * A run that has ended says so and stays on screen. Blanking it would hide the
   * very fact the comparison is about.
   */
  ended: {
    position: 'absolute',
    bottom: '12px',
    left: '50%',
    transform: 'translateX(-50%)',
    fontSize: '11.5px',
    color: chrome.text3,
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: '999px',
    paddingBlock: '2px',
    paddingInline: '10px',
    zIndex: 2,
  },
  endedLeft: { left: '25%' },
  verdict: {
    display: 'flex',
    alignItems: 'baseline',
    gap: '12px',
    paddingBlock: '7px',
    paddingInline: '12px',
    flex: 'none',
    fontSize: '12.5px',
    flexWrap: 'wrap',
  },
  gap: { marginLeft: 'auto', color: chrome.text2 },

  /**
   * `pointerEvents: none` so the dock does not swallow clicks meant for the film
   * behind it. The panels inside it turn it back on for themselves — the old CSS
   * said that with `.dock > *`, which StyleX has no way to select.
   */
  dock: {
    position: 'absolute',
    top: '12px',
    right: '12px',
    bottom: '12px',
    zIndex: 5,
    display: 'flex',
    pointerEvents: 'none',
  },
  /** Pinned: the dock stops floating and takes its own column beside the film. */
  dockStatic: {
    position: 'static',
    flex: 'none',
    paddingBlock: '12px',
    paddingRight: '12px',
    paddingLeft: 0,
    pointerEvents: 'auto',
  },

  playbar: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    paddingBlock: '8px',
    paddingInline: '12px',
    flex: 'none',
    flexWrap: 'wrap',
  },
  clock: {
    fontSize: '12.5px',
    minWidth: '120px',
    textAlign: 'center',
    display: { default: 'inline', [NARROW]: 'none' },
  },
});
