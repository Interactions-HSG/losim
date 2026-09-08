/**
 * does what the console writes load?
 *
 *   node viewer/checks/author.ts
 *
 * The console composes a simulation in TypeScript and the lab loads it in Java.
 * That is two programs agreeing about a file format, which is exactly the kind
 * of agreement that holds until somebody adds a field. A pool of one must not
 * write a `count:`, or its node is called `master0` and every override aimed at
 * `master` stops resolving; a duration must say what kind of time it is; a retry
 * names a *dotted* method and not the one with a slash in it that appears in
 * every stack trace.
 *
 * So this starts a real lab, hands the real writer's output to the real loader
 * through the real endpoint, and believes the answer. There is no fixture of
 * "what the loader accepts" here, because a fixture is a third opinion.
 *
 * It also checks the refusals, which matter as much: a console that could write
 * a file the simulation would then reject has moved the error somewhere worse.
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { firstDraft, nodes, toYaml, PAIRED, type Draft, type Failure, type Runs } from '../lib/author.ts';
import type { Palette } from '../lib/lab.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const JAR = join(ROOT, 'build/losim.jar');

/** Where a `runs:` value can point without the loader having to find a class. */
const ENTRY_FILE = 'losim/test/src/WordCountJob.java';
const WORKER_FILE = 'losim/test/src/Counter.java';
const SHUFFLER_FILE = 'losim/test/src/Accumulator.java';

/**
 * A lab with nothing in it but a name.
 *
 * Writing a simulation needs no compiled code — the loader reads a file, and
 * what it checks is the file. What it does need is the *paths* to exist, since
 * a `runs:` value is a path and the loader stats it, so the lab is rooted at
 * this repository and the files below are real ones.
 */
function lab(): string {
  const dir = mkdtempSync(join(tmpdir(), 'losim-author-'));
  mkdirSync(join(dir, 'build'), { recursive: true });
  mkdirSync(join(dir, 'simulations'), { recursive: true });
  // What a lab's build writes, and all losim reads to know it is one.
  writeFileSync(join(dir, 'build/losim-toolchain.properties'), `classpath=${JAR}\n`);
  return dir;
}

const runs = (service: string, file: string, failures: Runs['failures'] = {}): Runs =>
  ({ service, file, failures });

/** A node-level failure, with the six fields no kind uses all of. */
function fails(kind: Failure['kind'], over: Partial<Failure> = {}): Failure {
  return {
    kind, atRefMs: 0, perRefMs: 0, other: '', forRefMs: 0, factor: 2,
    noticeRefMs: 0, restartAfterRefMs: 0, ...over,
  };
}

/** The catalogues the lab would have sent, as this check does not compile anything. */
const PALETTE: Palette = {
  compiled: true,
  services: [
    { file: ENTRY_FILE, service: 'losim.Job', bare: 'Job', entry: true,
      rpcs: [{ name: 'Load', idempotent: true }, { name: 'Run', idempotent: false }] },
    { file: WORKER_FILE, service: 'losim.t.Worker', bare: 'Worker', entry: false,
      rpcs: [{ name: 'Map', idempotent: true }, { name: 'Reduce', idempotent: true },
             { name: 'Note', idempotent: false }] },
    { file: SHUFFLER_FILE, service: 'losim.t.Shuffler', bare: 'Shuffler', entry: false,
      rpcs: [{ name: 'Fold', idempotent: true }] },
  ],
  other: 12,
  instances: [
    { name: 'm5.large', family: 'm5', vcpu: 2, memoryMb: 8192, storageGb: 32, onDemandPerHour: 0.115 },
    { name: 'c5.large', family: 'c5', vcpu: 2, memoryMb: 4096, storageGb: 32, onDemandPerHour: 0.102 },
    { name: 'a1.medium', family: 'a1', vcpu: 1, memoryMb: 2048, storageGb: 8, onDemandPerHour: 0.0255 },
  ],
  regions: [
    { name: 'eu-central-1', provider: 'aws', continent: 'europe', where: 'Frankfurt',
      zones: ['eu-central-1a', 'eu-central-1b', 'eu-central-1c'] },
    { name: 'ap-northeast-1', provider: 'aws', continent: 'asia', where: 'Tokyo',
      zones: ['ap-northeast-1a', 'ap-northeast-1b', 'ap-northeast-1c'] },
    { name: 'switzerlandnorth', provider: 'azure', continent: 'europe', where: 'Zurich',
      zones: ['switzerlandnorth-1', 'switzerlandnorth-2', 'switzerlandnorth-3'] },
  ],
  simulations: ['main.yaml'],
};

const base = firstDraft(PALETTE);

/** The entry node, unchanged, for a draft that is about something else. */
const entryPool = () => ({
  name: 'master', count: 1, prefix: 'master', instance: 'm5.large',
  zones: ['eu-central-1a'], runs: [runs('losim.Job', ENTRY_FILE)],
  failures: [], memoryMb: null, diskMb: null, overrides: [],
});

/** Every shape of simulation the form can compose, and what each of them is for. */
const DRAFTS: [string, Draft][] = [
  ['the form as it opens', base],
  ['a pool of one, so its node keeps the pool’s name', {
    ...base, name: 'single', pools: [entryPool()],
  }],
  ['a pool dealt over three zones', {
    ...base, name: 'spread',
    pools: [
      entryPool(),
      { name: 'workers', count: 6, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b', 'eu-central-1c'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['across an ocean, and in the other cloud', {
    ...base, name: 'far',
    pools: [
      entryPool(),
      { name: 'edge', count: 1, prefix: 'edge', instance: 'a1.medium', zones: ['ap-northeast-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'vault', count: 2, prefix: 'vault', instance: 'm5.large', zones: ['switzerlandnorth-1'],
        runs: [runs('losim.t.Shuffler', SHUFFLER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['what each rpc costs, one with a per-unit term and one without', {
    ...base, name: 'priced',
    simulatedDuration: [
      { runs: WORKER_FILE, rpc: 'Map', fixedRefMs: 2, perUnitRefNs: 20000 },
      { runs: WORKER_FILE, rpc: 'Reduce', fixedRefMs: 5, perUnitRefNs: 0 },
      // Left at zero, so it is not written and must not read back: an rpc absent
      // from the file and one written at zero are the same simulation.
      { runs: WORKER_FILE, rpc: 'Note', fixedRefMs: 0, perUnitRefNs: 0 },
    ],
  }],
  ['a node killed, and one that never comes back', {
    ...base, name: 'killed',
    pools: [
      entryPool(),
      { name: 'workers', count: 3, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('kill', { atRefMs: 300, restartAfterRefMs: 2000 })],
        memoryMb: null, diskMb: null,
        overrides: [{ node: 'w1', instance: '', zone: '', memoryMb: null, diskMb: null,
                      failures: [fails('kill', { atRefMs: 900 })] }] },
    ],
  }],
  ['a node frozen, and another made permanently slow', {
    ...base, name: 'slowed',
    pools: [
      entryPool(),
      { name: 'workers', count: 2, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('freeze', { atRefMs: 300, forRefMs: 800 }),
                   fails('degrade', { atRefMs: 900, factor: 4 })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['failures written as a standing rate rather than an instant', {
    ...base, name: 'weathered',
    pools: [
      entryPool(),
      { name: 'workers', count: 4, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        // All three the loader lets stand as a rate. The other four either bring
        // a node back or take it away for good, and it refuses a `per:` on them.
        failures: [fails('kill', { perRefMs: 2000 }),
                   fails('freeze', { perRefMs: 700, forRefMs: 150 }),
                   fails('degrade', { perRefMs: 400, factor: 3 })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['one bad replica: an rpc that fails on one node and not its peers', {
    ...base, name: 'bad-replica',
    pools: [
      entryPool(),
      { name: 'workers', count: 3, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
      // The whole reason rpc failures are written inside `runs:`: this node
      // serves the same service as the pool above and is the only one that is
      // bad at it, which is the case every design handles worst.
      { name: 'w9', count: 1, prefix: 'w9', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE, {
          Map: [{ kind: 'status', status: 'UNAVAILABLE', factor: 1, perCalls: 20 }],
          Reduce: [{ kind: 'slow', status: '', factor: 6, perCalls: 4 },
                   { kind: 'drop', status: '', factor: 1, perCalls: 50 }],
        })],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a network that costs something, and drops one call in a hundred', {
    ...base, name: 'wired',
    net: { sameZoneRefMs: 0.5, crossZoneRefMs: 30, jitterRefMs: 2, loss: 0.01 },
    pools: [
      entryPool(),
      { name: 'workers', count: 3, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'ap-northeast-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a network set one number at a time', {
    ...base, name: 'lossy',
    net: { sameZoneRefMs: 0, crossZoneRefMs: 0, jitterRefMs: 0, loss: 0.2 },
  }],
  // `scale` is the one field `toYaml` writes conditionally that no other draft
  // here exercises: they all sit at 1, which writes no key whether or not the
  // writer knows the field exists. So a simulation that is actually a model of
  // something — where losing the key on save turns a projection of eighty
  // thousand units into a run of one, silently.
  ['a model of ten times the run', { ...base, name: 'tenfold', scale: 10 }],
  ['a workload read from a folder rather than generated from the seed', {
    ...base, name: 'sourced',
    input: { source: 'losim/test/proto', unit: 'line', count: 240 },
  }],
  ['retries, safe and deliberately not', {
    ...base, name: 'retried',
    retries: [
      { method: 'losim.t.Worker.Map', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false },
      { method: 'losim.t.Worker.Note', attempts: 2, backoffRefMs: 0, multiplier: 1, unsafe: true },
    ],
  }],
  ['a pool capped below what its instance comes with', {
    ...base, name: 'capped',
    pools: [
      entryPool(),
      // 4 MB is the wordcount simulation's own trick: a node far too small for
      // what it is given, which fills up and says so.
      { name: 'workers', count: 3, prefix: 'workers', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: 4, diskMb: 2048, overrides: [] },
    ],
  }],
  ['a pair of nodes that stop reaching each other, and are mended later', {
    ...base, name: 'split',
    pools: [
      { ...entryPool(),
        failures: [fails('partition', { atRefMs: 300, other: 'w0' }),
                   fails('heal', { atRefMs: 1200, other: 'w0' })] },
      { name: 'workers', count: 2, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a spot node that warns before it goes, and one that comes back', {
    ...base, name: 'reclaimed',
    pools: [
      entryPool(),
      { name: 'workers', count: 2, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('spotReclaim', { atRefMs: 400, noticeRefMs: 250 })],
        memoryMb: null, diskMb: null,
        overrides: [{ node: 'w1', instance: '', zone: '', memoryMb: null, diskMb: null,
                      failures: [fails('spotReclaim', { atRefMs: 900, noticeRefMs: 100, restartAfterRefMs: 1500 })] }] },
    ],
  }],
  ['a node restarted where it stands', {
    ...base, name: 'bounced',
    pools: [
      entryPool(),
      { name: 'workers', count: 2, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('restart', { atRefMs: 500 })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a size the engine probes its way up to', { ...base, name: 'projected', scale: 1250 }],
  // A pool whose nodes are named apart from the pool they are in: `mappers`
  // numbered `m0`, `m1` is how most of the suite is written.
  ['a pool whose nodes are named apart from it', {
    ...base, name: 'prefixed',
    pools: [
      entryPool(),
      { name: 'mappers', count: 4, prefix: 'm', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'reducers', count: 2, prefix: 'r', instance: 'c5.large', zones: ['eu-central-1b'],
        runs: [runs('losim.t.Shuffler', SHUFFLER_FILE)],
        failures: [fails('partition', { atRefMs: 600, other: 'm0' })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  // A pool of one is normally written without a count, so its node keeps the
  // pool's name. Give it a prefix of its own and both keys have to be written
  // even at one, or the node is called `solo` where the file said `s0`.
  ['a pool of one, named apart from itself', {
    ...base, name: 'lone',
    pools: [
      entryPool(),
      { name: 'solo', count: 1, prefix: 's', instance: 'm5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('kill', { atRefMs: 300 })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a pool where one node is not like the others', {
    ...base, name: 'straggler',
    pools: [
      entryPool(),
      { name: 'workers', count: 4, prefix: 'w', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [], memoryMb: null, diskMb: null,
        overrides: [
          // Every shape an override can take, so a writer that learns to skip
          // any of them is caught.
          { node: 'w1', instance: 'a1.medium', zone: '', memoryMb: null, diskMb: null, failures: [] },
          { node: 'w2', instance: '', zone: 'eu-central-1b', memoryMb: null, diskMb: null, failures: [] },
          { node: 'w3', instance: '', zone: '', memoryMb: 4, diskMb: 512, failures: [] },
        ] },
    ],
  }],
  ['retries that ease off, and ones that do not', {
    ...base, name: 'backing-off',
    retries: [
      { method: 'losim.t.Worker.Map', attempts: 5, backoffRefMs: 20, multiplier: 2, unsafe: false },
      { method: 'losim.t.Shuffler.Fold', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false },
    ],
  }],
  ['all of it at once', {
    ...base, name: 'everything', seed: 9, scale: 32,
    pools: [
      { ...entryPool(),
        failures: [fails('partition', { atRefMs: 1800, other: 'edge' }),
                   fails('heal', { atRefMs: 2400, other: 'edge' })] },
      { name: 'workers', count: 4, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b'],
        runs: [runs('losim.t.Worker', WORKER_FILE, {
                 Note: [{ kind: 'drop', status: '', factor: 1, perCalls: 12 }] }),
               runs('losim.t.Shuffler', SHUFFLER_FILE)],
        failures: [fails('kill', { atRefMs: 300, restartAfterRefMs: 2000 }),
                   fails('freeze', { perRefMs: 700, forRefMs: 150 })],
        memoryMb: null, diskMb: null,
        overrides: [{ node: 'workers3', instance: '', zone: '', memoryMb: null, diskMb: null,
                      failures: [fails('spotReclaim', { atRefMs: 1500, noticeRefMs: 300 })] }] },
      { name: 'edge', count: 1, prefix: 'edge', instance: 'a1.medium', zones: ['ap-northeast-1a'],
        runs: [runs('losim.t.Worker', WORKER_FILE)],
        failures: [fails('degrade', { atRefMs: 1200, factor: 3 }),
                   fails('restart', { atRefMs: 2600 })],
        memoryMb: null, diskMb: null, overrides: [] },
    ],
    net: { sameZoneRefMs: 0.4, crossZoneRefMs: 25, jitterRefMs: 3, loss: 0.005 },
    input: { source: '', unit: 'line', count: 4096 },
    retries: [{ method: 'losim.t.Worker.Map', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false }],
    simulatedDuration: [
      { runs: WORKER_FILE, rpc: 'Map', fixedRefMs: 2, perUnitRefNs: 20000 },
      { runs: SHUFFLER_FILE, rpc: 'Fold', fixedRefMs: 5, perUnitRefNs: 0 },
    ],
  }],
];

/** A node, written the way the form writes one, for a file that must be refused. */
const ONE_NODE = `seed: 1
nodes:
  a:
    instance: m5.large
    zone: eu-central-1a
    runs: { losim.Job: ${ENTRY_FILE} }
`;

/** And the ones that must be refused, with the loader's own words. */
const REFUSED: [string, string][] = [
  ['an instance type that does not exist',
   ONE_NODE.replace('m5.large', 'm5.enormous')],
  ['a duration that does not say what kind of time it is',
   `${ONE_NODE}    failures:\n      - { kill: true, at: 900 }\n`],
  ['a key that is a typo for a real one',
   'seed: 1\nnodse:\n  a: { instance: m5.large, zone: eu-central-1a }\n'],
  // The form clamps loss to 0..1, so this is the loader being asked to hold the
  // line underneath it rather than a file the console could produce.
  ['a loss that is not a probability',
   `network: { loss: 2 }\n${ONE_NODE}`],
  ['a degrade with no factor to say how much slower',
   `${ONE_NODE}    failures:\n      - { degrade: true, at: 1 refMs }\n`],
  ['a failure that says both when it happens and how often',
   `${ONE_NODE}    failures:\n      - { kill: true, at: 1 refMs, per: 2 refMs }\n`],
  ['a failure that says neither, so it never fires',
   `${ONE_NODE}    failures:\n      - { kill: true }\n`],
  ['a rate on a failure that can only happen once',
   `${ONE_NODE}    failures:\n      - { restart: true, per: 2 refMs }\n`],
  ['an rpc failure written at the node level',
   `${ONE_NODE}    failures:\n      - { drop: true, per: 4 calls }\n`],
  ['a node failure written under an rpc',
   `seed: 1\nnodes:\n  a:\n    instance: m5.large\n    zone: eu-central-1a\n    runs:\n`
   + `      losim.Job:\n        file: ${ENTRY_FILE}\n        failures:\n`
   + `          Run:\n            - { kill: true, per: 4 calls }\n`],
  ['a runs: value that is not a .java file',
   'seed: 1\nnodes:\n  a: { instance: m5.large, zone: eu-central-1a, runs: { losim.Job: lab.Render } }\n'],
  ['a runs: value naming a file that is not there',
   'seed: 1\nnodes:\n  a: { instance: m5.large, zone: eu-central-1a, runs: { losim.Job: src/Nowhere.java } }\n'],
  ['a workload counted in nothing at all',
   `${ONE_NODE}\ninput:\n  unit: ""\n  count: 4\n`],
  ['a workload read from somewhere that does not exist',
   `${ONE_NODE}\ninput:\n  source: data/nowhere/\n  unit: line\n  count: 4\n`],
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
// The lab is a temp directory and the working directory is this repository. Both
// matter: files are written into the lab, and a `runs:` value is a path the
// loader stats against the working directory — so the drafts below can name real
// `.java` files without this check having to write anything into the repository.
const server = spawn('java', ['-cp', JAR, 'losim.cli.Main', 'serve',
  '--root', dir, '--runs', join(dir, 'results'), '--port', String(port), '--no-open'],
  { cwd: ROOT, stdio: 'ignore' });

let bad = 0;
const say = (m: string) => { console.log(`  !! ${m}`); bad++; };

async function post(body: unknown): Promise<{ status: number; body: Record<string, string> }> {
  const res = await fetch(`http://127.0.0.1:${port}/api/simulation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  return { status: res.status, body: (await res.json()) as Record<string, string> };
}

/** What the Edit form opens with: the file on disk, as a Draft, from `Draft.of`. */
async function get(name: string): Promise<{ draft?: Draft; error?: string }> {
  const res = await fetch(`http://127.0.0.1:${port}/api/simulation?name=${encodeURIComponent(name)}`);
  return (await res.json()) as { draft?: Draft; error?: string };
}

try {
  // The server is a JVM: give it a moment, and say so if it never arrives.
  let up = false;
  for (let i = 0; i < 100 && !up; i++) {
    try {
      await fetch(`http://127.0.0.1:${port}/api/simulations`);
      up = true;
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  if (!up) {
    console.error(`no lab on ${port} — is build/losim.jar built?`);
    process.exit(1);
  }

  console.log('what the console writes, handed to the loader that runs it\n');
  for (const [what, draft] of DRAFTS) {
    const yaml = toYaml(draft);
    const r = await post({ name: `${draft.name}.yaml`, yaml });
    if (r.status !== 200) {
      say(`${what}: refused — ${r.body.error}`);
      console.log(yaml.split('\n').map((l) => `        ${l}`).join('\n'));
      continue;
    }
    console.log(`  ok  ${what.padEnd(56)} ${nodes(draft).length} nodes, ${yaml.split('\n').length} lines`);
  }

  // Opening a simulation in the form and pressing Save without touching
  // anything must not change the file. That is one property and it covers the
  // whole Edit path: the Java that reads a file into a Draft, the JSON it
  // crosses in, and the writer that turns it back into YAML. Anything either
  // side learns to say and the other does not shows up here as a diff.
  console.log('\nand what Edit opens, saved again untouched\n');
  for (const [what, draft] of DRAFTS) {
    const written = toYaml(draft);
    const back = await get(`${draft.name}.yaml`);
    if (!back.draft) { say(`${what}: would not open — ${back.error}`); continue; }
    const got = back.draft;

    // First: what the form said is what the lab read back.
    //
    // The text comparison below cannot do this on its own. It is symmetric — a
    // writer that drops a field drops it on both passes, so the file it wrote
    // and the file it would write again agree perfectly about a value that was
    // lost on the way. `scale` is the live example: every draft here but two
    // sits at the default, which writes no key whether or not the writer has
    // ever heard of the field.
    const off: string[] = [];
    const same = (k: string, a: unknown, b: unknown) => {
      if (a !== b) off.push(`${k}: form said ${JSON.stringify(a)}, lab read ${JSON.stringify(b)}`);
    };
    /** Only the number this kind actually obeys — the loader refuses the rest. */
    const sameFailure = (at: string, f: Failure, g: Failure) => {
      same(`${at} kind`, f.kind, g.kind);
      same(`${at} at`, f.atRefMs, g.atRefMs);
      same(`${at} per`, f.perRefMs, g.perRefMs);
      if (f.kind === 'kill') same(`${at} restartAfter`, f.restartAfterRefMs, g.restartAfterRefMs);
      if (f.kind === 'freeze') same(`${at} for`, f.forRefMs, g.forRefMs);
      if (f.kind === 'degrade') same(`${at} factor`, f.factor, g.factor);
      if (f.kind === 'spotReclaim') {
        same(`${at} notice`, f.noticeRefMs, g.noticeRefMs);
        same(`${at} restartAfter`, f.restartAfterRefMs, g.restartAfterRefMs);
      }
      // The far end, which only these two have — and the one field where a
      // writer that dropped it would still produce a file that loads.
      if (PAIRED.includes(f.kind)) same(`${at} other`, f.other, g.other);
    };
    same('seed', draft.seed, got.seed);
    same('scale', draft.scale, got.scale);
    same('net.sameZone', draft.net.sameZoneRefMs, got.net.sameZoneRefMs);
    same('net.crossZone', draft.net.crossZoneRefMs, got.net.crossZoneRefMs);
    same('net.jitter', draft.net.jitterRefMs, got.net.jitterRefMs);
    same('net.loss', draft.net.loss, got.net.loss);
    same('input.source', draft.input.source, got.input.source);
    same('input.unit', draft.input.unit, got.input.unit);
    same('input.count', draft.input.count, got.input.count);
    same('pools', draft.pools.length, got.pools.length);
    draft.pools.forEach((p, j) => {
      const q2 = got.pools[j];
      if (!q2) return;
      same(`pool ${j} name`, p.name, q2.name);
      same(`pool ${j} count`, p.count, q2.count);
      // What the nodes are called, which every override points at.
      same(`pool ${j} prefix`, p.prefix, q2.prefix);
      same(`pool ${j} instance`, p.instance, q2.instance);
      same(`pool ${j} zones`, p.zones.join(','), q2.zones.join(','));
      same(`pool ${j} runs`, p.runs.length, q2.runs?.length ?? 0);
      p.runs.forEach((r, k) => {
        const g = q2.runs?.[k];
        if (!g) return;
        // Both halves of the pair, because losing either is a file that names
        // something nothing answers to.
        same(`pool ${j} runs ${k} service`, r.service, g.service);
        same(`pool ${j} runs ${k} file`, r.file, g.file);
        const mine = Object.keys(r.failures).filter((x) => r.failures[x].length).sort();
        const theirs = Object.keys(g.failures ?? {}).sort();
        same(`pool ${j} runs ${k} failing rpcs`, mine.join(','), theirs.join(','));
        for (const rpc of mine) {
          const a = r.failures[rpc];
          const b = (g.failures ?? {})[rpc] ?? [];
          same(`pool ${j} runs ${k} ${rpc} failures`, a.length, b.length);
          a.forEach((f, i) => {
            const h = b[i];
            if (!h) return;
            same(`pool ${j} runs ${k} ${rpc} ${i} kind`, f.kind, h.kind);
            same(`pool ${j} runs ${k} ${rpc} ${i} per`, f.perCalls, h.perCalls);
            if (f.kind === 'status') same(`pool ${j} runs ${k} ${rpc} ${i} status`, f.status, h.status);
            if (f.kind === 'slow') same(`pool ${j} runs ${k} ${rpc} ${i} factor`, f.factor, h.factor);
          });
        }
      });
      // Null is the third state, and it has to survive as null: a cap read back
      // as 0 is a node that can hold nothing, and one read back as the
      // instance's own number is a file that has grown a key nobody wrote.
      same(`pool ${j} memoryMb`, p.memoryMb, q2.memoryMb ?? null);
      same(`pool ${j} diskMb`, p.diskMb, q2.diskMb ?? null);
      same(`pool ${j} failures`, p.failures.length, q2.failures?.length ?? 0);
      p.failures.forEach((f, k) => {
        const g = q2.failures?.[k];
        if (g) sameFailure(`pool ${j} failure ${k}`, f, g);
      });
      same(`pool ${j} overrides`, p.overrides.length, q2.overrides?.length ?? 0);
      p.overrides.forEach((o, k) => {
        const g = q2.overrides?.[k];
        if (!g) return;
        same(`pool ${j} override ${k} node`, o.node, g.node);
        same(`pool ${j} override ${k} instance`, o.instance, g.instance ?? '');
        same(`pool ${j} override ${k} zone`, o.zone, g.zone ?? '');
        same(`pool ${j} override ${k} memoryMb`, o.memoryMb, g.memoryMb ?? null);
        same(`pool ${j} override ${k} diskMb`, o.diskMb, g.diskMb ?? null);
        same(`pool ${j} override ${k} failures`, o.failures.length, g.failures?.length ?? 0);
        o.failures.forEach((f, i) => {
          const h = g.failures?.[i];
          if (h) sameFailure(`pool ${j} override ${k} failure ${i}`, f, h);
        });
      });
    });
    same('retries', draft.retries.length, got.retries.length);
    draft.retries.forEach((r, j) => {
      const g = got.retries[j];
      if (!g) return;
      same(`retry ${j} method`, r.method, g.method);
      same(`retry ${j} attempts`, r.attempts, g.attempts);
      same(`retry ${j} backoff`, r.backoffRefMs, g.backoffRefMs);
      same(`retry ${j} multiplier`, r.multiplier, g.multiplier);
      // The one with real teeth. `unsafe: true` is what lets a retry stand on a
      // method the .proto never declared idempotent; the loader is happy without
      // it and `Retry.check` refuses at *run* time, so losing it here would pass
      // every load-time check in this file and break only when someone ran it.
      same(`retry ${j} unsafe`, r.unsafe, g.unsafe);
    });
    // Only the priced ones: a row at zero is what an rpc gets by not being in
    // the file at all, so the form writes none and the lab reads none back.
    const priced = draft.simulatedDuration.filter((c) => c.fixedRefMs > 0 || c.perUnitRefNs > 0);
    same('simulatedDuration', priced.length, got.simulatedDuration.length);
    priced.forEach((c, j) => {
      const g = got.simulatedDuration[j];
      if (!g) return;
      same(`duration ${j} runs`, c.runs, g.runs);
      same(`duration ${j} rpc`, c.rpc, g.rpc);
      same(`duration ${j} fixed`, c.fixedRefMs, g.fixedRefMs);
      // The term that decides whether a law has a variable in it at all. Dropped,
      // every scaled run still completes and every projection is flat and wrong.
      same(`duration ${j} perUnit`, c.perUnitRefNs, g.perUnitRefNs);
    });
    if (off.length) {
      say(`${what}: the lab did not read back what the form wrote`);
      for (const o of off) console.log(`        ${o}`);
      continue;
    }

    // Then: and saving it again changes nothing.
    const again = toYaml(got);
    if (again !== written) {
      say(`${what}: opening and saving it changed the file`);
      const a = written.split('\n');
      const b = again.split('\n');
      for (let i = 0; i < Math.max(a.length, b.length); i++) {
        if (a[i] !== b[i]) console.log(`        ${i + 1}  wrote: ${a[i] ?? '—'}\n        ${i + 1}  again: ${b[i] ?? '—'}`);
      }
      continue;
    }
    console.log(`  ok  ${what}`);
  }

  console.log('\nand what it must refuse\n');
  for (const [what, yaml] of REFUSED) {
    const r = await post({ name: 'refused', yaml });
    if (r.status === 200) { say(`${what}: was accepted, and should not have been`); continue; }
    const said = String(r.body.error ?? '');
    if (!/^refused\.yaml:\d+:/.test(said)) {
      say(`${what}: refused without a line number — "${said}"`);
      continue;
    }
    console.log(`  ok  ${what.padEnd(52)} ${said.slice(0, 90)}`);
  }

  // And then the same property against the simulations this repo actually
  // ships: every file in the suite and the gallery, opened in the form and
  // saved again.
  //
  // The drafts above are what the form can *compose*, which is a smaller set
  // than what it has to be able to *open* — a hand-written simulation reaches
  // for shapes nobody would build by clicking, and a course whose interface is
  // the console cannot have a stop that answers "the form has no control for
  // this".
  console.log('\nand every simulation this repo ships, opened and saved again\n');
  const SHIPPED = ['tests/simulations', 'losim/test/simulations', 'demo/gallery/simulations'];
  let opened = 0;
  for (const from of SHIPPED) {
    const where = join(ROOT, from);
    // `demo/` is local-only and gitignored, so a fresh clone has none of it.
    // Absent is not empty and not a failure.
    if (!existsSync(where)) { console.log(`  --  ${from} (not in this checkout)`); continue; }
    const files = readdirSync(where).filter((f) => f.endsWith('.yaml')).sort();
    const bad0 = bad;
    for (const f of files) {
      const name = f;
      // Written through the endpoint rather than onto the disk, so the file this
      // opens is a file the lab put there — and so an original that would not
      // load is a failure here rather than a puzzle two lines down.
      const put = await post({ name, yaml: readFileSync(join(where, f), 'utf8') });
      if (put.status !== 200) { say(`${from}/${f}: the loader refused it as shipped — ${put.body.error}`); continue; }
      const first = await get(name);
      if (!first.draft) { say(`${from}/${f}: would not open — ${first.error}`); continue; }
      const r = await post({ name, yaml: toYaml(first.draft) });
      if (r.status !== 200) { say(`${from}/${f}: what the form wrote was refused — ${r.body.error}`); continue; }
      const second = await get(name);
      if (!second.draft) { say(`${from}/${f}: would not open after saving — ${second.error}`); continue; }
      // Draft to Draft, not text to text: the file is reformatted on the way
      // through and that is fine. What must not change is what it means.
      if (JSON.stringify({ ...first.draft, name: '' }) !== JSON.stringify({ ...second.draft, name: '' })) {
        say(`${from}/${f}: opening it, saving it and opening it again changed it`);
        console.log(`        was:  ${JSON.stringify(first.draft)}`);
        console.log(`        now:  ${JSON.stringify(second.draft)}`);
        continue;
      }
      opened++;
    }
    if (bad === bad0) console.log(`  ok  ${from.padEnd(28)} ${files.length} files`);
  }
  console.log(`\n  ok  ${opened} shipped simulations open in the form and save back unchanged`);

  // A name is a file name. This is a web page writing into somebody's project.
  for (const name of ['../../escape', 'a/b', '.hidden']) {
    const r = await post({ name, yaml: ONE_NODE });
    if (r.status === 200) say(`'${name}' was accepted as a simulation name`);
  }
  console.log('\n  ok  a simulation name cannot walk out of its own project');
} finally {
  server.kill('SIGKILL');
  rmSync(dir, { recursive: true, force: true });
}

if (bad) { console.error(`\n${bad} problem(s)`); process.exit(1); }
console.log('\nauthor: everything the console can compose is a file the simulation will accept');
