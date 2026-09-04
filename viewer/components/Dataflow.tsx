'use client';

/**
 * The film: a fleet, at an instant.
 *
 * Zones are bands the machines live inside; roles are columns left to right, so a
 * MapReduce still reads the way the figure on the whiteboard reads. A machine is
 * drawn the size it *is* — wider with more memory, taller with more cores — and it
 * always says **how much is left**, not how much is used, because "holding 255 MB"
 * predicts nothing on its own and "180 MB left" is the number the out-of-memory is
 * going to be about.
 *
 * Every element is a function of the frame it is given: no state, no effect, no
 * imperative drawing. That is what makes scrubbing, playing and recording one code
 * path.
 *
 * **Colours arrive as values, never as CSS variables.** The recorder serialises
 * this `<svg>` into a standalone image, and a standalone image has no page to
 * inherit `var(--ink)` from — so a film themed through CSS would record as black
 * on black. The theme comes down as a prop.
 */
import { memo } from 'react';

import * as D from '../lib/design.ts';
import * as G from '../lib/glyphs.ts';
import type { Flight, Frame, FrameMachine } from '../lib/frame.ts';
import type { Layout } from '../lib/layout.ts';
import { alarm, chill, taskColour, warn, type Theme } from '../lib/theme.ts';

export interface DataflowProps {
  layout: Layout;
  frame: Frame;
  theme: Theme;
  /** Set when a fleet is big enough that some readings have to go. */
  dense?: boolean;
  hovered?: string | null;
  /** Clicking a message, which keeps its panel open and makes it readable. */
  onPinMessage?: (f: Flight, at: [number, number]) => void;
  /**
   * Clicking a machine, which docks its panel beside the film.
   *
   * The same gesture a message already had, and for the same reason. Hovering
   * one shows its panel *over* the picture, and reaching for that panel takes
   * the pointer off the drawing — which fires the SVG's own `onMouseLeave` and
   * closes the thing being reached for. So a hovered panel can be read and
   * nothing else: not scrolled, not selected from, not even pinned by the
   * button inside it.
   */
  onPinMachine?: (name: string) => void;
  /**
   * Pointing at a message, with where the pointer is.
   *
   * In client coordinates rather than SVG ones: the panel is an HTML element
   * over the stage, and converting once where the stage is known beats
   * converting in every packet.
   */
  onMessage?: (f: Flight | null, at: [number, number]) => void;
  onHover?: (name: string | null) => void;
  /**
   * Machines the filter has set aside.
   *
   * Dimmed rather than removed, deliberately: a fleet with the shufflers taken
   * out of it is a different fleet, and the question a filter answers is "what
   * is this one doing *among* the others". Removing them would also make the
   * picture jump every time the filter changed, which is the one thing the
   * layout exists to prevent.
   */
  muted?: ReadonlySet<string>;
  /** A unit of work to follow, dimming every message that is not it. */
  task?: number | null;
  ref?: React.Ref<SVGSVGElement>;
  style?: React.CSSProperties;
  className?: string;
}

/** Room for the column captions under the lowest band. */
const CAPTION = 0.62;
const MARGIN = 0.4;

export function Dataflow({
  layout,
  frame,
  theme,
  dense,
  hovered,
  onMessage,
  onPinMessage,
  onHover,
  onPinMachine,
  muted,
  task,
  ref,
  style,
  className,
}: DataflowProps) {
  const halfW = layout.width / 2 + 0.35;
  const halfH = layout.height / 2;
  const minX = -halfW - MARGIN;
  const minY = -halfH - MARGIN;
  const w = 2 * (halfW + MARGIN);
  const h = 2 * halfH + 2 * MARGIN + CAPTION;

  // y grows upward in the layout, because that is how the figure was composed;
  // SVG grows downward. Flipped once, here, rather than in every consumer.
  const floor = -layout.columnFloor();

  return (
    <svg
      ref={ref}
      viewBox={`${minX} ${minY} ${w} ${h}`}
      className={className}
      style={{ display: 'block', background: theme.surface, ...style }}
      onMouseLeave={() => onHover?.(null)}
    >
      <defs>
        {/* Depth, in the smallest amount that reads as depth. A machine is an
            object sitting on a surface; drawn as an outline on a flat ground it
            reads as a diagram of a machine instead. */}
        <linearGradient id="body" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={theme.machine} />
          <stop offset="100%" stopColor={theme.machineLow} />
        </linearGradient>
        <linearGradient id="held" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={theme.dataFill} />
          <stop offset="100%" stopColor={theme.dataFillLow} />
        </linearGradient>
      </defs>

      <rect x={minX} y={minY} width={w} height={h} fill={theme.surface} />

      {layout.zones.map((zone, i) => {
        const [left, right, top, bottom] = layout.zoneRect(zone);
        return (
          <g key={zone}>
            <rect
              x={left}
              y={-top}
              width={right - left}
              height={top - bottom}
              rx={0.14}
              fill={theme.zones[i % theme.zones.length]}
              stroke={theme.zoneEdge}
              strokeWidth={0.01}
            />
            <text
              x={left + 0.2}
              y={-top + 0.27}
              fontSize={0.13}
              fontWeight={600}
              letterSpacing={0.022}
              fill={theme.zoneLabel}
              style={{ fontFamily: 'var(--sans)', textTransform: 'uppercase' }}
            >
              {zone}
            </text>
          </g>
        );
      })}

      {/* The role captions, in the quiet register, under everything. */}
      {layout.columns.map((_, i) => {
        const label = layout.columnLabel(i);
        if (!label) return null;
        return (
          <text
            key={i}
            x={layout.columnCentre(i)}
            y={floor + 0.36}
            textAnchor="middle"
            fontSize={0.13}
            fontWeight={600}
            letterSpacing={0.05}
            fill={theme.pencil}
            style={{ fontFamily: 'var(--sans)', textTransform: 'uppercase' }}
          >
            {label}
          </text>
        );
      })}

      {frame.machines.map((m) => (
        <Machine
          key={m.name}
          m={m}
          theme={theme}
          dense={!!dense}
          dim={(!!hovered && hovered !== m.name) || !!muted?.has(m.name)}
          onHover={onHover}
          onPin={onPinMachine}
        />
      ))}

      {frame.flights.map((f) => (
        <Packet
          key={`${f.id}-${f.returning ? 'r' : 'o'}`}
          f={f}
          layout={layout}
          theme={theme}
          dense={!!dense}
          dim={
            !!muted?.has(f.from) ||
            !!muted?.has(f.to) ||
            (!!hovered && hovered !== f.from && hovered !== f.to) ||
            (task !== null && task !== undefined && f.task !== task)
          }
          onHover={onMessage}
          onPin={onPinMessage}
        />
      ))}
    </svg>
  );
}

/**
 * One machine.
 *
 * Memoised on its appearance rather than its identity: a fill that moved by a
 * hundredth of a megabyte is not a different picture, so what it holds is
 * bucketed before it is compared. Without that, every machine re-renders on every
 * frame of a run where nothing much is happening.
 */
const Machine = memo(
  function Machine({
    m,
    theme,
    dense,
    dim,
    onHover,
    onPin,
  }: {
    m: FrameMachine;
    theme: Theme;
    dense: boolean;
    dim: boolean;
    onHover?: (name: string | null) => void;
    onPin?: (name: string) => void;
  }) {
    const { w, h } = m;
    const dead = m.state === 'dead';
    const frozen = m.state === 'frozen';
    const fill = dead ? '' : G.liquid(w, h, m.memShare);
    const [bars, hatchWeight] = m.state === 'degraded' ? G.hatch(w, h) : [[], 0];
    const over = m.memShare >= 1;
    const task = m.work.length ? m.work[0].task : null;
    const rim = dead || frozen ? chill(theme) : theme.ink;
    const level =
      m.memShare >= 1 ? alarm(theme) : m.memShare >= D.WARN_AT ? warn(theme) : 'url(#held)';

    return (
      <g
        transform={`translate(${m.x} ${-m.y})`}
        opacity={dim ? 0.32 : 1}
        onMouseEnter={() => onHover?.(m.name)}
        onClick={(e) => {
          e.stopPropagation();
          onPin?.(m.name);
        }}
        style={{ cursor: 'pointer' }}
      >
        <rect x={-w / 2 - 0.1} y={-h / 2 - 0.34} width={w + 0.2} height={h + 0.8} fill="transparent" />

        {/* Depth, painted rather than filtered: the same shape, a shade below. */}
        {!dead && (
          <path
            d={G.ellipse(w, h)}
            transform="translate(0 0.035)"
            fill="#000"
            opacity={theme.shadow * 0.38}
          />
        )}
        <path
          d={G.ellipse(w, h)}
          fill={dead ? theme.surface : 'url(#body)'}
          stroke={rim}
          strokeWidth={0.017}
          strokeDasharray={dead || frozen ? '0.09 0.06' : undefined}
        />
        {fill && <path d={fill} fill={level} opacity={0.95} />}
        {bars.map((d, i) => (
          <path key={i} d={d} stroke={D.HATCH} strokeWidth={hatchWeight} fill="none" opacity={0.6} />
        ))}
        <path
          d={G.ellipse(w, h)}
          fill="none"
          stroke={rim}
          strokeWidth={0.017}
          strokeDasharray={dead || frozen ? '0.09 0.06' : undefined}
        />
        {over && <path d={G.overflow(w, h)} fill="none" stroke={alarm(theme)} strokeWidth={0.035} />}
        {dead &&
          G.struck(w, h).map((d, i) => (
            <path key={i} d={d} stroke={alarm(theme)} strokeWidth={0.035} strokeLinecap="round" />
          ))}

        {/* While it is working, its name is replaced by what it is working on. A
            machine's name is a thing you look up once; what it is computing is the
            thing you came to see. */}
        {m.work.length ? (
          <text
            textAnchor="middle"
            y={0.03}
            fontSize={0.175}
            fontWeight={600}
            fill={taskColour(theme, task)}
            style={{ fontFamily: 'var(--sans)' }}
          >
            {m.work[0].method}
            {m.work.length > 1 && (
              <tspan fill={theme.pencil} fontSize={0.13} fontWeight={500}>
                {' '}
                +{m.work.length - 1}
              </tspan>
            )}
          </text>
        ) : (
          <text
            textAnchor="middle"
            y={0.03}
            fontSize={0.185}
            fontWeight={500}
            fill={dead ? chill(theme) : theme.ink}
            style={{ fontFamily: 'var(--sans)' }}
          >
            {m.name}
          </text>
        )}

        {/* What program answers here, at all times. A column position says where a
            machine sits in the pipeline; only this says what it runs — and on a
            fleet where one machine serves two services, the column has stopped
            being able to say it. */}
        {!dense && m.serves.length > 0 && (
          <text
            textAnchor="middle"
            y={-h / 2 - 0.12}
            fontSize={0.112}
            fontWeight={600}
            letterSpacing={0.018}
            fill={theme.pencil}
            style={{ fontFamily: 'var(--sans)' }}
          >
            {m.serves.join(' · ')}
          </text>
        )}

        <Lanes m={m} theme={theme} />

        {/* Headroom, in words. Not usage: what is left. */}
        <text
          textAnchor="middle"
          y={h / 2 + 0.31}
          fontSize={0.125}
          fontWeight={500}
          fill={m.memShare >= D.WARN_AT ? alarm(theme) : theme.pencil}
          style={{ fontFamily: 'var(--sans)' }}
        >
          {mb(m.freeMb)} left
          {m.diskCapMb > 0 && (
            <tspan fill={m.diskShare >= D.WARN_AT ? alarm(theme) : theme.pencil} opacity={0.75}>
              {' '}
              · disk {mb(m.diskFreeMb)}
            </tspan>
          )}
        </text>

        {!dense && (
          <text
            textAnchor="middle"
            y={h / 2 + 0.47}
            fontSize={0.108}
            fill={theme.rule}
            style={{ fontFamily: 'var(--sans)' }}
          >
            {m.instance}
          </text>
        )}
      </g>
    );
  },
  (a, b) => a.dense === b.dense && a.dim === b.dim && a.theme === b.theme && same(a.m, b.m),
);

/** One slot per vCPU, lit while something is in it, plus what is queued behind. */
function Lanes({ m, theme }: { m: FrameMachine; theme: Theme }) {
  const n = Math.max(1, Math.min(m.vcpu, 8));
  const slot = Math.min(0.16, (m.w * 0.72) / n);
  const gap = slot * 0.35;
  const total = n * slot + (n - 1) * gap;
  const busy = Math.round((m.busy / 100) * n);
  return (
    <g transform={`translate(${-total / 2} ${m.h / 2 + 0.07})`}>
      {Array.from({ length: n }, (_, i) => (
        <rect
          key={i}
          x={i * (slot + gap)}
          y={0}
          width={slot}
          height={0.05}
          rx={0.025}
          fill={
            i < busy
              ? taskColour(theme, m.work[i % Math.max(1, m.work.length)]?.task ?? 0)
              : theme.faint
          }
        />
      ))}
      {m.queued > 0 && (
        <text
          x={total + 0.07}
          y={0.052}
          fontSize={0.1}
          fontWeight={600}
          fill={warn(theme)}
          style={{ fontFamily: 'var(--sans)' }}
        >
          +{Math.round(m.queued)}
        </text>
      )}
    </g>
  );
}

/**
 * A message, on the wire, between two named machines.
 *
 * Four things have to be unmistakable, and all four are easy to lose.
 *
 * **Which machine it left, and which it is going to.** The wire is drawn whole, so
 * the message is always on a line whose two ends are visible — not a box drifting
 * through open space that could have come from anywhere — with an arrowhead at
 * the far end. It bows rather than running straight, which keeps it off whatever
 * machine happens to sit between the two, and bows the *other* way coming back, so
 * a request and its reply are two visibly different journeys rather than one line
 * with traffic going both ways on it.
 *
 * **What is inside it.** The digest: *the words*. `the 1,729 · cat 402 · +1,116
 * more`. This is the whole reason losim records payloads at all (D8 rule 4), which
 * no real tracing system would do — a film of machines exchanging opaque byte
 * counts teaches nothing a bar chart would not.
 *
 * **How much of it there is.** The envelope is drawn the size of its payload, so a
 * coordinator handing out a task number and a shuffler dragging a whole region
 * across a zone boundary are visibly not the same event. Compressed hard, like the
 * machine sizes, because the spread between those two is enormous and drawn
 * linearly the small one disappears.
 */
const Packet = memo(function Packet({
  f,
  layout,
  theme,
  dense,
  dim,
  onHover,
  onPin,
}: {
  f: Flight;
  layout: Layout;
  theme: Theme;
  dense: boolean;
  dim: boolean;
  onHover?: (f: Flight | null, at: [number, number]) => void;
  onPin?: (f: Flight, at: [number, number]) => void;
}) {
  const [ax, ay] = layout.point(f.from);
  const [bx, by] = layout.point(f.to);
  const rawSrc: [number, number] = f.returning ? [bx, -by] : [ax, -ay];
  const rawDst: [number, number] = f.returning ? [ax, -ay] : [bx, -by];
  const from = f.returning ? f.to : f.from;
  const to = f.returning ? f.from : f.to;

  // Edge to edge, not centre to centre. A packet drawn all the way to the middle
  // of its destination sits on top of the machine it is addressed to, hiding the
  // one thing the arrival was supposed to tell you.
  const dx = rawDst[0] - rawSrc[0];
  const dy = rawDst[1] - rawSrc[1];
  const len = Math.hypot(dx, dy) || 1;
  const src = shrink(rawSrc, dx / len, dy / len, layout.sizeOf(from), 1);
  const dst = shrink(rawDst, dx / len, dy / len, layout.sizeOf(to), -1);

  const mx = (src[0] + dst[0]) / 2;
  const my = (src[1] + dst[1]) / 2;
  const bow = 0.13 * Math.hypot(dst[0] - src[0], dst[1] - src[1]) * (f.returning ? -1 : 1);
  const ctrl: [number, number] = [
    mx - ((dst[1] - src[1]) / len) * bow,
    my + ((dst[0] - src[0]) / len) * bow,
  ];

  const [x, y] = quad(src, ctrl, dst, f.progress);
  const [hx, hy] = quad(src, ctrl, dst, 0.985);
  const colour = f.failed ? alarm(theme) : taskColour(theme, f.task);
  const angle = (Math.atan2(dst[1] - hy, dst[0] - hx) * 180) / Math.PI;

  const pw = D.PACKET_W * f.size;
  const ph = D.PACKET_H * f.size;
  const above = y > -layout.height / 2 + 0.55;
  const lift = above ? -1 : 1;
  const wire = f.crossZone ? warn(theme) : theme.rule;

  // Everything about this call, for the pointer. The film already says the route
  // and a digest; this is the rest of what a viewer asks next.
  const says = [
    `${f.method}: ${from} to ${to}`,
    f.returning ? 'the answer, coming back' : 'the request, going out',
    `${f.bytes.toLocaleString()} bytes`,
    f.items > 0 ? `${f.items.toLocaleString()} entries` : '',
    f.crossZone ? 'crossed a zone: billed, and slower' : '',
    f.failed ? 'failed' : '',
    f.held ? 'held on screen longer than it took' : '',
    f.digest,
  ]
    .filter(Boolean)
    .join(' — ');

  return (
    <g
      opacity={dim ? 0.16 : 1}
      onMouseMove={(e) => onHover?.(f, [e.clientX, e.clientY])}
      onMouseLeave={() => onHover?.(null, [0, 0])}
      onClick={(e) => {
        e.stopPropagation();
        onPin?.(f, [e.clientX, e.clientY]);
      }}
      style={{ cursor: onHover ? 'pointer' : undefined }}
    >
      {/* Kept for the browser's own tooltip and for anything reading the SVG on
          its own — an exported still has no panel to open. The panel is what a
          person gets; this is what the file remembers. */}
      <title>{says}</title>
      <path
        d={`M ${src[0]} ${src[1]} Q ${ctrl[0]} ${ctrl[1]} ${dst[0]} ${dst[1]}`}
        fill="none"
        stroke={wire}
        strokeWidth={f.crossZone ? 0.017 : 0.01}
        strokeDasharray={f.crossZone ? '0.09 0.05' : '0.05 0.05'}
        opacity={f.crossZone ? 0.85 : 0.6}
        strokeLinecap="round"
      />
      <g transform={`translate(${dst[0]} ${dst[1]}) rotate(${angle})`}>
        <path d="M -0.2 -0.08 L 0 0 L -0.2 0.08 Z" fill={wire} opacity={0.95} />
      </g>

      <g transform={`translate(${x} ${y})`}>
        <rect
          x={-pw / 2}
          y={-ph / 2 + 0.032}
          width={pw}
          height={ph}
          rx={0.045}
          fill="#000"
          opacity={theme.shadow * 0.32}
        />
        <rect
          x={-pw / 2}
          y={-ph / 2}
          width={pw}
          height={ph}
          rx={0.045}
          fill={f.failed ? (theme.dark ? '#3a1e1b' : '#f8e6e4') : 'url(#held)'}
          stroke={colour}
          strokeWidth={0.02}
        />
        <text
          textAnchor="middle"
          y={0.036 * Math.min(f.size, 1.4)}
          fontSize={Math.min(0.12, 0.1 * f.size)}
          fontWeight={600}
          fill={colour}
          style={{ fontFamily: 'var(--sans)' }}
        >
          {f.method}
        </text>

        {/* Who to whom, and what is in it. Two lines, because they answer two
            different questions and running them together makes both slower to
            read. Haloed, so they survive whatever they cross. */}
        {!dense && (
          <g transform={`translate(0 ${lift * (ph / 2 + 0.1)})`}>
            <text
              textAnchor="middle"
              y={above ? -0.15 : 0.27}
              fontSize={0.115}
              fontWeight={600}
              fill={theme.pencil}
              stroke={theme.surface}
              strokeWidth={0.055}
              strokeLinejoin="round"
              style={{ fontFamily: 'var(--sans)', paintOrder: 'stroke' }}
            >
              {from} <tspan fill={colour}>{f.returning ? '←' : '→'}</tspan> {to}
              {f.bytes > 0 && (
                <tspan fill={theme.rule} fontWeight={500}>
                  {' '}
                  · {bytes(f.bytes)}
                </tspan>
              )}
            </text>
            {f.digest && (
              <text
                textAnchor="middle"
                y={above ? 0 : 0.12}
                fontSize={0.12}
                fill={f.failed ? alarm(theme) : theme.ink}
                stroke={theme.surface}
                strokeWidth={0.06}
                strokeLinejoin="round"
                style={{ fontFamily: 'var(--sans)', paintOrder: 'stroke' }}
              >
                {f.digest}
              </text>
            )}
          </g>
        )}
      </g>
    </g>
  );
});

// --------------------------------------------------------------------- bits

/** A point along a quadratic curve, at `u` of the way from one end to the other. */
function quad(
  a: [number, number],
  c: [number, number],
  b: [number, number],
  u: number,
): [number, number] {
  const v = 1 - u;
  return [
    v * v * a[0] + 2 * v * u * c[0] + u * u * b[0],
    v * v * a[1] + 2 * v * u * c[1] + u * u * b[1],
  ];
}

/**
 * Moves a point from a machine's centre out to its rim, along a direction.
 *
 * The exact ellipse radius rather than an approximation, because the fleet's
 * machines differ in shape on purpose — wider with memory, taller with cores — and
 * a circle's worth of clearance would leave a wide machine covered and a tall one
 * with a gap.
 */
function shrink(
  at: [number, number],
  ux: number,
  uy: number,
  size: [number, number],
  sign: number,
): [number, number] {
  const rx = size[0] / 2 + 0.09;
  const ry = size[1] / 2 + 0.09;
  const r = (rx * ry) / Math.hypot(ry * ux, rx * uy);
  return [at[0] + sign * ux * r, at[1] + sign * uy * r];
}

/**
 * Has this machine's *appearance* changed?
 *
 * The fill is bucketed to about the number of levels an eye can tell apart at the
 * size a machine is drawn, so a reducer creeping up by a hundredth of a megabyte
 * does not re-render for it.
 */
function same(a: FrameMachine, b: FrameMachine): boolean {
  return (
    a.name === b.name &&
    a.state === b.state &&
    bucket(a.memShare) === bucket(b.memShare) &&
    bucket(a.diskShare) === bucket(b.diskShare) &&
    Math.round(a.busy / 12) === Math.round(b.busy / 12) &&
    Math.round(a.queued) === Math.round(b.queued) &&
    label(a) === label(b) &&
    mb(a.freeMb) === mb(b.freeMb) &&
    mb(a.diskFreeMb) === mb(b.diskFreeMb)
  );
}

function bucket(share: number): number {
  return Math.round(share * 24);
}

function label(m: FrameMachine): string {
  return m.work.length ? `${m.work.length}:${m.work[0].method}:${m.work[0].task}` : '';
}

/** Bytes on the wire, which are a different magnitude from a machine's contents. */
export function bytes(n: number): string {
  if (n >= 1048576) return `${(n / 1048576).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(0)} kB`;
  return `${n} B`;
}

/** A size, in as few characters as will still say which one it is. */
export function mb(v: number): string {
  if (v >= 10240) return `${(v / 1024).toFixed(1)} GB`;
  if (v >= 1024) return `${(v / 1024).toFixed(2)} GB`;
  if (v >= 100) return `${v.toFixed(0)} MB`;
  if (v >= 10) return `${v.toFixed(1)} MB`;
  return `${v.toFixed(2)} MB`;
}
