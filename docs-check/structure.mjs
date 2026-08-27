/**
 * The site's own shape: every page reachable, every link resolvable.
 *
 *     node docs-check/structure.mjs docs
 *
 * Separate from the leak rules because it answers a different question. Those
 * ask whether a page says something it must not; this asks whether the site
 * holds together — a page in the navigation with no file behind it is a 404,
 * and a file with no navigation entry is a page nobody will ever find.
 *
 * Ported from Python, and that is the whole of why it changed: this repository
 * is Java, TypeScript and the shell, and a sixty-line guard was the last reason
 * to need a fourth language installed to check the docs.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.argv[2];

/** Every page named anywhere under `navigation`, however it is nested. */
const nav = [];
function walk(node) {
  if (Array.isArray(node)) {
    for (const item of node) walk(item);
  } else if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) {
      if (key === 'pages' && Array.isArray(value)) {
        for (const page of value) {
          if (typeof page === 'string') nav.push(page);
          else walk(page);
        }
      } else {
        walk(value);
      }
    }
  }
}
walk(JSON.parse(readFileSync(join(root, 'docs.json'), 'utf8')).navigation);

const onDisk = new Set();
(function scan(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) scan(path);
    else if (entry.endsWith('.mdx')) onDisk.add(relative(root, path).slice(0, -4));
  }
})(root);

const problems = [];
for (const page of nav) {
  if (!onDisk.has(page)) problems.push(`in the navigation with no file behind it: ${page}.mdx`);
}
for (const page of [...onDisk].filter((p) => !nav.includes(p)).sort()) {
  problems.push(`a page nobody can reach — not in the navigation: ${page}.mdx`);
}
for (const page of new Set(nav.filter((p) => nav.filter((q) => q === p).length > 1))) {
  problems.push(`listed twice in the navigation: ${page}`);
}

// Internal links. An absolute /path must be a page; an anchor is not checked,
// because a heading's slug is Mintlify's business rather than ours.
const LINK = /\]\((\/[^)\s#]*)(#[^)\s]*)?\)/g;
for (const page of [...onDisk].sort()) {
  const lines = readFileSync(join(root, `${page}.mdx`), 'utf8').split('\n');
  lines.forEach((line, i) => {
    for (const [, target] of line.matchAll(LINK)) {
      if (onDisk.has(target.replace(/\/+$/, '').replace(/^\/+/, ''))) continue;
      problems.push(`${page}.mdx:${i + 1}: link to ${target}, which is not a page`);
    }
  });
}

if (problems.length) {
  console.log('== the site\'s shape ==');
  for (const p of problems) console.log('  ' + p);
  console.log(`\n${problems.length} problem(s).`);
  process.exit(1);
}
console.log(`== the site's shape ==\n  ${nav.length} pages, all reachable, every internal link resolves.`);
