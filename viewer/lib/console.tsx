'use client';

/**
 * The console's own state: which run is open, and what time it is.
 *
 * Everything else in this app is a drawing of `(trace, t)`. What was missing was
 * somewhere for the `t` to live that was not inside one of those drawings. The
 * film owned the clock, so the ledger could only accrue underneath the film, and
 * a question like "what had we paid for by the time the first worker answered"
 * had no page to be asked on.
 *
 * So the clock moves up here, above the views, and the views become four
 * drawings of one instant: the film, the execution graph, what each machine was
 * doing, and what it had cost by then. Dragging one cursor moves all four,
 * because there is only one cursor.
 *
 * ## Why the views are not routes
 *
 * They would read better in the URL, and they cannot be. The viewer is a static
 * export (`next.config.mjs`) served by `python3 -m http.server` out of whatever
 * directory it happens to be in — sometimes the root, sometimes a subdirectory,
 * sometimes a file:// path somebody was sent. Absolute route paths break in two
 * of those three, and a real navigation would unmount the clock and reload the
 * trace on every tab. So the view is state, the URL carries it as a query, and
 * the History API keeps the back button working.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';

import { LedgerModel } from './ledger.ts';
import { Clock } from './playback.ts';
import { manifest, openFile, openUrl, type Run, type RunRef } from './runs.ts';

export const VIEWS = ['runs', 'design', 'overview', 'film', 'usage', 'cost', 'systems'] as const;
export type View = (typeof VIEWS)[number];

/** The views the clock governs. On the others it is not shown, because there is no time in them. */
export const TIMED: ReadonlySet<View> = new Set<View>(['overview', 'film', 'usage', 'cost']);

export interface ConsoleState {
  /** Every run the export was built with. */
  runs: RunRef[];
  /** The one that is open, once its trace has been read. */
  run: Run | null;
  /** What it cost, when there is a bill beside it. */
  ledger: LedgerModel | null;
  /** The clock every view reads. Null until a run is open. */
  clock: Clock | null;
  view: View;
  busy: boolean;
  error: string | null;
  /** Whether `losim serve` is behind this page. */
  hasLab: boolean;
  /** Bumped when something starts a run, so the panel that follows one asks again. */
  watching: number;
  nudge: () => void;

  go: (view: View) => void;
  open: (name: string, view?: View) => Promise<void>;
  /** Open a run by where it is, for one that was written a moment ago and is not in the index yet. */
  openAt: (name: string, href: string, view?: View) => Promise<void>;
  openDropped: (file: File) => Promise<void>;
  reload: () => Promise<void>;
  setHasLab: (v: boolean) => void;
  setError: (e: string | null) => void;
}

/**
 * The seam.
 *
 * Exported so the console's views can be rendered against a made-up state — a
 * real trace, a real bill, a clock parked at a chosen instant — without a
 * browser. `viewer/spikes/s10-console.ts` is what that is for: four views, every
 * trace in the tree, six points on the clock, checked for arithmetic that came
 * out as `NaN` and for markup that does not close.
 */
export const ConsoleContext = createContext<ConsoleState | null>(null);
const Ctx = ConsoleContext;

export function useConsole(): ConsoleState {
  const c = useContext(Ctx);
  if (!c) throw new Error('useConsole outside the console');
  return c;
}

/* ------------------------------------------------------------------ the URL */

function readUrl(): { run: string | null; view: View | null } {
  if (typeof window === 'undefined') return { run: null, view: null };
  const q = new URLSearchParams(window.location.search);
  const v = q.get('view');
  return {
    run: q.get('run'),
    view: VIEWS.includes(v as View) ? (v as View) : null,
  };
}

/**
 * Put the run and the view in the address bar, relatively.
 *
 * `?run=…&view=…` on whatever path the page is already at — never a written-down
 * one — because this app is served from a directory as often as from a root.
 */
function writeUrl(run: string | null, view: View, push: boolean): void {
  const q = new URLSearchParams(window.location.search);
  if (run) q.set('run', run);
  else q.delete('run');
  if (view === 'runs') q.delete('view');
  else q.set('view', view);
  // `at` names a moment in a run; it means nothing once a different one is open.
  const s = q.toString();
  const url = `${window.location.pathname}${s ? `?${s}` : ''}`;
  if (push) history.pushState(null, '', url);
  else history.replaceState(null, '', url);
}

/* -------------------------------------------------------------- the provider */

export function ConsoleProvider({ children }: { children: ReactNode }) {
  const [runs, setRuns] = useState<RunRef[]>([]);
  const [run, setRun] = useState<Run | null>(null);
  const [view, setView] = useState<View>('runs');
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasLab, setHasLab] = useState(false);
  const [watching, setWatching] = useState(0);
  /** What is being opened, so a slow fetch that has been superseded is dropped. */
  const wanted = useRef<string | null>(null);

  /**
   * One clock per run, and every view reads it.
   *
   * Rebuilt when the run changes rather than reset, because its pacing is
   * computed from that run's moments — the whole point of `lib/pace.ts` is that
   * the playhead slows down where *this* run is quick.
   */
  const clock = useMemo(
    () => (run ? new Clock(run.trace.duration, run.index.moments()) : null),
    [run],
  );
  useEffect(() => () => clock?.dispose(), [clock]);

  const ledger = useMemo(
    () => (run?.bill ? new LedgerModel(run.trace, run.bill) : null),
    [run],
  );

  const load = useCallback(async (ref: RunRef, next?: View) => {
    wanted.current = ref.name;
    setBusy(true);
    setError(null);
    try {
      const opened = await openUrl(ref.name, ref.href);
      if (wanted.current !== ref.name) return;
      setRun(opened);
      setView((was) => {
        const to = next ?? (was === 'runs' ? 'overview' : was);
        writeUrl(ref.name, to, true);
        return to;
      });
    } catch (e) {
      setError(String((e as Error).message));
    } finally {
      if (wanted.current === ref.name) setBusy(false);
    }
  }, []);

  const open = useCallback(
    async (name: string, next?: View) => {
      const ref = runs.find((r) => r.name === name);
      if (ref) await load(ref, next);
    },
    [runs, load],
  );

  const openAt = useCallback(
    async (name: string, href: string, next?: View) => {
      await load({ name, href }, next);
    },
    [load],
  );

  const openDropped = useCallback(async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const opened = await openFile(file);
      wanted.current = opened.name;
      setRun(opened);
      setView('overview');
      writeUrl(null, 'overview', true);
    } catch (e) {
      setError(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  }, []);

  const reload = useCallback(async () => {
    setRuns(await manifest());
  }, []);

  const nudge = useCallback(() => setWatching((n) => n + 1), []);

  const go = useCallback(
    (next: View) => {
      setView(next);
      writeUrl(run?.name ?? null, next, true);
      window.scrollTo(0, 0);
    },
    [run],
  );

  // Open on whatever the address bar asks for, and otherwise on the gallery with
  // the first of your own runs already loaded behind it — so the run section of
  // the nav is live and one click is a film, without landing you in a run you
  // did not pick.
  useEffect(() => {
    let live = true;
    manifest()
      .then(async (found) => {
        if (!live) return;
        setRuns(found);
        const { run: asked, view: askedView } = readUrl();
        const mine = found.filter((r) => r.from === 'yours');
        const ref =
          (asked ? found.find((r) => r.name === asked) : undefined) ?? mine[0] ?? found[0];
        if (!ref) return;
        const opened = await openUrl(ref.name, ref.href);
        if (!live) return;
        setRun(opened);
        const to = askedView ?? (asked ? 'overview' : 'runs');
        setView(to);
        writeUrl(ref.name, to, false);
      })
      .catch((e) => live && setError(String((e as Error).message ?? e)))
      .finally(() => live && setBusy(false));
    return () => {
      live = false;
    };
  }, []);

  // The back button is the one control nobody has to be taught.
  useEffect(() => {
    const onPop = () => {
      const { run: asked, view: askedView } = readUrl();
      setView(askedView ?? 'runs');
      if (asked && asked !== wanted.current) {
        const ref = runs.find((r) => r.name === asked);
        if (ref) void load(ref, askedView ?? 'overview');
      }
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [runs, load]);

  const value = useMemo<ConsoleState>(
    () => ({
      runs, run, ledger, clock, view, busy, error, hasLab, watching,
      go, open, openAt, openDropped, reload, setHasLab, setError, nudge,
    }),
    [runs, run, ledger, clock, view, busy, error, hasLab, watching,
     go, open, openAt, openDropped, reload, nudge],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/* ------------------------------------------------------------- the clock, read */

const NO_SUB = (): (() => void) => () => {};
const ZERO = (): number => 0;

/**
 * Where the playhead is, in reference milliseconds.
 *
 * Through `useSyncExternalStore` rather than state, because the clock ticks at
 * sixty frames a second and only the components that actually show a number need
 * to re-render at that rate — which is the same reason `lib/playback.ts` keeps it
 * outside React in the first place.
 *
 * The clock is the *server* snapshot too, and that is not a detail. A clock is a
 * plain object rather than anything a browser owns, so there is no reason for it
 * to read zero when nobody is looking — and while it did, every view rendered
 * outside a browser was silently drawn at `t = 0`, which made a check that
 * rendered them at six points on the clock a check of one point six times.
 */
export function useNow(): number {
  const { clock } = useConsole();
  return useSyncExternalStore(
    clock ? clock.subscribe : NO_SUB,
    clock ? clock.now : ZERO,
    clock ? clock.now : ZERO,
  );
}

const NO = () => false;

export function usePlaying(): boolean {
  const { clock } = useConsole();
  return useSyncExternalStore(
    clock ? clock.subscribe : NO_SUB,
    clock ? clock.isPlaying : NO,
    clock ? clock.isPlaying : NO,
  );
}
