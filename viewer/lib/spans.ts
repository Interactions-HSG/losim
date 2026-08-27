/**
 * The distributed call stack.
 *
 * A trace's **events** say what happened. **Spans** say *why*, because every span
 * carries a parent — and losim propagates that parent across the RPC boundary in
 * a metadata header (D8 rule 2), so the chain is a real distributed call stack
 * rather than a per-machine one:
 *
 *     phase    shuffle                 master   1991 → 3581
 *       rpc    ShuffleWorker.Sort      master   2040 → 3575     to s2
 *         handler ShuffleWorker.Sort   s2       2042 → 3554
 *           rpc  MapWorker.Pull        s2       2069 → 2140     to m0
 *             handler MapWorker.Pull   m0       2072 → 2116
 *
 * Four machines deep, reading as one stack. That is the thing a student cannot
 * get from reading the code: that asking one machine to sort a partition causes
 * it to go and read from four others, and that most of the wall clock at the top
 * is the bottom waiting.
 *
 * ## Two numbers that carry the lesson
 *
 * **Self time** — a span's duration minus its children's — is what that machine
 * actually *did*; the rest it spent waiting on somebody else. Children's time is
 * taken as a **union rather than a sum**, because a handler that fans out four
 * concurrent calls has four overlapping children, and adding them up would
 * report a machine as having negative self time for being efficient.
 *
 * **The critical path** — at each level the child that finished last — is what
 * the makespan is actually made of, and therefore what there is any point in
 * making faster.
 *
 * ## What a bar is made of
 *
 * A call is not one duration, and the segments are the vocabulary of the
 * subject:
 *
 *     Sort → s2      ├─┤███████████████████████████████├──┤
 *                    out   queue      working          back
 *
 * All four are **measured**, and they are measured by subtraction rather than by
 * matching up events, so they sum to the bar exactly. `netRefMs` gives the wire;
 * the gap between the packet landing and the handler opening is the call sitting
 * on a machine with no core free, which is the vCPU model made visible and the
 * segment students least expect to exist.
 */
import { bare } from './frame.ts';
import type { Span, Trace } from './trace.ts';

export type Part = 'out' | 'queue' | 'working' | 'back';

export interface Segment {
  part: Part;
  t0: number;
  t1: number;
}

export interface Node {
  span: Span;
  id: number;
  depth: number;
  children: Node[];
  parent: Node | null;
  t0: number;
  /** Where it ended, or where the trace does when it never closed. */
  t1: number;
  /** True when this span never closed — a telemetry bug, and drawn as one. */
  dangling: boolean;
  /** Its own work: duration minus the union of what it was waiting on. */
  selfMs: number;
  /** Where it was addressed, when it was a call. */
  to: string | null;
  crossZone: boolean;
  method: string;
  task: number | null;
  segments: Segment[];
  ok: boolean;
  /** How many spans are underneath it, so a collapsed row can say what it hides. */
  hidden: number;
}

export class SpanTree {
  readonly roots: Node[] = [];
  readonly byId = new Map<number, Node>();
  /** Every node in tree order — the order the waterfall draws. */
  readonly flat: Node[] = [];
  readonly critical = new Set<number>();
  readonly duration: number;
  readonly machines: string[];

  constructor(trace: Trace) {
    this.duration = trace.duration;
    this.machines = trace.machines.map((m) => m.name);
    const tasks = trace.tasks();
    const zoneOf = new Map(trace.machines.map((m) => [m.name, m.zone]));

    const kids = new Map<number, Span[]>();
    for (const s of trace.spans) {
      const list = kids.get(s.parent) ?? [];
      list.push(s);
      kids.set(s.parent, list);
    }
    for (const list of kids.values()) list.sort((a, b) => a.t0 - b.t0 || a.id - b.id);

    // Span 0 is nobody's span: it is the absent parent every root points at. The
    // trace is therefore a forest, and a `job` that runs beside its phases rather
    // than over them is normal rather than a defect to be repaired here.
    const build = (span: Span, depth: number, parent: Node | null): Node => {
      const dangling = span.t1 < 0;
      const t1 = dangling ? this.duration : span.t1;
      const to = typeof span.detail['to'] === 'string' ? (span.detail['to'] as string) : null;
      const node: Node = {
        span,
        id: span.id,
        depth,
        children: [],
        parent,
        t0: span.t0,
        t1,
        dangling,
        selfMs: 0,
        to,
        crossZone: !!to && zoneOf.get(span.vm) !== zoneOf.get(to),
        method: span.kind === 'rpc' || span.kind === 'handler' ? bare(span.label) : span.label,
        // A handler inherits the task of the call that opened it. The trace keys
        // a unit of work on the *call*, but the thing that visibly computes is
        // the handler — and a machine working on task 3 should be task 3's colour
        // in every view, which is the whole of what the per-task hues are for.
        task:
          tasks.get(span.id) ??
          (parent && (parent.span.kind === 'rpc' || parent.span.kind === 'handler')
            ? parent.task
            : null),
        segments: [],
        ok: span.status === 'OK' || span.status === '',
        hidden: 0,
      };
      this.byId.set(node.id, node);
      for (const child of kids.get(span.id) ?? []) {
        node.children.push(build(child, depth + 1, node));
      }
      node.selfMs = Math.max(0, t1 - span.t0 - union(node.children));
      node.hidden = node.children.reduce((a, c) => a + 1 + c.hidden, 0);
      node.segments = segmentsOf(node);
      return node;
    };

    for (const root of kids.get(0) ?? []) this.roots.push(build(root, 0, null));

    const walk = (n: Node) => {
      this.flat.push(n);
      for (const c of n.children) walk(c);
    };
    for (const r of this.roots) walk(r);

    // The critical path: at each level, the child that finished last.
    //
    // Started from the last-finishing root **that has children**, which is not
    // the same as the last-finishing root. A `job` span brackets the whole run
    // and its phases are written as its *siblings* rather than its children, so
    // the root that finishes last is routinely a label with nothing underneath
    // it — and the chain from there is one span long, which is not a critical
    // path, it is a tautology.
    let head: Node | null = null;
    for (const r of this.roots) if (r.children.length && (!head || r.t1 > head.t1)) head = r;
    if (!head) for (const r of this.roots) if (!head || r.t1 > head.t1) head = r;
    while (head) {
      this.critical.add(head.id);
      let next: Node | null = null;
      for (const c of head.children) if (!next || c.t1 > next.t1) next = c;
      head = next;
    }
  }

  /**
   * The rows to draw, given what is collapsed and what is being asked for.
   *
   * A filter keeps a span *and its ancestors*, because a bar with its stack cut
   * away is a duration with nothing to explain it — the whole reason to look at
   * spans rather than events is the chain above them.
   */
  rows(collapsed: ReadonlySet<number>, keep?: (n: Node) => boolean): Node[] {
    let visible: Set<number> | null = null;
    if (keep) {
      visible = new Set<number>();
      for (const n of this.flat) {
        if (!keep(n)) continue;
        for (let a: Node | null = n; a && !visible.has(a.id); a = a.parent) visible.add(a.id);
      }
    }
    const out: Node[] = [];
    const walk = (n: Node) => {
      if (visible && !visible.has(n.id)) return;
      out.push(n);
      if (collapsed.has(n.id)) return;
      for (const c of n.children) walk(c);
    };
    for (const r of this.roots) walk(r);
    return out;
  }

  /** Where the job spends itself, gathered however you want to ask. */
  rollup(by: (n: Node) => string | null): Rollup[] {
    const rows = new Map<string, Rollup>();
    for (const n of this.flat) {
      const key = by(n);
      if (key === null) continue;
      const row = rows.get(key) ?? { key, total: 0, self: 0, calls: 0, failed: 0, bytes: 0 };
      row.total += n.t1 - n.t0;
      row.self += n.selfMs;
      row.calls++;
      if (!n.ok) row.failed++;
      row.bytes += Number(n.span.detail['bytes'] ?? n.span.detail['outBytes'] ?? 0);
      rows.set(key, row);
    }
    return [...rows.values()].sort((a, b) => b.self - a.self);
  }

  /** Every span open on one machine, for the swimlanes. */
  lanes(): Map<string, Node[]> {
    const out = new Map<string, Node[]>();
    for (const m of this.machines) out.set(m, []);
    for (const n of this.flat) {
      // An rpc is drawn on the caller's lane only when nothing answered it —
      // otherwise the handler is the thing that occupied a machine, and drawing
      // both would double every call.
      if (n.span.kind === 'rpc' && n.children.length) continue;
      const list = out.get(n.span.vm);
      if (list) list.push(n);
    }
    return out;
  }
}

export interface Rollup {
  key: string;
  total: number;
  self: number;
  calls: number;
  failed: number;
  bytes: number;
}

/** How much wall clock a set of spans covers between them, counting overlap once. */
function union(nodes: Node[]): number {
  if (!nodes.length) return 0;
  const spans = nodes.map((n) => [n.t0, n.t1] as const).sort((a, b) => a[0] - b[0]);
  let total = 0;
  let [start, end] = spans[0];
  for (let i = 1; i < spans.length; i++) {
    const [a, b] = spans[i];
    if (a > end) {
      total += end - start;
      start = a;
      end = b;
    } else if (b > end) {
      end = b;
    }
  }
  return total + (end - start);
}

/**
 * What a call was doing, part by part.
 *
 * By subtraction, so the parts tile the bar exactly. The alternative — matching
 * each handler to the `queue_wait` event nearest before it — is a guess that can
 * be wrong, and it would leave a bar whose segments do not add up to itself.
 */
function segmentsOf(n: Node): Segment[] {
  if (n.span.kind !== 'rpc') return [];
  const net = Number(n.span.detail['netRefMs'] ?? 0);
  const handler = n.children.find((c) => c.span.kind === 'handler');
  const out: Segment[] = [];
  if (!handler) {
    // Nothing answered: a call that failed, timed out or is still in flight. The
    // whole of it was the network as far as anyone here can tell.
    if (net > 0) out.push({ part: 'out', t0: n.t0, t1: Math.min(n.t1, n.t0 + net / 2) });
    return out;
  }
  const leg = Math.max(0, Math.min(net / 2, handler.t0 - n.t0));
  if (leg > 0) out.push({ part: 'out', t0: n.t0, t1: n.t0 + leg });
  // Landed, and waiting for a core. The gap is the queue, and it is the vCPU
  // model made visible.
  if (handler.t0 > n.t0 + leg) out.push({ part: 'queue', t0: n.t0 + leg, t1: handler.t0 });
  out.push({ part: 'working', t0: handler.t0, t1: handler.t1 });
  if (n.t1 > handler.t1) out.push({ part: 'back', t0: handler.t1, t1: n.t1 });
  return out;
}

/** How long, in the words a duration is read in. */
export function ms(v: number): string {
  if (v >= 1000) return `${(v / 1000).toFixed(2)}s`;
  if (v >= 10) return `${v.toFixed(0)}ms`;
  return `${v.toFixed(1)}ms`;
}
