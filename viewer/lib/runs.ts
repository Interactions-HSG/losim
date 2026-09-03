/**
 * Finding a run, and opening it.
 *
 * Two ways in, and both matter. A **manifest** beside the exported app lists the
 * traces that were built with it, so the picker has something in it the moment
 * the page loads. And a **file** the viewer drops on the page opens the same way,
 * because the case this whole viewer exists for is a student pointing it at
 * their own run — which is also why nothing is baked: what is read here is a raw
 * losim trace, exactly as `losim run` wrote it.
 */
import { RunIndex } from './frame.ts';
import { loadBill, type BillJson } from './ledger.ts';
import { Trace } from './trace.ts';

export interface RunRef {
  name: string;
  /** Where to fetch it, relative to the page. */
  href: string;
  /**
   * Whose run this is — `yours`, the reference `suite`'s, or the `gallery`'s.
   *
   * The picker groups on it and puts yours first. Without it a student's own
   * first run is one line among a hundred, alphabetically, between two worked
   * examples they have never heard of.
   */
  from?: 'yours' | 'suite' | 'gallery';
  machines?: number;
  durationRefMs?: number;
  job?: string;
  note?: string;
  /** Every distinct zone the machines sat in, so a card can say how far apart they were. */
  zones?: string[];
  /** The scenario file it was run from — `mr-classic.yaml`. */
  scenario?: string;
  /** Absent unless the run did not finish. */
  completed?: boolean;
  /**
   * What `losim bill` said, copied into the index by `traces.sh`.
   *
   * Here so the gallery and the cost report can put a hundred runs beside each
   * other without fetching a hundred bills — and never computed in this app,
   * because a viewer with its own prices would be a second accountant.
   */
  cost?: number;
  currency?: string;
  buckets?: Record<string, number>;
}

export interface Run {
  name: string;
  trace: Trace;
  index: RunIndex;
  /**
   * What `losim bill --json` said this run cost, when it is beside the trace.
   *
   * Optional, because a trace a student drops on the page has no bill next to it.
   * Absent, the money is simply not shown — which is better than the viewer
   * inventing a second pricing model of its own.
   */
  bill: BillJson | null;
}

/**
 * What the export was built with.
 *
 * Fetched rather than bundled, so re-running the gallery does not mean
 * rebuilding the app — and a static server cannot list a directory, so
 * something has to write this down.
 */
export async function manifest(base = './traces/index.json'): Promise<RunRef[]> {
  const res = await fetch(base, { cache: 'no-store' });
  if (!res.ok) return [];
  const body = (await res.json()) as { runs?: RunRef[] };
  return body.runs ?? [];
}

export async function openUrl(name: string, href: string): Promise<Run> {
  const [res, bill] = await Promise.all([
    fetch(href, { cache: 'no-store' }),
    loadBill(href.replace(/\.json$/, '.bill.json')),
  ]);
  if (!res.ok) throw new Error(`${name}: ${res.status} ${res.statusText}`);
  return build(name, await res.text(), bill);
}

export async function openFile(file: File): Promise<Run> {
  return build(file.name.replace(/\.json$/, ''), await file.text(), null);
}

function build(name: string, text: string, bill: BillJson | null): Run {
  let trace: Trace;
  try {
    trace = Trace.parse(text);
  } catch (e) {
    throw new Error(`${name} is not a losim trace: ${(e as Error).message}`);
  }
  if (!trace.machines.length) throw new Error(`${name} has no machines in it`);
  return { name, trace, index: new RunIndex(trace), bill };
}
