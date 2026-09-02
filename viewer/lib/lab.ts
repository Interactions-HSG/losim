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
 * — and in all of those `/api/tasks` is a 404. So every call here answers `null`
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
  task?: string;
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
  const body = await json<{ tasks?: System[]; busy?: string | null }>('./api/tasks');
  if (!body || !Array.isArray(body.tasks)) return null;
  return { systems: body.tasks, busy: body.busy ?? null };
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
  task: string,
  scenario?: string,
): Promise<{ job?: number; error?: string }> {
  try {
    const res = await fetch('./api/run', {
      cache: 'no-store',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(scenario ? { task, scenario } : { task }),
    });
    const body = (await res.json()) as { job?: number; error?: string };
    return res.ok ? body : { error: body.error ?? `the lab said ${res.status}` };
  } catch {
    return { error: 'the lab is not answering — is `losim serve` still running?' };
  }
}

/** What the current run has said since `from`. */
export async function output(from: number): Promise<Output | null> {
  return json<Output>(`./api/log?from=${from}`);
}
