/**
 * do the console's views survive every trace, at every point on the clock?
 *
 *   node viewer/checks/console.ts [substring]
 *
 * Three questions a browser would answer slowly and this answers in a second.
 *
 * **Does it render at all?** Every view, over every trace in the tree, at six
 * points on the clock including both ends. A view that throws at `t = 0` because
 * a run has not started yet is the bug this catches, and it is the one you would
 * otherwise find in a lecture.
 *
 * **Does the arithmetic come out?** `NaN`, `undefined` and `[object Object]` in
 * the output are all the same mistake wearing different clothes: a number that
 * was divided by a run that had not begun. Grepping the rendered markup for them
 * is cruder than a unit test and finds more, because it is looking at what
 * somebody would actually read.
 *
 * **Does the ruler hold still?** The whole claim of the console is that dragging
 * the clock moves the drawing and never the scale under it. So the axis labels
 * are pulled out of the rendered SVG at each instant and compared: if a chart's
 * ticks differ between two points on the clock, the chart is lying about its own
 * shape and the trend you read off it is not there.
 *
 * The designer is here for its shell only. Its substance is a scenario file
 * handed to the Java loader, and `s11-author.ts` is where that is answered —
 * against a real lab, because a fixture of "what the loader accepts" would be a
 * third opinion about a format two programs already have to agree on.
 *
 * What is *not* answered here is whether the film moves: it is drawn into a live
 * SVG by a layout search and a ResizeObserver, and that is a browser's job.
 * `./viewer/serve.sh` is where it is answered. Its first frame is rendered all
 * the same, because a film page that throws before it has drawn anything is a
 * bug this can see.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, rmSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { createElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

import { RunIndex } from '../lib/frame.ts';
import { LedgerModel, type BillJson } from '../lib/ledger.ts';
import { Clock } from '../lib/playback.ts';
import type { Run, RunRef } from '../lib/runs.ts';
import { Trace } from '../lib/trace.ts';
import type { ConsoleState, View } from '../lib/console.tsx';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TRACES = resolve(HERE, '../../build/viewer/traces');
const arg = process.argv[2] ?? '';
/** Every trace, rather than a sample of the gallery. Two seconds against a minute. */
const all = arg === '--all';
const only = all ? '' : arg;

/**
 * The views, compiled.
 *
 * Node strips types out of `.ts` on its own but has never heard of JSX, and the
 * views are JSX. So they are emitted once into a scratch directory with the same
 * compiler the project already type-checks with — which makes this check a type
 * check as well, for free, and means there is no second toolchain to keep in
 * step with the first.
 */
const BUILT = join(ROOT, '.check');
rmSync(BUILT, { recursive: true, force: true });
try {
  execFileSync(
    'node',
    [join(ROOT, 'node_modules/typescript/bin/tsc'), '-p', join(ROOT, 'tsconfig.json'),
     '--noEmit', 'false', '--outDir', BUILT, '--declaration', 'false',
     '--sourceMap', 'false', '--rewriteRelativeImportExtensions'],
    { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] },
  );
} catch (e) {
  const said = String((e as { stdout?: Buffer }).stdout ?? '').trim();
  if (said) {
    console.error('the console does not compile:\n' + said);
    process.exit(1);
  }
}
const load = async (p: string) => import(pathToFileURL(join(BUILT, p)).href);
const { Cost } = await load('components/console/Cost.js');
const { Scenarios } = await load('components/console/Scenarios.js');
const { FilmView } = await load('components/console/FilmView.js');
const { Gallery } = await load('components/console/Gallery.js');
const { Overview } = await load('components/console/Overview.js');
const { Usage } = await load('components/console/Usage.js');
const { Shell } = await load('components/console/Shell.js');
const { ConsoleContext } = await load('lib/console.js');


/** Six points, including both ends: nothing has happened, and everything has. */
const WHEN = [0, 0.07, 0.31, 0.5, 0.83, 1];
const VIEWS: [View, () => ReactNode][] = [
  ['runs', Gallery],
  ['scenarios', Scenarios],
  ['overview', Overview],
  ['film', FilmView],
  ['usage', Usage],
  ['cost', Cost],
];

let index: RunRef[] = [];
try {
  index = (JSON.parse(readFileSync(join(TRACES, 'index.json'), 'utf8')) as { runs: RunRef[] }).runs;
} catch {
  console.error('no traces yet — run ./viewer/traces.sh first');
  process.exit(1);
}

const mine = new Set(
  index.filter((r) => r.from !== 'gallery').map((r) => `${r.name}.json`),
);
const files = readdirSync(TRACES)
  .filter((f) => f.endsWith('.json') && !f.endsWith('.bill.json') && f !== 'index.json')
  .filter((f) => !only || f.includes(only))
  // Yours and the suite always, and a fifth of the gallery. The gallery is
  // eighty-one variations on a handful of designs; checking every one of them
  // turns a two-second check into a minute-long one and finds the same bugs.
  .filter((f, i) => all || only || mine.has(f) || i % 5 === 0);

if (!files.length) {
  // An index with nothing in it is what a fresh export looks like — `export.sh`
  // writes one on purpose — so say the thing that fixes it rather than the thing
  // that is true.
  console.error(only ? `no traces match ${only}` : 'no traces yet — run ./viewer/traces.sh first');
  process.exit(1);
}

function open(file: string): Run {
  const name = file.replace(/\.json$/, '');
  const trace = Trace.parse(readFileSync(join(TRACES, file), 'utf8'));
  let bill: BillJson | null = null;
  try {
    bill = JSON.parse(readFileSync(join(TRACES, `${name}.bill.json`), 'utf8')) as BillJson;
  } catch {
    // A trace somebody was sent has no bill beside it, and the money is simply absent.
  }
  return { name, trace, index: new RunIndex(trace), bill };
}

function state(run: Run, clock: Clock, view: View): ConsoleState {
  const noop = async () => {};
  return {
    runs: index,
    run,
    ledger: run.bill ? new LedgerModel(run.trace, run.bill) : null,
    clock,
    view,
    busy: false,
    error: null,
    hasLab: false,
    watching: 0,
    nudge: () => {},
    building: null,
    startBuild: noop,
    go: () => {},
    open: noop,
    openAt: noop,
    openDropped: noop,
    reload: noop,
    setHasLab: () => {},
    setError: () => {},
  };
}

/** The y-axis labels of every chart on a page, in order. The ruler, in other words. */
function rulers(html: string): string[] {
  return [...html.matchAll(/<text class="tick"[^>]*text-anchor="end"[^>]*>([^<]*)<\/text>/g)].map(
    (m) => m[1],
  );
}

const VOID = new Set(['br', 'hr', 'img', 'input', 'meta', 'link', 'source', 'use', 'path',
  'circle', 'rect', 'line', 'polyline', 'polygon', 'ellipse', 'stop', 'col', 'area']);

/** Does every tag close, and in the order it opened? */
function unbalanced(html: string): string | null {
  const stack: string[] = [];
  for (const m of html.matchAll(/<(\/?)([a-zA-Z][\w-]*)([^>]*?)(\/?)>/g)) {
    const [, close, tag, , self] = m;
    const t = tag.toLowerCase();
    if (VOID.has(t) || self) continue;
    if (close) {
      const top = stack.pop();
      if (top !== t) return `</${t}> closes <${top ?? 'nothing'}>`;
    } else stack.push(t);
  }
  return stack.length ? `never closed: ${stack.slice(-3).join(', ')}` : null;
}

let bad = 0;
let renders = 0;
let charts = 0;
const say = (m: string) => {
  console.log(`  !! ${m}`);
  bad++;
};

/*
 * Who owns the address bar?
 *
 * The console keeps the run and the view in `?run=&view=`, and the film keeps
 * its own moment and framing beside them. They are separate components writing
 * one string, so the only thing stopping them is that they picked different
 * keys — and for one commit they had not: `Film` read and wrote `view` too, so
 * sitting on the film page deleted `?view=film` and a shared link opened the
 * gallery instead. Nothing rendered wrong, which is why every render check
 * above passed through it.
 *
 * This reads the sources rather than a browser, because the invariant is about
 * which literal strings appear in them: no component below the console may
 * write a key the console owns.
 */
const OWNED = new Set(['view']);
for (const f of ['components/Film.tsx', 'components/console/FilmView.tsx']) {
  const src = readFileSync(join(ROOT, f), 'utf8');
  for (const m of src.matchAll(/searchParams\.(?:set|delete)\(\s*'([^']+)'/g)) {
    if (OWNED.has(m[1]!)) say(`${f} writes ?${m[1]}=, which the console owns`);
  }
}

for (const file of files) {
  let run: Run;
  try {
    run = open(file);
  } catch (e) {
    say(`${file}: will not open — ${(e as Error).message}`);
    continue;
  }
  const clock = new Clock(run.trace.duration, run.index.moments());

  for (const [view, Component] of VIEWS) {
    const seen = new Map<number, string>();
    for (const f of WHEN) {
      const at = run.trace.duration * f;
      clock.seek(at);
      let html: string;
      try {
        // Inside the shell, not on its own: the rail, the clock and the
        // scrubber are markup too, and the bar is the one thing on the page
        // that is redrawn sixty times a second.
        html = renderToStaticMarkup(
          createElement(
            ConsoleContext.Provider,
            { value: state(run, clock, view) },
            createElement(
              Shell as (p: { children: ReactNode }) => ReactNode,
              { children: createElement(Component as () => ReactNode) },
            ),
          ),
        );
      } catch (e) {
        say(`${run.name}/${view} @${Math.round(at)}: threw — ${(e as Error).message}`);
        break;
      }
      renders++;

      const junk = html.match(/.{0,60}(NaN|undefined|\[object Object\]).{0,60}/);
      if (junk) say(`${run.name}/${view} @${Math.round(at)}: ${junk[0].replace(/\s+/g, ' ')}`);
      const broke = unbalanced(html);
      if (broke) say(`${run.name}/${view} @${Math.round(at)}: ${broke}`);

      // The ruler, held against the first instant this view was drawn at.
      const r = rulers(html).join('|');
      if (view !== 'runs') {
        const was = seen.get(0);
        if (was === undefined) {
          seen.set(0, r);
          charts += rulers(html).length;
        } else if (r !== was) {
          say(`${run.name}/${view}: the axis moved at ${Math.round(at)} refMs\n     was ${was}\n     now ${r}`);
        }
      }
    }
  }
  clock.dispose();
}

console.log(
  `\n${files.length} traces${all ? '' : ' (yours, the suite, and a fifth of the gallery — --all for every one)'}`
  + ` · ${renders} renders · ${charts} axis labels held still`,
);
if (bad) {
  console.error(`\n${bad} problem(s)`);
  process.exit(1);
}
console.log('console: every view renders, and no ruler moves under the clock');
