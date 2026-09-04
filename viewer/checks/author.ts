/**
 * does what the designer writes load?
 *
 *   node viewer/checks/author.ts
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
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expand, firstDraft, toYaml, PAIRED, type Draft } from '../lib/author.ts';
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
  scenarios: ['main.yaml'],
};

const base = firstDraft(PALETTE);

/** Every shape of scenario the form can compose, and what each of them is for. */
const DRAFTS: [string, Draft][] = [
  ['the form as it opens', base],
  ['a pool of one, so its machine keeps the pool’s name', {
    ...base, name: 'single',
    pools: [{ name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] }],
  }],
  ['a pool dealt over three zones', {
    ...base, name: 'spread',
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'workers', count: 6, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b', 'eu-central-1c'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['across an ocean, and in the other cloud', {
    ...base, name: 'far',
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'edge', count: 1, prefix: 'edge', instance: 'a1.medium', zones: ['ap-northeast-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'vault', count: 2, prefix: 'vault', instance: 'm5.large', zones: ['switzerlandnorth-1'], runs: ['lab.Reducer'], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a machine killed, and one that never comes back', {
    ...base, name: 'killed',
    faults: [
      { kind: 'kill', atRefMs: 300, target: 'workers1', other: '', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 2000 },
      { kind: 'kill', atRefMs: 900, target: 'workers2', other: '', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  ['a machine frozen, and another made permanently slow', {
    ...base, name: 'slowed',
    faults: [
      { kind: 'freeze', atRefMs: 300, target: 'workers1', other: '', forRefMs: 800, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'degrade', atRefMs: 900, target: 'workers2', other: '', forRefMs: 0, factor: 4, noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  ['a network that costs something, and drops one call in a hundred', {
    ...base, name: 'wired',
    net: { sameZoneRefMs: 0.5, crossZoneRefMs: 30, jitterRefMs: 2, loss: 0.01 },
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'workers', count: 3, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'ap-northeast-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null, overrides: [] },
    ],
  }],
  ['a network set one number at a time', {
    ...base, name: 'lossy',
    net: { sameZoneRefMs: 0, crossZoneRefMs: 0, jitterRefMs: 0, loss: 0.2 },
  }],
  // kTime is the one field `toYaml` writes conditionally that no other draft
  // here exercises: they all sit at 1, which writes no key whether or not the
  // writer knows the field exists. So a scenario that is actually compressed —
  // where losing the key on save means the run silently takes twenty times as
  // long as the author asked for.
  ['a run compressed twenty times', { ...base, name: 'quick', kTime: 20 }],
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
      { method: 'lab.Worker.Map', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false },
      { method: 'lab.Worker.Note', attempts: 2, backoffRefMs: 0, multiplier: 1, unsafe: true },
    ],
  }],
  // The five shapes the Edit form could not open until this session. Each is a
  // scenario the console would run happily and then refuse to show you, which in
  // a course whose interface *is* the console is a dead end rather than a
  // limitation.
  ['a pool capped below what its instance comes with', {
    ...base, name: 'capped',
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [],
        memoryMb: null, diskMb: null, overrides: [] },
      // 4 MB is the wordcount scenario's own trick: a machine far too small for
      // the bucket it is given, which fills up and says so.
      { name: 'workers', count: 3, prefix: 'workers', instance: 'c5.large', zones: ['eu-central-1a'],
        runs: ['lab.Combiner'], memoryMb: 4, diskMb: 2048, overrides: [] },
    ],
  }],
  ['a pair of machines that stop reaching each other, and are mended later', {
    ...base, name: 'split',
    faults: [
      { kind: 'partition', atRefMs: 300, target: 'coordinator', other: 'workers1',
        forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'heal', atRefMs: 1200, target: 'coordinator', other: 'workers1',
        forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  ['a spot machine that warns before it goes, and one that comes back', {
    ...base, name: 'reclaimed',
    faults: [
      { kind: 'spot_reclaim', atRefMs: 400, target: 'workers1', other: '',
        forRefMs: 0, factor: 1, noticeRefMs: 250, restartAfterRefMs: 0 },
      { kind: 'spot_reclaim', atRefMs: 900, target: 'workers2', other: '',
        forRefMs: 0, factor: 1, noticeRefMs: 100, restartAfterRefMs: 1500 },
    ],
  }],
  ['a machine restarted where it stands', {
    ...base, name: 'bounced',
    faults: [
      { kind: 'restart', atRefMs: 500, target: 'workers0', other: '',
        forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  ['a workload, run directly', {
    ...base, name: 'sized',
    workload: { records: 5000, probe: [1000, 2000, 4000, 8000], workers: [2, 3] },
  }],
  ['a workload the engine probes its way up to', {
    ...base, name: 'projected', mode: 'scaled',
    workload: { records: 10_000_000, probe: [500, 1000, 2000, 4000, 8000], workers: [2, 4, 6] },
  }],
  // The pool that blocked 59 of the repo's own scenarios: machines named apart
  // from the pool they are in. `mappers` numbered `m0`, `m1` is how every
  // MapReduce scenario in the gallery is written.
  ['a pool whose machines are named apart from it', {
    ...base, name: 'prefixed',
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large',
        zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'mappers', count: 4, prefix: 'm', instance: 'c5.large',
        zones: ['eu-central-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'reducers', count: 2, prefix: 'r', instance: 'c5.large',
        zones: ['eu-central-1b'], runs: ['lab.Reducer'], memoryMb: null, diskMb: null, overrides: [] },
    ],
    // Aimed at the prefixed names, because that is what the machines are called
    // and a fault that named the pool would not load.
    faults: [
      { kind: 'kill', atRefMs: 300, target: 'm2', other: '', forRefMs: 0, factor: 1,
        noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'partition', atRefMs: 600, target: 'm0', other: 'r1', forRefMs: 0, factor: 1,
        noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  // A pool of one is normally written without a count, so its machine keeps the
  // pool's name. Give it a prefix of its own and both keys have to be written
  // even at one, or the machine is called `solo` where the file said `s0`.
  ['a pool of one, named apart from itself', {
    ...base, name: 'lone',
    pools: [
      { name: 'solo', count: 1, prefix: 's', instance: 'm5.large',
        zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'workers', count: 2, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null,
        overrides: [] },
    ],
    faults: [
      { kind: 'kill', atRefMs: 300, target: 's0', other: '', forRefMs: 0, factor: 1,
        noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
  }],
  ['a pool where one machine is not like the others', {
    ...base, name: 'straggler',
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large',
        zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'workers', count: 4, prefix: 'w', instance: 'c5.large',
        zones: ['eu-central-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null,
        overrides: [
          // Every one of the four shapes an override can take, so a writer that
          // learns to skip any of them is caught.
          { machine: 'w1', instance: 'a1.medium', zone: '', memoryMb: null, diskMb: null },
          { machine: 'w2', instance: '', zone: 'eu-central-1b', memoryMb: null, diskMb: null },
          { machine: 'w3', instance: '', zone: '', memoryMb: 4, diskMb: 512 },
        ] },
    ],
  }],
  ['retries that ease off, and ones that do not', {
    ...base, name: 'backing-off',
    retries: [
      { method: 'lab.Worker.Map', attempts: 5, backoffRefMs: 20, multiplier: 2, unsafe: false },
      { method: 'lab.Shuffler.Fold', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false },
    ],
  }],
  ['a scenario marked as a deliberately thin margin', {
    ...base, name: 'tight', tightMargin: true,
  }],
  ['all of it at once', {
    ...base, name: 'everything', seed: 9, kTime: 4, expectedRunRefSeconds: 45,
    pools: [
      { name: 'master', count: 1, prefix: 'master', instance: 'm5.large', zones: ['eu-central-1a'], runs: [], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'workers', count: 4, prefix: 'workers', instance: 'c5.large',
        zones: ['eu-central-1a', 'eu-central-1b'], runs: ['lab.Combiner', 'lab.Reducer'], memoryMb: null, diskMb: null, overrides: [] },
      { name: 'edge', count: 1, prefix: 'edge', instance: 'a1.medium', zones: ['ap-northeast-1a'], runs: ['lab.Combiner'], memoryMb: null, diskMb: null, overrides: [] },
    ],
    net: { sameZoneRefMs: 0.4, crossZoneRefMs: 25, jitterRefMs: 3, loss: 0.005 },
    workload: { records: 250_000, probe: [1000, 2000, 4000, 8000], workers: [2, 4] },
    faults: [
      { kind: 'kill', atRefMs: 300, target: 'workers1', other: '', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 2000 },
      { kind: 'freeze', atRefMs: 600, target: 'workers2', other: '', forRefMs: 400, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'degrade', atRefMs: 1200, target: 'edge', other: '', forRefMs: 0, factor: 3, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'spot_reclaim', atRefMs: 1500, target: 'workers3', other: '', forRefMs: 0, factor: 1, noticeRefMs: 300, restartAfterRefMs: 0 },
      { kind: 'partition', atRefMs: 1800, target: 'master', other: 'edge', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'heal', atRefMs: 2400, target: 'master', other: 'edge', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
      { kind: 'restart', atRefMs: 2600, target: 'workers0', other: '', forRefMs: 0, factor: 1, noticeRefMs: 0, restartAfterRefMs: 0 },
    ],
    chaos: [{ kind: 'freeze', everyRefMs: 700, among: 'workers', forRefMs: 150, factor: 2 }],
    retries: [{ method: 'lab.Worker.Map', attempts: 3, backoffRefMs: 40, multiplier: 1, unsafe: false }],
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
  // The form clamps loss to 0..1, so this is the loader being asked to hold the
  // line underneath it rather than a file the console could produce.
  ['a loss that is not a probability',
   'job: J\nnetwork: { loss: 2 }\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\n'],
  ['a degrade with no factor to say how much slower',
   'job: J\nmachines:\n  a: { instance: m5.large, zone: eu-central-1a }\nfaults:\n  - { at: 1 refMs, degrade: a }\n'],
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

/** What the Edit form opens with: the file on disk, as a Draft, from `Draft.of`. */
async function get(name: string): Promise<{ draft?: Draft; error?: string }> {
  const res = await fetch(`http://127.0.0.1:${port}/api/scenario?name=${encodeURIComponent(name)}`);
  return (await res.json()) as { draft?: Draft; error?: string };
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

  // Opening a scenario in the form and pressing Save without touching anything
  // must not change the file. That is one property and it covers the whole Edit
  // path: the Java that reads a file into a Draft, the JSON it crosses in, and
  // the writer that turns it back into YAML. Anything either side learns to say
  // and the other does not shows up here as a diff.
  console.log('\nand what Edit opens, saved again untouched\n');
  for (const [what, draft] of DRAFTS) {
    const wrote = toYaml(draft);
    const back = await get(`${draft.name}.yaml`);
    if (!back.draft) { say(`${what}: would not open — ${back.error}`); continue; }
    const got = back.draft;

    // First: what the form said is what the lab read back.
    //
    // The text comparison below cannot do this on its own. It is symmetric — a
    // writer that drops a field drops it on both passes, so the file it wrote
    // and the file it would write again agree perfectly about a value that was
    // lost on the way. `kTime` is the live example: every draft here but two
    // sits at the default, which writes no key whether or not the writer has
    // ever heard of the field.
    const off: string[] = [];
    const same = (k: string, a: unknown, b: unknown) => {
      if (a !== b) off.push(`${k}: form said ${JSON.stringify(a)}, lab read ${JSON.stringify(b)}`);
    };
    same('job', draft.job, got.job);
    same('seed', draft.seed, got.seed);
    same('kTime', draft.kTime, got.kTime);
    same('expectedRunRefSeconds', draft.expectedRunRefSeconds, got.expectedRunRefSeconds);
    same('tightMargin', draft.tightMargin, got.tightMargin);
    same('mode', draft.mode, got.mode);
    // Null and a workload of one record are different scenarios, so the absence
    // is compared as an absence rather than skipped.
    same('workload present', draft.workload !== null, got.workload != null);
    if (draft.workload && got.workload) {
      same('workload records', draft.workload.records, got.workload.records);
      same('workload probe', draft.workload.probe.join(','), got.workload.probe.join(','));
      same('workload workers', draft.workload.workers.join(','), got.workload.workers.join(','));
    }
    same('net.sameZone', draft.net.sameZoneRefMs, got.net.sameZoneRefMs);
    same('net.crossZone', draft.net.crossZoneRefMs, got.net.crossZoneRefMs);
    same('net.jitter', draft.net.jitterRefMs, got.net.jitterRefMs);
    same('net.loss', draft.net.loss, got.net.loss);
    same('pools', draft.pools.length, got.pools.length);
    draft.pools.forEach((p, j) => {
      const q2 = got.pools[j];
      if (!q2) return;
      same(`pool ${j} name`, p.name, q2.name);
      same(`pool ${j} count`, p.count, q2.count);
      // What the machines are called, which every fault points at.
      same(`pool ${j} prefix`, p.prefix, q2.prefix);
      same(`pool ${j} instance`, p.instance, q2.instance);
      same(`pool ${j} zones`, p.zones.join(','), q2.zones.join(','));
      same(`pool ${j} runs`, p.runs.join(','), q2.runs.join(','));
      // Null is the third state, and it has to survive as null: a cap read back
      // as 0 is a machine that can hold nothing, and one read back as the
      // instance's own number is a file that has grown a key nobody wrote.
      same(`pool ${j} memoryMb`, p.memoryMb, q2.memoryMb ?? null);
      same(`pool ${j} diskMb`, p.diskMb, q2.diskMb ?? null);
      same(`pool ${j} overrides`, p.overrides.length, q2.overrides?.length ?? 0);
      p.overrides.forEach((o, k) => {
        const g = q2.overrides?.[k];
        if (!g) return;
        same(`pool ${j} override ${k} machine`, o.machine, g.machine);
        same(`pool ${j} override ${k} instance`, o.instance, g.instance ?? '');
        same(`pool ${j} override ${k} zone`, o.zone, g.zone ?? '');
        same(`pool ${j} override ${k} memoryMb`, o.memoryMb, g.memoryMb ?? null);
        same(`pool ${j} override ${k} diskMb`, o.diskMb, g.diskMb ?? null);
      });
    });
    same('faults', draft.faults.length, got.faults.length);
    draft.faults.forEach((f, j) => {
      const g = got.faults[j];
      if (!g) return;
      same(`fault ${j} kind`, f.kind, g.kind);
      same(`fault ${j} at`, f.atRefMs, g.atRefMs);
      same(`fault ${j} target`, f.target, g.target);
      // Only the number this kind actually obeys: the others are never written,
      // so the lab reads its own defaults for them and a difference means nothing.
      if (f.kind === 'kill') same(`fault ${j} restart_after`, f.restartAfterRefMs, g.restartAfterRefMs);
      if (f.kind === 'freeze') same(`fault ${j} for`, f.forRefMs, g.forRefMs);
      if (f.kind === 'degrade') same(`fault ${j} factor`, f.factor, g.factor);
      if (f.kind === 'spot_reclaim') {
        same(`fault ${j} notice`, f.noticeRefMs, g.noticeRefMs);
        same(`fault ${j} restart_after`, f.restartAfterRefMs, g.restartAfterRefMs);
      }
      // The second machine, which only these two have — and the one field where
      // a writer that dropped it would still produce a file that loads, because
      // `partition: [a]` is refused but `partition: a` is a different shape the
      // loader would report as a pair of one.
      if (PAIRED.includes(f.kind)) same(`fault ${j} other`, f.other, g.other);
    });
    same('chaos', draft.chaos.length, got.chaos.length);
    draft.chaos.forEach((c, j) => {
      const g = got.chaos[j];
      if (!g) return;
      same(`chaos ${j} kind`, c.kind, g.kind);
      same(`chaos ${j} every`, c.everyRefMs, g.everyRefMs);
      same(`chaos ${j} among`, c.among, g.among);
      if (c.kind !== 'kill') same(`chaos ${j} for`, c.forRefMs, g.forRefMs);
      if (c.kind === 'degrade') same(`chaos ${j} factor`, c.factor, g.factor);
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
    if (off.length) {
      say(`${what}: the lab did not read back what the form wrote`);
      for (const o of off) console.log(`        ${o}`);
      continue;
    }

    // Then: and saving it again changes nothing.
    const again = toYaml(got);
    if (again !== wrote) {
      say(`${what}: opening and saving it changed the file`);
      const a = wrote.split('\n');
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
    const r = await post({ system: 'sys', name: 'refused', yaml });
    if (r.status === 200) { say(`${what}: was accepted, and should not have been`); continue; }
    const said = String(r.body.error ?? '');
    if (!/^refused\.yaml:\d+:/.test(said)) {
      say(`${what}: refused without a line number — "${said}"`);
      continue;
    }
    console.log(`  ok  ${what}\n      ${said.slice(0, 120)}`);
  }

  // And then the same property against the scenarios this repo actually ships:
  // every file in the suite and the gallery, opened in the form and saved again.
  //
  // The drafts above are what the form can *compose*, which is a smaller set
  // than what it has to be able to *open* — a hand-written scenario reaches for
  // shapes nobody would build by clicking, and a course whose interface is the
  // console cannot have a stop that answers "the form has no control for this".
  console.log('\nand every scenario this repo ships, opened and saved again\n');
  const SHIPPED = ['tests/scenarios', 'losim/test/scenarios', 'demo/gallery/scenarios'];
  let opened = 0;
  for (const from of SHIPPED) {
    const dir = join(ROOT, from);
    // `demo/` is local-only and gitignored, so a fresh clone has none of it.
    // Absent is not empty and not a failure.
    if (!existsSync(dir)) { console.log(`  --  ${from} (not in this checkout)`); continue; }
    const files = readdirSync(dir).filter((f) => f.endsWith('.yaml')).sort();
    let bad0 = bad;
    for (const f of files) {
      const name = f;
      // Written through the endpoint rather than onto the disk, so the file this
      // opens is a file the lab put there — and so an original that would not
      // load is a failure here rather than a puzzle two lines down.
      const put = await post({ system: 'sys', name, yaml: readFileSync(join(dir, f), 'utf8') });
      if (put.status !== 200) { say(`${from}/${f}: the loader refused it as shipped — ${put.body.error}`); continue; }
      const first = await get(name);
      if (!first.draft) { say(`${from}/${f}: would not open — ${first.error}`); continue; }
      const wrote = toYaml(first.draft);
      const r = await post({ system: 'sys', name, yaml: wrote });
      if (r.status !== 200) { say(`${from}/${f}: what the form wrote was refused — ${r.body.error}`); continue; }
      const second = await get(name);
      if (!second.draft) { say(`${from}/${f}: would not open after saving — ${second.error}`); continue; }
      // Draft to Draft, not text to text: the file is reformatted on the way
      // through and that is fine. What must not change is what it means.
      if (JSON.stringify(first.draft) !== JSON.stringify(second.draft)) {
        say(`${from}/${f}: opening it, saving it and opening it again changed it`);
        console.log(`        was:  ${JSON.stringify(first.draft)}`);
        console.log(`        now:  ${JSON.stringify(second.draft)}`);
        continue;
      }
      opened++;
    }
    if (bad === bad0) console.log(`  ok  ${from.padEnd(24)} ${files.length} files`);
  }
  console.log(`\n  ok  ${opened} shipped scenarios open in the form and save back unchanged`);

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
