/**
 * Where the nodes go.
 *
 * The reference figure this language borrows from reads left to right because a
 * MapReduce reads left to right, and someone drew it that way by hand. A viewer
 * cannot draw by hand, and hand-fanned arrows are exactly what made the first
 * one unreadable past a handful of nodes — so the columns are *derived*.
 *
 * Nothing here scales anything. The picture is composed at its natural size and
 * fitted to the frame as one unit, the way an SVG viewBox does it — so a cluster
 * of fifty is the same drawing, smaller, rather than a different drawing.
 */
import * as D from './design.ts';
import { Trace, spanTo } from './trace.ts';

/** What the film has to fit into, in the units everything here is written in. */
export const FRAME_W = 12.4;
export const FRAME_H = 5.6;

export type Point = [number, number];
export type Band = [number, number]; // top, bottom — y grows upward here

/**
 * How much air a band carries around its nodes.
 *
 * Separated out and named because it is the one thing in this file that is a
 * matter of taste rather than of structure — everything else is derived from the
 * trace, and this is chosen. It is also the difference between a picture that
 * fills its frame and one that floats in the middle of an empty page.
 *
 * The floors are not aesthetic. Below a node sit its lanes, its headroom
 * reading and its instance type; above it sits the service badge. Take the room
 * away and they collide with the band next door.
 */
export interface Room {
  /** Inside the band's edge, above the topmost node's label. */
  pad: number;
  /** Under a node, as a multiple of its height: [small cluster, large cluster]. */
  below: [number, number];
  /** Over a node, for the service badge. */
  above: [number, number];
  /** Beside a node, so two readings do not run together. */
  aside: [number, number];
}

/**
 * What the Python renderer used, kept so the parity check has something fixed
 * to compare against.
 *
 * Because it never changes, a divergence found against `LEGACY` is a
 * difference in the port's own arithmetic, not a difference in taste — a
 * guarantee `TIGHT`, tuned separately below, cannot offer on its own.
 */
export const LEGACY: Room = { pad: 0.34, below: [0.95, 0.55], above: [0.52, 0.16], aside: [0.34, 0.22] };

/**
 * What the viewer uses.
 *
 * `LEGACY` was composed for a film with nothing else on the screen; here the
 * picture is one panel among several and has to earn its space. Measured
 * against what actually hangs off a node, `LEGACY`'s spacing carries about
 * twice the air its labels need, so `TIGHT` draws every node in the cluster a
 * quarter smaller and gives back the room that air was spending.
 */
export const TIGHT: Room = { pad: 0.16, below: [0.72, 0.5], above: [0.34, 0.14], aside: [0.34, 0.22] };

interface Plan {
  at: Map<string, Point>;
  bands: Map<string, Band>;
  columnX: number[];
  width: number;
  height: number;
}

export class Layout {
  readonly trace: Trace;
  readonly colGap: number;
  readonly rowGap: number;
  readonly room: Room;

  /** node -> what its column is called, which is what its role serves */
  readonly home = new Map<string, string>();
  /** one per column, the entry's excepted */
  readonly labels: string[] = [];
  readonly columns: string[][];
  readonly size: Map<string, [number, number]>;
  readonly at: Map<string, Point>;
  readonly zones: string[];
  readonly bands: Map<string, Band>;
  readonly columnX: number[];
  readonly width: number;
  readonly height: number;

  /**
   * The shape being drawn into, which the arrangement is searched against.
   *
   * A default rather than a constant, because the right arrangement genuinely
   * depends on the screen: twenty-five nodes in four roles across three zones
   * lay out nine columns wide and three rows deep, which fills a cinema frame and
   * letterboxes a squarer one — while the same cluster stacked two-deep does the
   * opposite. Told the real shape, the search picks the one that fills it.
   *
   * The default is the frame the design system was composed in, so a layout
   * asked no question draws exactly as it always did.
   */
  frame: [number, number] = [FRAME_W, FRAME_H];

  constructor(trace: Trace, colGap = 3.05, rowGap = 1.62, room: Room = TIGHT, frame?: [number, number]) {
    if (frame) this.frame = frame;
    this.trace = trace;
    this.colGap = colGap;
    this.rowGap = rowGap;
    this.room = room;
    this.columns = this.buildColumns();
    this.size = this.sizes();
    const [zones, plan] = this.place();
    this.zones = zones;
    this.at = plan.at;
    this.bands = plan.bands;
    this.columnX = plan.columnX;
    this.width = plan.width;
    this.height = plan.height;
  }

  // ---------------------------------------------------------------- ranks

  /** When each node was first spoken to. */
  private firstCallTo(): Map<string, number> {
    const first = new Map<string, number>();
    const ordered = [...this.trace.spans].sort((a, b) => a.t0 - b.t0);
    for (const s of ordered) {
      const to = spanTo(s);
      if (s.kind !== 'rpc' || !to) continue;
      if (!first.has(to)) first.set(to, s.t0);
    }
    return first;
  }

  /**
   * One column per role, ordered by when that role first went to work.
   *
   * Two rules that both had to be abandoned to get here, and it is worth saying
   * why, because both are the obvious thing to try.
   *
   * **A column is not a stage of the pipeline.** Work overlaps: a node serving
   * fetches goes on serving them all through whatever comes next, so "who was
   * busy then" answers *nine of fifteen* and tells you nothing about what any of
   * them is for.
   *
   * **A node is not placed by when it was first called.** An entry that asks
   * every node what it is before it places anything has spoken to the whole
   * cluster before doing a thing, and first contact then puts every node in one
   * column.
   *
   * What survives both is what a node **offers**, which is also the only thing a
   * peer can find it by. Nodes serving the same services are the same role and
   * belong together, whether or not they were ever chosen — a node nobody sent
   * anything to still serves what it serves, and a cluster with spare capacity
   * should draw as one column rather than as a row of strays.
   */
  private buildColumns(): string[][] {
    const first = this.firstCallTo();

    // The entry is named by the trace rather than guessed at: `losim.Job/Run`
    // was served there, and `meta.entry` says so too. Inferring it from "was
    // never called" almost works, and stops working the moment a node reports
    // something back to it — which is exactly what a cluster with a monitor on
    // it does.
    const entry = this.trace.root?.vm || this.trace.entry;
    let drivers = this.trace.nodes.filter((m) => m.name === entry).map((m) => m.name);
    if (!drivers.length) {
      drivers = this.trace.nodes.filter((m) => !first.has(m.name)).map((m) => m.name);
    }
    const columns: string[][] = drivers.length ? [drivers] : [];
    const placed = new Set(drivers);

    // Role = the tuple of services a node offers, which is its label as well as
    // its grouping: there is nothing else a column of nodes has in common, and
    // nothing else a design would have chosen them by.
    const roles = new Map<string, { key: string[]; members: string[] }>();
    for (const m of this.trace.nodes) {
      if (placed.has(m.name)) continue;
      const key = [...m.serves].sort();
      const id = key.join('\u0000');
      let role = roles.get(id);
      if (!role) roles.set(id, (role = { key, members: [] }));
      role.members.push(m.name);
    }

    interface Ranked {
      when: number;
      key: string[];
      label: string;
      id: string;
    }
    const ranked: Ranked[] = [];
    for (const [id, role] of roles) {
      const members = new Set(role.members);
      const when: number[] = [];
      for (const span of this.trace.spans) {
        if (members.has(span.vm) && span.kind === 'handler') when.push(span.t0);
      }
      // The average moment its nodes were working, so a role that answers late
      // sits to the right of one that answers early — which is the only ordering
      // left once phases are gone, and the one the eye reads as flow anyway.
      const avg = when.length ? when.reduce((a, b) => a + b, 0) / when.length : Infinity;
      ranked.push({ when: avg, key: role.key, label: label(role.key), id });
    }

    ranked.sort((a, b) => cmp(a.when, b.when) || cmpList(a.key, b.key) || cmp(a.label, b.label));

    for (const r of ranked) {
      this.labels.push(r.label);
      const members = roles.get(r.id)!.members;
      columns.push(
        [...members].sort(
          (x, y) => cmp(first.get(x) ?? 0, first.get(y) ?? 0) || cmp(x, y),
        ),
      );
      for (const n of members) this.home.set(n, r.label);
    }
    return columns;
  }

  // --------------------------------------------------------------- places

  /**
   * How big to draw each node: wider with memory, taller with cores.
   *
   * Against the cluster's own median, so the picture answers "which of these is
   * the big one" rather than "how many gigabytes is this" — which is a question
   * no shape can answer and every legend has to.
   */
  private sizes(): Map<string, [number, number]> {
    const mems = this.trace.nodes.map((m) => m.capMb).filter((v) => v).sort((a, b) => a - b);
    const cpus = this.trace.nodes.map((m) => m.vcpu).filter((v) => v).sort((a, b) => a - b);
    const midM = mems.length ? mems[Math.floor(mems.length / 2)] : 1.0;
    const midC = cpus.length ? cpus[Math.floor(cpus.length / 2)] : 2;
    return new Map(
      this.trace.nodes.map((m) => [m.name, D.sizeOf(m.capMb, m.vcpu, midM, midC)]),
    );
  }

  /**
   * Zones are bands; roles are columns; a node sits where the two cross.
   *
   * This departs from the figure the rest of the language is borrowed from, on
   * purpose. That figure has no zones in it because it is a picture of an
   * algorithm, and this is a picture of a cluster — where a node *is* decides
   * what every call it makes costs, in latency and in money, and a diagram that
   * leaves the reader to remember which node was in which zone from the YAML
   * has left out the thing the simulation was written to show.
   *
   * Reading it: left to right is still the pipeline, so the algorithm survives.
   * Top to bottom is geography. A call that stays inside a band is cheap; one
   * that crosses a band is the shuffle paying cross-zone rates, and it becomes
   * something you can see rather than something you have to be told.
   *
   * How deep a cell stacks before it wraps is *counted*, not assumed. Four
   * mappers in one zone drawn as a stack of four make a band four nodes deep
   * and three such bands are taller than the frame; the same four as a two-by-two
   * make it two deep and the drawing doubles in size. Which is better depends on
   * how many nodes there are, so the number is what decides it.
   */
  private place(): [string[], Plan] {
    const zones = [...new Set(this.trace.nodes.map((m) => m.zone))].sort();

    const cell = new Map<string, string[]>();
    this.columns.forEach((column, i) => {
      for (const name of column) {
        const node = this.trace.byName.get(name);
        const k = cellKey(node ? node.zone : '', i);
        const list = cell.get(k);
        if (list) list.push(name);
        else cell.set(k, [name]);
      }
    });

    let deepest = 1;
    for (const v of cell.values()) deepest = Math.max(deepest, v.length);

    let bestScore: [number, number] | null = null;
    let best: Plan | null = null;
    for (let depth = 1; depth <= deepest; depth++) {
      const plan = this.arrange(cell, zones, depth);
      const fit = Math.min(
        this.frame[0] / Math.max(0.01, plan.width + 0.9),
        this.frame[1] / Math.max(0.01, plan.height + 0.7),
      );
      // Shallower wins ties: a cell that is two deep and two wide reads as a
      // group, and one that is one deep and four wide reads as a queue.
      const score: [number, number] = [round3(fit), -depth];
      if (bestScore === null || cmpPair(score, bestScore) > 0) {
        bestScore = score;
        best = plan;
      }
    }
    return [zones, best!];
  }

  /** One candidate layout, at a given cell depth, measured but not committed. */
  private arrange(cell: Map<string, string[]>, zones: string[], depth: number): Plan {
    // The vertical gap carries the reading that sits above each node, so it
    // is the generous one; the horizontal gap only has to let a packet cross.
    const gapX = this.aside + 0.8;
    const gapY = this.above + 0.3;
    const pad = this.room.pad;

    // How many sub-columns each role needs, decided by its fullest zone — so a
    // role stays one column across every band even when only one zone is busy.
    const subs: number[] = [];
    this.columns.forEach((_, i) => {
      let most = zones.length ? 0 : 1;
      for (const z of zones) most = Math.max(most, (cell.get(cellKey(z, i)) ?? []).length);
      subs.push(Math.max(1, Math.ceil(most / depth)));
    });

    const xs: number[][] = [];
    let at = 0.0;
    this.columns.forEach((column, i) => {
      let w = D.NODE_W;
      if (column.length) {
        w = -Infinity;
        for (const n of column) w = Math.max(w, this.size.get(n)![0]);
      }
      const span = subs[i] * w + (subs[i] - 1) * gapX;
      const row: number[] = [];
      for (let k = 0; k < subs[i]; k++) row.push(at + w / 2 + k * (w + gapX));
      xs.push(row);
      at += span + gapX;
    });
    const totalW = Math.max(0.01, at - gapX);

    const rowsUsed = new Map<string, number>();
    for (const z of zones) {
      let deep = 0;
      for (let i = 0; i < this.columns.length; i++) {
        const members = cell.get(cellKey(z, i)) ?? [];
        if (members.length) deep = Math.max(deep, Math.min(depth, members.length));
      }
      rowsUsed.set(z, Math.max(1, deep));
    }

    const place = new Map<string, Point>();
    const bands = new Map<string, Band>();
    let y = 0.0;
    for (const z of zones) {
      let tallest = -Infinity;
      for (let i = 0; i < this.columns.length; i++) {
        for (const n of cell.get(cellKey(z, i)) ?? []) {
          tallest = Math.max(tallest, this.size.get(n)![1]);
        }
      }
      const pitch = (tallest === -Infinity ? D.NODE_H : tallest) + gapY;
      const height = rowsUsed.get(z)! * pitch - gapY + this.below + this.above + pad * 2;
      bands.set(z, [y, y - height]);
      for (let i = 0; i < this.columns.length; i++) {
        const members = cell.get(cellKey(z, i)) ?? [];
        members.forEach((name, k) => {
          let col = depth ? Math.floor(k / depth) : 0;
          const row = depth ? k % depth : k;
          col = Math.min(col, xs[i].length - 1);
          const h = this.size.get(name)![1];
          place.set(name, [xs[i][col], y - pad - this.above - row * pitch - h / 2]);
        });
      }
      y -= height;
    }
    const totalH = Math.max(0.01, -y);

    const cx = totalW / 2;
    const cy = -totalH / 2;
    return {
      at: new Map([...place].map(([n, [x, yy]]) => [n, [x - cx, yy - cy] as Point])),
      bands: new Map([...bands].map(([z, [t, b]]) => [z, [t - cy, b - cy] as Band])),
      columnX: xs.map((c) => c.reduce((a, b) => a + b, 0) / c.length - cx),
      width: totalW,
      height: totalH,
    };
  }

  /** Room under a node for its lanes, and its instance type when shown. */
  get below(): number {
    return D.NODE_H * this.room.below[this.trace.nodes.length > 13 ? 1 : 0];
  }

  /** Room over a node for what it is computing, pinned to its top-right. */
  get above(): number {
    return this.room.above[this.trace.nodes.length > 13 ? 1 : 0];
  }

  /**
   * Room to the right of a node for what it is computing.
   *
   * Deliberately less than a full reading needs. Only a handful of nodes are
   * computing at any instant, so reserving a label's width beside every one of
   * them spends half the frame on space that is empty in most frames — and the
   * drawing shrinks for all of them to make room for a few. What is reserved is
   * enough to keep the readings apart; a long one runs into the gap, which is
   * empty when it matters.
   */
  get aside(): number {
    return this.room.aside[this.trace.nodes.length > 13 ? 1 : 0];
  }

  /** The rectangle a zone lives in: left, right, top, bottom. */
  zoneRect(zone: string): [number, number, number, number] {
    const [top, bottom] = this.bands.get(zone) ?? [0.0, 0.0];
    const half = this.width / 2 + 0.3;
    return [-half, half, top, bottom];
  }

  point(name: string): Point {
    return this.at.get(name) ?? [0.0, 0.0];
  }

  sizeOf(name: string): [number, number] {
    return this.size.get(name) ?? [D.NODE_W, D.NODE_H];
  }

  /** The middle of a role's column. */
  columnCentre(i: number): number {
    return i < this.columnX.length ? this.columnX[i] : 0.0;
  }

  /**
   * How much to magnify the finished drawing to fill the frame.
   *
   * Allowed above 1: a cluster of four drawn at its natural size sits in the
   * middle of an empty page, and there is no reason for it to. Bounded, because
   * past about half again the strokes start to look like a poster rather than a
   * figure.
   */
  get scaleFor(): number {
    return Math.min(
      1.55,
      this.frame[0] / Math.max(0.01, this.width + 0.9),
      this.frame[1] / Math.max(0.01, this.height + 0.7),
    );
  }

  /** Where the role captions hang: under every band. */
  columnFloor(): number {
    let low = Infinity;
    for (const [, band] of this.bands) low = Math.min(low, band[1]);
    return low === Infinity ? 0.0 : low;
  }

  /** What to call a column, in the quiet register under it. */
  columnLabel(i: number): string {
    // Where losim came in, and the one column not named by what it serves. Not
    // "coordinator": a design that has one is a design that chose to, and losim
    // does not know or care whether this node directs anything.
    if (i === 0 && this.columns.length && this.columns[0].length) return 'entry';
    const j = i - 1;
    return j >= 0 && j < this.labels.length ? this.labels[j] : '';
  }

  /** Where the corpus comes in: left of everything. */
  get inlet(): Point {
    let x = Infinity;
    for (const [, p] of this.at) x = Math.min(x, p[0]);
    if (x === Infinity) x = 0.0;
    return [x - this.colGap * 0.92, 0.0];
  }

  /** Where the answer leaves: right of everything. */
  get outlet(): Point {
    let x = -Infinity;
    for (const [, p] of this.at) x = Math.max(x, p[0]);
    if (x === -Infinity) x = 0.0;
    return [x + this.colGap * 0.92, 0.0];
  }
}

// --------------------------------------------------------------------- bits

function cellKey(zone: string, column: number): string {
  return `${zone}\u0000${column}`;
}

/**
 * What to call a role, which is what its nodes serve.
 *
 * Lowercased because it is drawn in the quiet register under a column, and
 * joined rather than abbreviated when a node serves more than one — two
 * services on one node is a real design, and picking one of them to print would
 * be the drawing deciding which half matters.
 */
function label(serves: string[]): string {
  return serves.map((s) => s.slice(s.lastIndexOf('.') + 1).toLowerCase()).join('\u00b7');
}

function cmp(a: number | string, b: number | string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Python's tuple comparison, for the tuple of services that names a role. */
function cmpList(a: string[], b: string[]): number {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    const c = cmp(a[i], b[i]);
    if (c) return c;
  }
  return a.length - b.length;
}

function cmpPair(a: [number, number], b: [number, number]): number {
  return cmp(a[0], b[0]) || cmp(a[1], b[1]);
}

/**
 * Python's `round(x, 3)`, which rounds half to even where JavaScript rounds
 * half up. The tie is exact only when the value is an odd multiple of 1/16 —
 * four fractional bits give exactly four decimal digits, the last of them a 5 —
 * and multiplying by 16 is exact, so the test cannot lie. Asking whether
 * `x * 1000` lands on a half would ask a question of an already-rounded product
 * and answer yes for values that are merely close.
 */
function round3(x: number): number {
  const q = x * 16;
  if (Number.isInteger(q) && Math.abs(q % 2) === 1) {
    const k = (q * 125 - 1) / 2; // x * 1000 is exactly k + 0.5
    return (k % 2 === 0 ? k : k + 1) / 1000;
  }
  return Number(x.toFixed(3));
}
