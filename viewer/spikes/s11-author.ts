/**
 * S11 — does what the designer writes load?
 *
 *   node viewer/spikes/s11-author.ts
 *
 * The console composes a scenario in TypeScript and the lab loads it in Java.
 * That is two programs agreeing about a file format, which is exactly the kind
 * of agreement that holds until somebody adds a field. A pool of one must not
 * write a `count:`, or its machine is called `master0` and every fault aimed at
 * `master` stops resolving; a duration must say what kind of time it is; a retry
 * names a *dotted* method and not the one with a slash in it that appears in
 * every stack trace.
 *
 * So this starts a real lab, hands the real writer's output to the real loader
 * through the real endpoint, and believes the answer. There is no fixture of
 * "what the loader accepts" here, because a fixture is a third opinion.
 *
 * It also checks the refusals, which matter as much: a console that could write
 * a file the run would then reject has moved the error somewhere worse.
 */
import { spawn } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expand, firstDraft, toYaml, type Draft } from '../lib/author.ts';
import type { Palette } from '../lib/lab.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const JAR = join(ROOT, 'build/losim.jar');

/**
 * A lab with nothing in it but a name.
 *
 * Writing a scenario needs no compiled code — the loader reads a file, and what
 * it checks is the file. So the fixture is a jar and a folder, and this check
 * costs a second rather than a protoc run.
 */
function lab(): string {
  const dir = mkdtempSync(join(tmpdir(), 'losim-author-'));
  mkdirSync(join(dir, 'lib'), { recursive: true });
  mkdirSync(join(dir, 'sys/scenarios'), { recursive: true });
  copyFileSync(JAR, join(dir, 'lib/losim.jar'));
  writeFileSync(join(dir, 'sys/scenarios/main.yaml'),
    'job: Nothing\nmachines:\n  only: { instance: m5.large, zone: eu-central-1a }\n');
  return dir;
}

/** The catalogues the lab would have sent, as this check does not compile anything. */
const PALETTE: Palette = {
  system: 'sys',
  compiled: true,
  jobs: ['lab.Elastic', 'lab.WordCount'],
  services: [
    { cls: 'lab.Combiner', service: 'Worker', qualified: 'lab.Worker',
      methods: [{ name: 'Map', idempotent: true }, { name: 'Reduce', idempotent: true },
                { name: 'Note', idempotent: false }] },
    { cls: 'lab.Reducer', service: 'Shuffler', qualified: 'lab.Shuffler',
      methods: [{ name: 'Fold', idempotent: true }] },
  ],
  other: 12,
  instances: [
    { name: 'm5.large', family: 'm5', vcpu: 2, memoryMb: 8192, storageGb: 32, burstable: false, onDemandPerHour: 0.115 },
    { name: 'c5.large', family: 'c5', vcpu: 2, memoryMb: 4096, storageGb: 32, burstable: false, onDemandPerHour: 0.102 },
    { name: 't3.small', family: 't3', vcpu: 2, memoryMb: 2048, storageGb: 16, burstable: true, onDemandPerHour: 0.024 },
  ],
  regions: [
    { name: 'eu-central-1', provider: 'aws', continent: 'europe', where: 'Frankfurt',
      zones: ['eu-central-1a', 'eu-central-1b', 'eu-central-1c'] },
    { name: 'ap-northeast-1', provider: 'aws', continent: 'asia', where: 'Tokyo',
      zones: ['ap-northeast-1a', 'ap-northeast-1b', 'ap-northeast-1c'] },
    { name: 'switzerlandnorth', provider: 'azure', continent: 'europe', where: 'Zurich',
      zones: ['switzerlandnorth-1', 'switzerlandnorth-2', 'switzerlandnorth-3'] },
  ],
  scenarios: ['main.yaml'],
};

const base = firstDraft(PALETTE);

/** Every shape of scenario the form can compose, and what each of them is for. */
const DRAFTS: [string, Draft][] = [
  ['the form as it opens', base],
  ['a pool of one, so its machine keeps the pool’s name', {
    ...base, name: 'single',
    pools: [{ name: 'master', count: 1, instance: 'm5.large', zones: ['eu-central-1a'], runs: [] }],
  }],
  ['a pool dealt over three zones', {
    ...base, name: 'spread',
    pools: [
      { name: 'master', count: 1, instance: 'm5.large', zones: ['eu-central-1a'], runs: [] },
      { name: 'workers', count: 6, instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b', 'eu-central-1c'], runs: ['lab.Combiner'] },
    ],
  }],
  ['across an ocean, and in the other cloud', {
    ...base, name: 'far',
    pools: [
      { name: 'master', count: 1, instance: 'm5.large', zones: ['eu-central-1a'], runs: [] },
      { name: 'edge', count: 1, instance: 't3.small', zones: ['ap-northeast-1a'], runs: ['lab.Combiner'] },
      { name: 'vault', count: 2, instance: 'm5.large', zones: ['switzerlandnorth-1'], runs: ['lab.Reducer'] },
    ],
  }],
  ['a machine killed, and one that never comes back', {
    ...base, name: 'killed',
    kills: [
      { atRefMs: 300, target: 'workers1', restartAfterRefMs: 2000 },
      { atRefMs: 900, target: 'workers2', restartAfterRefMs: 0 },
    ],
  }],
  ['a standing rate of every kind', {
    ...base, name: 'chaotic',
    chaos: [
      { kind: 'freeze', everyRefMs: 700, among: 'workers', forRefMs: 150, factor: 2 },
      { kind: 'kill', everyRefMs: 2000, among: 'workers', forRefMs: 0, factor: 1 },
      { kind: 'degrade', everyRefMs: 400, among: 'coordinator', forRefMs: 300, factor: 4 },
    ],
  }],
  ['retries, safe and deliberately not', {
    ...base, name: 'retried',
    retries: [
      { method: 'lab.Worker.Map', attempts: 3, backoffRefMs: 40, unsafe: false },
      { method: 'lab.Worker.Note', attempts: 2, backoffRefMs: 0, unsafe: true },
    ],
  }],
  ['all of it at once', {
    ...base, name: 'everything', seed: 9, expectedRunRefSeconds: 45,
    pools: [
      { name: 'master', count: 1, instance: 'm5.large', zones: ['eu-central-1a'], runs: [] },
      { name: 'workers', count: 4, instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b'], runs: ['lab.Combiner', 'lab.Reducer'] },
      { name: 'edge', count: 1, instance: 't3.small', zones: ['ap-northeast-1a'], runs: ['lab.Combiner'] },
    ],
    kills: [{ atRefMs: 300, target: 'workers1', restartAfterRefMs: 2000 }],
    chaos: [{ kind: 'freeze', everyRefMs: 700, among: 'workers', forRefMs: 150, factor: 2 }],
    retries: [{ method: 'lab.Worker.Map', attempts: 3, backoffRefMs: 40, unsafe: false }],
  }],
];

/** And the ones that must be refused, with the loader's own words. */
const REFUSED: [string, string][] = [
  ['an instance type that does not exist',
   'job: J\nmachines:\n  a: { instance: m5.enormous, zone: eu-central-1a }\n'],
  ['a fault aimed at a machine that is not there',
   'job: J\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\nfaults:\n  - { at: 1 refMs, kill: ghost }\n'],
  ['a duration that does not say what kind of time it is',
   'job: J\nexpectedRun: 900\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\n'],
  ['a key that is a typo for a real one',
   'job: J\nmachiens:\n  a: { instance: m5.large, zone: eu-central-1a }\n'],
];

/**
 * A port nothing else is on.
 *
 * Not a number picked out of the air: `losim serve` answers a taken port by
 * saying it is already running and staying quiet, so a guess that collided
 * would leave this check talking to somebody else's lab and believing it.
 */
async function freePort(): Promise<number> {
  return new Promise((ok, no) => {
    const s = createServer();
    s.on('error', no);
    s.listen(0, '127.0.0.1', () => {
      const a = s.address();
      const p = typeof a === 'object' && a ? a.port : 0;
      s.close(() => ok(p));
    });
  });
}

const dir = lab();
const port = await freePort();
const server = spawn('java', ['-cp', JAR, 'losim.cli.Main', 'serve',
  '--root', dir, '--runs', join(dir, 'runs'), '--port', String(port), '--no-open'],
  { stdio: 'ignore' });

let bad = 0;
const say = (m: string) => { console.log(`  !! ${m}`); bad++; };

async function post(body: unknown): Promise<{ status: number; body: Record<string, string> }> {
  const res = await fetch(`http://127.0.0.1:${port}/api/scenario`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  return { status: res.status, body: (await res.json()) as Record<string, string> };
}

try {
  // The server is a JVM: give it a moment, and say so if it never arrives.
  let up = false;
  for (let i = 0; i < 100 && !up; i++) {
    try {
      await fetch(`http://127.0.0.1:${port}/api/systems`);
      up = true;
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  if (!up) {
    console.error(`no lab on ${port} — is build/losim.jar built?`);
    process.exit(1);
  }

  console.log('what the designer writes, handed to the loader that runs it\n');
  for (const [what, draft] of DRAFTS) {
    const yaml = toYaml(draft);
    const r = await post({ system: 'sys', name: draft.name, yaml });
    if (r.status !== 200) {
      say(`${what}: refused — ${r.body.error}`);
      console.log(yaml.split('\n').map((l) => `        ${l}`).join('\n'));
      continue;
    }
    console.log(`  ok  ${what.padEnd(48)} ${expand(draft).length} machines, ${yaml.split('\n').length} lines`);
  }

  console.log('\nand what it must refuse\n');
  for (const [what, yaml] of REFUSED) {
    const r = await post({ system: 'sys', name: 'refused', yaml });
    if (r.status === 200) { say(`${what}: was accepted, and should not have been`); continue; }
    const said = String(r.body.error ?? '');
    if (!/^refused\.yaml:\d+:/.test(said)) {
      say(`${what}: refused without a line number — "${said}"`);
      continue;
    }
    console.log(`  ok  ${what}\n      ${said.slice(0, 120)}`);
  }

  // A name is a file name. This is a web page writing into somebody's project.
  for (const name of ['../../escape', 'a/b', '.hidden']) {
    const r = await post({ system: 'sys', name, yaml: 'job: J\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\n' });
    if (r.status === 200) say(`'${name}' was accepted as a scenario name`);
  }
  console.log('\n  ok  a scenario name cannot walk out of its own system');
} finally {
  server.kill('SIGKILL');
  rmSync(dir, { recursive: true, force: true });
}

if (bad) { console.error(`\n${bad} problem(s)`); process.exit(1); }
console.log('\nauthor: everything the designer can compose is a file the run will accept');
