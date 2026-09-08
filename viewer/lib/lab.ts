/**
 * The lab behind the page, when there is one.
 *
 * `losim serve` puts three things on the same port as this app: the simulations
 * in the lab, a way to simulate one, and the output of the one that is going.
 * This is the client for those three, and it exists because the alternative is a
 * student learning a command line before they learn anything this course is
 * about.
 *
 * **It is allowed not to be there.** The same exported application is served
 * from a plain directory — the gallery, a trace somebody was sent, a static host
 * — and in all of those `/api/simulations` is a 404. So every call here answers
 * `null` rather than throwing, and the panel that uses it simply does not
 * appear. A viewer that showed a broken button whenever it was opened without a
 * lab behind it would be worse than one that shows nothing.
 */
import type { Draft } from './author.ts';

/** One simulation in the lab, as the server sees it. */
export interface Simulation {
  /** Its file name — `two-nodes.yaml`. */
  name: string;
  /** Where it sits, from the lab root. */
  path: string;
  /** Its last result, if it has one. */
  trace?: string;
}

export interface Project {
  simulations: Simulation[];
  /** Whether there is any code in the lab yet. A lab can start empty. */
  started: boolean;
  files: number;
  schema: boolean;
  /** The simulation that is running, or null. */
  busy: string | null;
}

/** What a run has said so far, and where to ask from next. */
export interface Output {
  text: string;
  next: number;
  done: boolean;
  ok?: boolean;
  simulation?: string;
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

/** Every simulation in the lab, or null if this page is not being served by a lab. */
export async function project(): Promise<Project | null> {
  const body = await json<Partial<Project>>('./api/simulations');
  if (!body || !Array.isArray(body.simulations)) return null;
  return {
    simulations: body.simulations,
    started: body.started ?? false,
    files: body.files ?? 0,
    schema: body.schema ?? false,
    busy: body.busy ?? null,
  };
}

/**
 * Ask for a simulation, and come back before it has finished.
 *
 * The server answers as soon as it is queued, which is the whole design: a build
 * takes seconds and a system that fails takes longer, and a page that waited
 * for it would look broken. What comes back is a number; the output arrives
 * through {@link output}.
 *
 * A refusal comes back as its own sentence rather than as a status code — the
 * server writes one, and it is the thing worth putting on the screen.
 */
export async function run(simulation: string): Promise<{ run?: number; error?: string }> {
  try {
    const res = await fetch('./api/run', {
      cache: 'no-store',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ simulation }),
    });
    const body = (await res.json()) as { run?: number; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}

/** What the simulation now running has said since `from`. */
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
   * Carried because a retry policy on an rpc that did not is *refused* when the
   * simulation starts — so a form that offers retries without knowing this
   * offers a simulation that will not start.
   */
  idempotent: boolean;
}

/**
 * One file a node could run, and what it thereby serves.
 *
 * Both halves, because a `runs:` entry is both: the key is the service, the
 * value is the path. A form that held one of them would have to invent the
 * other on save.
 */
export interface Offered {
  /** Where it is, from the project root — the value of a `runs:` entry. */
  file: string;
  /** As gRPC names it on the wire — the key of a `runs:` entry. */
  service: string;
  /** Its last segment, for a column with room for one word. */
  bare: string;
  /** Whether this is `losim.Job`. Exactly one node in a simulation runs one. */
  entry: boolean;
  rpcs: Rpc[];
}

export interface Instance {
  name: string;
  family: string;
  vcpu: number;
  memoryMb: number;
  storageGb: number;
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
 * Everything needed to author a simulation for this lab.
 *
 * The files are read off their own compiled bytecode; the instances and the
 * regions are losim's own catalogues. Exposing all three here is what lets a
 * simulation be composed from scratch rather than by copying one, without
 * reading losim's source to find out what a node can be given.
 *
 * One list of services, and the one that starts the work is in it with a flag
 * on it. There used to be a second list saying what each driver object's input
 * was made of, which the form had to ask the code for before it could draw the
 * input at all; the input is three lines now and the form draws all three
 * without asking anybody.
 */
export interface Palette {
  /** Whether it builds. When it does not, `log` is javac's own words. */
  compiled: boolean;
  log?: string;
  services: Offered[];
  /** How many other classes there are — so "nothing is a service" reads differently from "nothing compiled". */
  other: number;
  instances: Instance[];
  regions: Region[];
  simulations: string[];
}

export async function palette(): Promise<Palette | null> {
  const body = await json<Partial<Palette>>('./api/classes');
  if (!body) return null;
  // A lab that does not compile answers with `compiled: false` and a log, and
  // nothing else — so every list has to be filled in rather than assumed.
  return {
    compiled: body.compiled ?? false,
    log: body.log,
    services: body.services ?? [],
    other: body.other ?? 0,
    instances: body.instances ?? [],
    regions: body.regions ?? [],
    simulations: body.simulations ?? [],
  };
}

/**
 * Write a simulation, having first had the lab refuse to write a broken one.
 *
 * The server loads it with the same loader a simulation uses before a byte
 * reaches disk, so a refusal comes back as the loader's own sentence with the
 * line it was written on — which is worth far more than anything this app could
 * say.
 */
export async function saveSimulation(
  name: string,
  yaml: string,
): Promise<{ simulation?: string; path?: string; replaced?: boolean; error?: string }> {
  try {
    const res = await fetch('./api/simulation', {
      cache: 'no-store',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, yaml }),
    });
    const body = (await res.json()) as { simulation?: string; path?: string; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}

/**
 * An existing simulation, in the same shape the authoring form composes one in.
 *
 * The server does the reading: the loader checks the file is even valid, then
 * a second walk of the same parse tree fills in exactly what the form has a
 * control for. Anything the file has that the form does not comes back as a
 * refusal naming the key — the same as a broken simulation would — never a
 * `Draft` with something silently missing from it.
 */
export async function openSimulation(name: string): Promise<{ draft?: Draft; error?: string }> {
  try {
    const res = await fetch(`./api/simulation?name=${encodeURIComponent(name)}`, { cache: 'no-store' });
    const body = (await res.json()) as { draft?: Draft; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}
