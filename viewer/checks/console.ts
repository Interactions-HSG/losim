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
 * The designer is here for its shell only. Its substance is a simulation file
 * handed to the Java loader, and `s11-author.ts` is where that is answered —
 * against a real lab, because a fixture of "what the loader accepts" would be a
 * third opinion about a format two programs already have to agree on.
 *
 * What is *not* answered here is whether the film moves: it is drawn into a live
 * SVG by a layout search and a ResizeObserver, and that is a browser's job.
 * `dissaly dev viewer serve` is where it is answered. Its first frame is rendered all
 * the same, because a film page that throws before it has drawn anything is a
 * bug this can see.
 */
import { execFileSync } from 'node:child_process';
import { closeSync, openSync, readFileSync, readSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { createElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

import { RunIndex, revealText } from '../lib/frame.ts';
import { LedgerModel, type BillJson } from '../lib/ledger.ts';
import { Clock } from '../lib/playback.ts';
import type { Run, RunRef } from '../lib/runs.ts';
import { Trace } from '../lib/trace.ts';
import type { ConsoleState, View } from '../lib/console.tsx';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TRACES = resolve(HERE, '../../build/served');
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

/**
 * StyleX is a build-time system, and tsc does not know that.
 *
 * What tsc emits above still contains the `stylex.defineVars` and
 * `stylex.create` calls verbatim, and reaching one of those at runtime is an
 * error the library raises on purpose — styles must be compiled. So the same
 * Babel plugin the app builds with is run over the emitted JavaScript before
 * anything is imported. The check then renders what the app renders, rather
 * than a stand-in for it that could agree with a broken component.
 */
const { transformFileSync } = await import('@babel/core');
const stylexBabel = (await import('@stylexjs/babel-plugin')).default;
const js = (dir: string): string[] =>
  readdirSync(dir).flatMap((e) => {
    const at = join(dir, e);
    return statSync(at).isDirectory() ? js(at) : at.endsWith('.js') ? [at] : [];
  });
for (const file of js(BUILT)) {
  const out = transformFileSync(file, {
    babelrc: false,
    configFile: false,
    plugins: [[stylexBabel, { runtimeInjection: false, unstable_moduleResolution: { type: 'commonJS' } }]],
  });
  if (out?.code) writeFileSync(file, out.code);
}

const load = async (p: string) => import(pathToFileURL(join(BUILT, p)).href);
const { Cost } = await load('components/console/Cost.js');
const { Simulations } = await load('components/console/Simulations.js');
const { FilmView } = await load('components/console/FilmView.js');
const { Gallery } = await load('components/console/Gallery.js');
const { Overview } = await load('components/console/Overview.js');
const { Usage } = await load('components/console/Usage.js');
const { Shell } = await load('components/console/Shell.js');
const { ConsoleContext } = await load('lib/console.js');
const { Dataflow, same } = await load('components/Dataflow.js');
const { NodePanel } = await load('components/NodePanel.js');
const { LIGHT } = await load('lib/theme.js');


/** Six points, including both ends: nothing has happened, and everything has. */
const WHEN = [0, 0.07, 0.31, 0.5, 0.83, 1];
const VIEWS: [View, () => ReactNode][] = [
  ['runs', Gallery],
  ['simulations', Simulations],
  ['overview', Overview],
  ['film', FilmView],
  ['usage', Usage],
  ['cost', Cost],
];

let index: RunRef[] = [];
try {
  index = (JSON.parse(readFileSync(join(TRACES, 'index.json'), 'utf8')) as { runs: RunRef[] }).runs;
} catch {
  console.error('no traces yet — run `dissaly dev viewer traces` first');
  process.exit(1);
}

const mine = new Set(
  index.filter((r) => r.from !== 'gallery').map((r) => `${r.name}.json`),
);

/**
 * Is this trace a model of something bigger?
 *
 * From the head of the file rather than by parsing it: the index does not
 * record it, and a scaled run has to be in the sample whether or not the
 * gallery's every-fifth happens to land on one — otherwise the projection check
 * below is a check that can pass while checking nothing. `meta.scale` opens
 * around byte 110, so two kilobytes is a decision made without reading a
 * trace that may be tens of megabytes.
 */
function isModel(file: string): boolean {
  const fd = openSync(join(TRACES, file), 'r');
  try {
    const head = Buffer.alloc(2048);
    const n = readSync(fd, head, 0, head.length, 0);
    return /"scale"\s*:\s*\{/.test(head.subarray(0, n).toString('utf8'));
  } finally {
    closeSync(fd);
  }
}

const files = readdirSync(TRACES)
  .filter((f) => f.endsWith('.json') && !f.endsWith('.bill.json') && f !== 'index.json')
  .filter((f) => !only || f.includes(only))
  // Yours and the suite always, and a fifth of the gallery. The gallery is
  // eighty-one variations on a handful of designs; checking every one of them
  // turns a two-second check into a minute-long one and finds the same bugs.
  // Every scaled run as well, whichever fifth it falls in: a model is the one
  // kind of run whose whole answer is in a panel rather than on a chart.
  .filter((f, i) => all || only || mine.has(f) || i % 5 === 0 || isModel(f));

if (!files.length) {
  // An index with nothing in it is what a fresh export looks like — a build
  // writes one on purpose — so say the thing that fixes it rather than the thing
  // that is true.
  console.error(only ? `no traces match ${only}` : 'no traces yet — run `dissaly dev viewer traces` first');
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
  // `data-ruler`, not a class. The chart's classes are generated by StyleX now,
  // and matching one silently found nothing: every ruler compared equal to every
  // other, the check passed for any input, and it announced 0 labels while still
  // saying no ruler moved. An attribute the styling system does not own cannot
  // go the same way.
  return [...html.matchAll(/<text[^>]*data-ruler=""[^>]*>([^<]*)<\/text>/g)].map((m) => m[1]);
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
/** How many scaled runs had their projections held against the markup. */
let models = 0;
const say = (m: string) => {
  console.log(`  !! ${m}`);
  bad++;
};

/*
 * Who owns the address bar?
 *
 * The console keeps the run and the view in `?run=&view=`, and the film keeps
 * its own moment and framing beside them. They are separate components writing
 * one string, so the only thing stopping a collision is that they pick
 * different keys: a component below the console that also wrote `view` would
 * silently win the argument, so sitting on the film page would delete
 * `?view=film` and a shared link would open the gallery instead. Nothing would
 * render wrong, which is why no render check above can catch it.
 *
 * This reads the sources rather than a browser, because the invariant is about
 * which literal strings appear in them: no component below the console may
 * write a key the console owns.
 */
/*
 * Does the memo comparator see every prop?
 *
 * `Node` in Dataflow.tsx is memoised on its *appearance*, so it re-renders only
 * when its own comparator says two frames differ. A prop the comparator forgets
 * is therefore a prop that silently stops working: the film keeps drawing the
 * value it drew before, for as long as nothing else about that node changes.
 * Nominating a different key and watching every face refuse to move is the
 * symptom, and it looks exactly like a stale cache.
 *
 * No render check can catch it. `renderToStaticMarkup` builds a fresh tree
 * every time, so `memo` never runs and the comparator is never consulted —
 * which is the same blind spot the URL-key check below covers, and it is
 * covered the same way, by reading the source for the names that must appear.
 *
 * The general property rather than the prop that prompted it: every prop the
 * component destructures has to be compared, so the next one added fails here
 * instead of on somebody's screen. Handlers are exempt — a fresh closure each
 * render compares unequal every time, which would defeat the memo entirely.
 */
{
  const src = readFileSync(join(ROOT, 'components/Dataflow.tsx'), 'utf8');
  const taken = /const Node = memo\(\s*function Node\(\{([^}]*)\}/.exec(src);
  const cmp = /\n\s*\(a, b\) =>([\s\S]*?),\n\);/.exec(src.slice(taken?.index ?? 0));
  if (!taken || !cmp) say('Dataflow.tsx: cannot find Node\'s props or its memo comparator');
  else {
    const props = taken[1].split(',').map((x) => x.trim()).filter(Boolean);
    for (const prop of props) {
      if (/^on[A-Z]/.test(prop)) continue;
      // `m` is compared through same(a.m, b.m) rather than by name.
      const seen = prop === 'm'
        ? /same\(\s*a\.m\s*,\s*b\.m\s*\)/.test(cmp[1])
        : new RegExp(`a\\.${prop}\\s*===\\s*b\\.${prop}`).test(cmp[1]);
      if (!seen) say(`Dataflow.tsx: Node takes \`${prop}\` and its memo comparator ignores it`);
    }
  }
}

const OWNED = new Set(['view']);
for (const f of ['components/Film.tsx', 'components/console/FilmView.tsx']) {
  const src = readFileSync(join(ROOT, f), 'utf8');
  for (const m of src.matchAll(/searchParams\.(?:set|delete)\(\s*'([^']+)'/g)) {
    if (OWNED.has(m[1]!)) say(`${f} writes ?${m[1]}=, which the console owns`);
  }
}

/*
 * Does a revealed value reach the screen?
 *
 * `checks/stops.ts` proves the numbers arrive in the frame. A number in a frame
 * that nothing renders is exactly the state this feature was in for a release,
 * and no amount of checking the frame would have noticed — so the last step has
 * to be markup.
 *
 * On `t6`, frozen in this directory rather than whatever was last swept, at the
 * one instant that holds all three cases at once: `emitted` first lands on m0 at
 * 175.9 and m1 at 178.1, and not on m2 until 186.5, so at 180 there are nodes
 * with a value, nodes still to report, and nodes — master, r0, r1 — that never
 * report this key at all. The last two look identical in a frame and must not
 * look identical on a face: one is late, the other was never asked.
 */
{
  /** The loaded components are untyped `any` out of `.check`; give them props. */
  const el = (C: unknown, props: Record<string, unknown>) =>
    createElement(C as (p: Record<string, unknown>) => ReactNode, props);
  const trace = Trace.parse(gunzipSync(readFileSync(join(HERE, 'traces/t6.json.gz'))).toString('utf8'));
  const idx = new RunIndex(trace);
  const at = 180;
  const frame = idx.frameAt(at);
  const face = (show: string, dense = false) =>
    renderToStaticMarkup(el(Dataflow, { layout: idx.layout, frame, theme: LIGHT, show, dense }));
  const values = (html: string) =>
    [...html.matchAll(/data-reveal="[^"]*"[^>]*>(.*?)<\/text>/g)]
      .map((m) => m[1].replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim());

  const drawn = values(face('emitted'));
  const said = frame.nodes.filter((n) => n.revealed.some((r) => r.key === 'emitted'));
  const ready = said.filter((n) => n.revealed.some((r) => r.key === 'emitted' && r.value !== null));
  if (drawn.length !== said.length) {
    say(`t6 @180: ${said.length} nodes report emitted and ${drawn.length} faces carry it`);
  }
  if (said.length === frame.nodes.length || !ready.length || ready.length === said.length) {
    say('t6 @180 no longer holds a value, a wait and a silence at once — pick another instant');
  }
  for (const n of ready) {
    const want = revealText(n.revealed.find((r) => r.key === 'emitted')!.value!);
    if (!drawn.some((d) => d.endsWith(want))) say(`t6 @180: ${n.name} reports ${want} and no face says it`);
  }
  // A node still to report wears a dash; a node that never reports wears
  // nothing. The glyph is pinned on purpose: it is the mark that separates
  // "asked, not yet answered" from "never asked", which is the distinction this
  // whole feature exists to draw. If it changes, that is a decision about what
  // the film says, and it should have to be made here rather than noticed
  // later — so this goes red and a human chooses, instead of the assertion
  // quietly following whatever the file happens to contain. It was an em dash
  // until the prose pass of 2026-09-10 and is a hyphen by that decision.
  if (!drawn.some((d) => d.endsWith('-'))) {
    const marks = [...new Set(drawn.filter((d) => !/\d$/.test(d)).map((d) => d.slice(-1)))];
    say(`t6 @180: the mark for a node that has not reported is ${marks.length
      ? `"${marks.join('", "')}" and not "-"` : 'gone'} — decide, then update this line`);
  }
  if (values(face('')).length) say('a face draws a revealed value with nothing nominated');
  if (!values(face('emitted', true)).length) say('a dense face drops the value entirely');

  // The panel: the node's own values, and the sentence a node without any gets.
  const panel = (name: string) =>
    renderToStaticMarkup(el(NodePanel, {
      trace, m: frame.nodes.find((n) => n.name === name), t: at,
      pinned: true, onPin: () => {}, onClose: () => {},
    }));
  const m0 = panel('m0');
  if (!m0.includes('data-reveals')) say('the node panel has no revealed values section');
  if (!/data-reveal="emitted">\s*5\s*</.test(m0)) say('the node panel does not list m0 emitted 5');
  if (!panel('master').includes('data-reveals')) say('a node that reveals nothing loses the section entirely');

  // The memo comparator. A node whose value moved and whose appearance is
  // otherwise identical is the only thing that changes on most frames, so a
  // comparator blind to it freezes the number on the face while the film plays.
  const one = frame.nodes.find((n) => n.name === 'm0')!;
  if (same(one, { ...one, revealed: [{ ...one.revealed[0], value: 999 }] })) {
    say('Dataflow.same() calls two nodes identical when a revealed value changed');
  }
}

/*
 * Does a scaled run's projection reach the screen?
 *
 * A model executes a workload small enough to fit on the machine in front of
 * you and reports the one it stands for. Every chart on the usage page draws the
 * first, because that is what the trace's channels hold — so if the panel that
 * carries the second renders nothing, the page is left saying `0.04` about a
 * cluster declared at forty-eight thousand frames, and every render check above
 * passes while it does. That was the state this feature shipped in, and no
 * amount of checking that a view does not throw would have found it.
 *
 * Three questions, and the second is the one with teeth. **Is the panel there?**
 * **Is the value in it the projected one and not the measured one?** — a panel
 * that renders the observed figure under a "at full size" heading is worse than
 * no panel. And **does a number carry its unit?**, which is where this started.
 *
 * Drawn at the end of the clock, once: a projection does not accrue, so there is
 * no instant of the run at which it is a different number.
 */
{
  const { size } = await load('lib/units.js');
  const scaled = files.filter(isModel);
  models = scaled.length;
  if (!models) {
    say('no scaled trace among those checked, so nothing below was actually answered');
  }
  for (const file of scaled) {
    const run = open(file);
    const model = run.trace.scaled!;
    const clock = new Clock(run.trace.duration);
    clock.seek(run.trace.duration);
    const draw = (view: View, C: unknown) =>
      renderToStaticMarkup(
        createElement(
          ConsoleContext.Provider,
          { value: state(run, clock, view) },
          createElement(C as () => ReactNode),
        ),
      );
    const usage = draw('usage', Usage);
    const flat = usage.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');

    if (!flat.includes(model.fullUnits.toLocaleString())) {
      say(`${run.name}/usage: nothing on the page says what size it is a model of`);
    }
    for (const p of model.projections) {
      if (!flat.includes(p.resource)) {
        say(`${run.name}/usage: ${p.resource} was fitted and does not appear`);
        continue;
      }
      // The condition, verbatim. A number that dropped the assumption it was
      // produced under is a different claim from the one the engine made.
      const why = p.assumed ?? p.refused;
      if (why && !flat.includes(why.slice(0, 40))) {
        say(`${run.name}/usage: ${p.resource} is shown without the condition it came with`);
      }
      if (p.projected === null) continue;
      // Formatted through the same function the panel formats with, because the
      // question is whether the value arrived, not whether it can be written
      // down — the same claim `data-reveal` makes about a face.
      const want = p.resource.endsWith('Mb') ? size(p.projected) : null;
      if (want && !flat.includes(want)) {
        say(`${run.name}/usage: ${p.resource} projects ${want} and no cell says it`);
      }
      // And it must not be the small number wearing the big number's heading.
      const observed = p.resource.endsWith('Mb') ? size(p.observed) : null;
      if (want && observed && want === observed && p.projected !== p.observed) {
        say(`${run.name}/usage: ${p.resource} reads the same at both sizes — the unit is too coarse to tell them apart`);
      }
    }

    // The unit, at the head of the axis rather than only in the `aria-label`,
    // which is where it was for a release: available to a screen reader and to
    // nobody looking at the page. Only where there are charts to put one on — a
    // run recorded with telemetry off has no channels and draws none, and its
    // projections are still owed.
    if (run.trace.channelNames().length) {
      const units = [...usage.matchAll(/<text[^>]*text-anchor="end"[^>]*>([A-Z]?B|%)<\/text>/g)];
      if (!units.length) say(`${run.name}/usage: no chart says what its numbers are counted in`);
    }

    if (run.bill?.projected) {
      const cost = draw('cost', Cost).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
      if (!cost.includes(String(run.bill.projected.cost.toFixed(2)))
          && !cost.includes(String(run.bill.projected.cost.toFixed(4)))) {
        say(`${run.name}/cost: the bill carries a projected total and the page does not show it`);
      }
      for (const [what] of Object.entries(run.bill.projected.unpriceable ?? {})) {
        if (!cost.includes(what)) {
          say(`${run.name}/cost: "${what}" is missing from the projected bill with no word about why`);
        }
      }
    }
    clock.dispose();
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
  const clock = new Clock(run.trace.duration);

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
  + ` · ${renders} renders · ${charts} axis labels held still`
  + ` · ${models} of them models, projections held against the markup`,
);
if (bad) {
  console.error(`\n${bad} problem(s)`);
  process.exit(1);
}
console.log('console: every view renders, and no ruler moves under the clock');
