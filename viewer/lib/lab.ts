/**
 * The lab behind the page, when there is one.
 *
 * `losim serve` puts three things on the same port as this app: the systems in
 * the project, a way to run one, and the output of the run that is going. This
 * is the client for those three, and it exists because the alternative is a
 * student learning a command line before they learn anything this course is
 * about.
 *
 * **It is allowed not to be there.** The same exported application is served
 * from a plain directory — the gallery, a trace somebody was sent, a static host
 * — and in all of those `/api/systems` is a 404. So every call here answers `null`
 * rather than throwing, and the panel that uses it simply does not appear. A
 * viewer that showed a broken button whenever it was opened without a lab behind
 * it would be worse than one that shows nothing.
 */

/** One system in the project, as the server sees it. */
export interface System {
  /** Its path from the project root — `0-tour/1-two-machines`. */
  id: string;
  /** Whether there is any code in it yet. A task can be declared and empty. */
  started: boolean;
  /** Whether it has a world to be run in. A single machine has none, and gets no film. */
  distributed: boolean;
  files: number;
  schema: boolean;
  /** Every scenario beside it, `main` first. More than one is a system with variants. */
  scenarios: string[];
  /** Its last run, if it has one. */
  trace?: string;
}

export interface Project {
  systems: System[];
  /** The system that is running, or null. */
  busy: string | null;
}

/** What a run has said so far, and where to ask from next. */
export interface Output {
  text: string;
  next: number;
  done: boolean;
  ok?: boolean;
  system?: string;
  /** Where the trace landed, once there is one. */
  trace?: string;
}

async function json<T>(url: string, init?: RequestInit): Promise<T | null> {
  try {
    const res = await fetch(url, { cache: 'no-store', ...init });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    // No lab behind this page, or it went away mid-poll. Both are ordinary.
    return null;
  }
}

/** Every system in the project, or null if this page is not being served by a lab. */
export async function project(): Promise<Project | null> {
  const body = await json<{ systems?: System[]; busy?: string | null }>('./api/systems');
  if (!body || !Array.isArray(body.systems)) return null;
  return { systems: body.systems, busy: body.busy ?? null };
}

/**
 * Ask for a run, and come back before it has finished.
 *
 * The server answers as soon as the run is queued, which is the whole design: a
 * build takes seconds and a fleet under chaos takes longer, and a page that
 * waited for it would look broken. What comes back is a job number; the output
 * arrives through {@link output}.
 *
 * A refusal comes back as its own sentence rather than as a status code — the
 * server writes one, and it is the thing worth putting on the screen.
 */
export async function run(
  system: string,
  scenario?: string,
): Promise<{ run?: number; error?: string }> {
  try {
    const res = await fetch('./api/run', {
      cache: 'no-store',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(scenario ? { system, scenario } : { system }),
    });
    const body = (await res.json()) as { run?: number; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}

/** What the current run has said since `from`. */
export async function output(from: number): Promise<Output | null> {
  return json<Output>(`./api/log?from=${from}`);
}

/* ------------------------------------------------------- what the code offers */

/** One rpc a service answers. */
export interface Rpc {
  name: string;
  /**
   * Whether the `.proto` declared it safe to run twice.
   *
   * Carried because a retry policy on a method that did not is *refused* when the
   * run starts — so a designer that offers retries without knowing this offers a
   * scenario that will not start.
   */
  idempotent: boolean;
}

/** One class a machine could run. */
export interface Offered {
  /** The Java class, fully qualified — what `runs:` takes. */
  cls: string;
  /** The bare gRPC service name — what the trace's `serves` reports. */
  service: string;
  /** The same service with its proto package, as `retries:` names it. */
  qualified: string;
  methods: Rpc[];
  source?: string;
}

export interface Instance {
  name: string;
  family: string;
  vcpu: number;
  memoryMb: number;
  storageGb: number;
  burstable: boolean;
  onDemandPerHour: number;
}

export interface Region {
  name: string;
  provider: string;
  continent: string;
  where: string;
  zones: string[];
}

/**
 * Everything needed to author a scenario for one system.
 *
 * The classes are read off the compiled bytecode; the instances and the regions
 * are losim's own catalogues. All three used to be discoverable only by reading
 * losim's source, which is why scenarios have been written by copying one.
 */
export interface Palette {
  system: string;
  /** Whether it builds. When it does not, `log` is javac's own words. */
  compiled: boolean;
  log?: string;
  jobs: string[];
  services: Offered[];
  /** How many other classes there are — so "nothing is a service" reads differently from "nothing compiled". */
  other: number;
  instances: Instance[];
  regions: Region[];
  scenarios: string[];
}

export async function palette(system: string): Promise<Palette | null> {
  const body = await json<Partial<Palette>>(`./api/classes?system=${encodeURIComponent(system)}`);
  if (!body) return null;
  // A system that does not compile answers with `compiled: false` and a log, and
  // nothing else — so every list has to be filled in rather than assumed.
  return {
    system: body.system ?? system,
    compiled: body.compiled ?? false,
    log: body.log,
    jobs: body.jobs ?? [],
    services: body.services ?? [],
    other: body.other ?? 0,
    instances: body.instances ?? [],
    regions: body.regions ?? [],
    scenarios: body.scenarios ?? [],
  };
}

/**
 * Write a scenario, having first had the lab refuse to write a broken one.
 *
 * The server loads it with the same loader a run uses before a byte reaches
 * disk, so a refusal comes back as the loader's own sentence with the line it
 * was written on — which is worth far more than anything this app could say.
 */
export async function saveScenario(
  system: string,
  name: string,
  yaml: string,
): Promise<{ scenario?: string; path?: string; replaced?: boolean; error?: string }> {
  try {
    const res = await fetch('./api/scenario', {
      cache: 'no-store',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ system, name, yaml }),
    });
    const body = (await res.json()) as { scenario?: string; path?: string; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}
