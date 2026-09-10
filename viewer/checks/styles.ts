/**
 * does the export's markup name a class the export's CSS never defines?
 *
 *   node viewer/checks/styles.ts
 *
 * StyleX is compiled twice on the way to `viewer/out`: once by the bundler,
 * which writes the class names into the markup, and once by the PostCSS plugin,
 * which runs Babel again over the same sources to collect the atomic CSS. The
 * two are separate passes over separate copies of the same input, and nothing
 * between them checks that they agreed.
 *
 * They have already disagreed once. An incremental build served the layout a
 * cached compile of `lib/themes.stylex.ts` while PostCSS compiled it afresh, so
 * `<body>` was dressed in `xooe0l1` and the stylesheet defined `.xeusip0`. The
 * page rendered, every check passed, and the university's colours were simply
 * gone — a whole theme lost to a stale cache, with no error anywhere.
 *
 * That is the shape of failure this catches: not a wrong colour, which somebody
 * has to have an opinion about, but a class that is *named and undefined*, which
 * is a fact. Every class in the export comes from `stylex.props` — `lib/text.tsx`
 * makes writing one by hand a type error — so a name with no rule behind it is
 * always a build that came apart, never a style somebody meant.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const OUT = process.argv[2] ? resolve(process.argv[2]) : join(ROOT, 'viewer/out');

/** Every file under a directory whose name ends one of these ways. */
function under(dir: string, ends: string[]): string[] {
  const out: string[] = [];
  const walk = (at: string) => {
    for (const name of readdirSync(at)) {
      const path = join(at, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (ends.some((e) => name.endsWith(e))) out.push(path);
    }
  };
  walk(dir);
  return out;
}

/**
 * The classes a stylesheet defines.
 *
 * A class selector is a dot and a name, and the only other dots in CSS are in
 * numbers and in strings — neither of which can start with a letter, an
 * underscore or a dash, which is what the name pattern insists on.
 */
function defined(css: string): Set<string> {
  const out = new Set<string>();
  for (const m of css.matchAll(/\.(-?[A-Za-z_][\w-]*)/g)) out.add(m[1]);
  return out;
}

/** The classes a page puts on its elements, and the element each one came from. */
function used(html: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const m of html.matchAll(/<([a-zA-Z][\w-]*)[^>]*?\sclass="([^"]*)"/g)) {
    for (const c of m[2].split(/\s+/)) if (c && !out.has(c)) out.set(c, m[1]);
  }
  return out;
}

// ------------------------------------------------------------------ the check

const pages = under(OUT, ['.html']);
const sheets = under(OUT, ['.css']);

if (!pages.length || !sheets.length) {
  console.error(`S8  nothing to read in ${OUT} — dissaly dev viewer build first`);
  process.exit(1);
}

const have = new Set<string>();
for (const sheet of sheets) for (const c of defined(readFileSync(sheet, 'utf8'))) have.add(c);

// Next writes its own not-found page with a `<style>` element in the head, so a
// stylesheet is not the only place a rule can live. Read those too — a class
// defined inline is defined.
for (const page of pages) {
  for (const m of readFileSync(page, 'utf8').matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) {
    for (const c of defined(m[1])) have.add(c);
  }
}

const orphans: string[] = [];
let names = 0;
for (const page of pages) {
  const here = used(readFileSync(page, 'utf8'));
  names += here.size;
  for (const [c, tag] of here) {
    if (!have.has(c)) orphans.push(`  ${page.slice(OUT.length + 1)}  <${tag} class="… ${c} …">`);
  }
}

console.log(`S8  ${pages.length} pages, ${names} class names, ${have.size} defined in ${sheets.length} stylesheets`);
if (orphans.length) {
  console.log(`\n${orphans.length} named and undefined:\n${orphans.slice(0, 20).join('\n')}`);
  if (orphans.length > 20) console.log(`  ... and ${orphans.length - 20} more`);
  console.log(
    '\nthe two StyleX passes disagreed. Delete viewer/.next and build again:'
    + '\n  rm -rf viewer/.next && bin/dissaly dev viewer build',
  );
  process.exitCode = 1;
} else {
  console.log('    every class the markup names has a rule behind it');
}
