/**
 * The visual language of a losim picture, as data.
 *
 * Nothing here imports d3, or React, or anything else. These are tokens —
 * colours, sizes, proportions and the rules about when to use which — and every
 * view reads them. A palette that lived inside a component would be a palette
 * the next component had to reimplement, and two pictures that must agree but
 * are written twice are two pictures that will not.
 *
 * The language is borrowed from the figure every distributed-systems course
 * already draws on a whiteboard, and its one real idea is **layer discipline**:
 *
 *     system      thin black on white, data in pale green.  What exists.
 *     mechanics   small italic serif, parenthesised.        What an edge does.
 *     narration   bold red, numbered.                       What you are being shown.
 *
 * Red is never part of the system. It is the lecturer's pen, and the moment it
 * starts filling shapes it stops meaning "look here" and the picture goes flat.
 */

// --------------------------------------------------------------------- colour

export const PAPER = '#F7F7F5'; // the page, not a screen: this is a figure, not a dashboard
export const INK = '#1A1A1A'; // every stroke of the system itself
export const PENCIL = '#5A5A5A'; // captions, units, anything you read second
export const RULE = '#BFBFBF'; // hairlines, axes, the grid a timeline hangs on
export const FAINT = '#E4E4E0'; // fills that must recede all the way to the paper

export const MACHINE = '#FFFFFF'; // a machine is white: what fills it is what it holds

export const DATA_FILL = '#DCE9DF'; // data at rest — a file, a bucket, a chunk
export const DATA_EDGE = '#5C8F70';
export const TAB = '#2F6B4A'; // the dark tab that names a document
export const FLOW = '#4E9160'; // the block arrows: bulk moving through the system

export const NARRATE = '#D6392B'; // the lecturer's pen. Text and leader lines only.

export const WARN = '#E8A33D'; // a machine approaching its cap, or warned it is going
export const ALARM = '#C4342A'; // a machine past it, or gone
export const CHILL = '#7C93A8'; // frozen: still there, answering nothing

// ------------------------------------------------------------------- tasks
//
// One hue per unit of work, so that two things happening on one machine are two
// things rather than a busier machine. A fleet is only interesting because work
// overlaps, and a picture that draws every call in the same ink has thrown away
// the one property worth watching.
//
// Chosen against the meaning colours rather than for prettiness: nothing here is
// allowed near WARN's amber or ALARM's red, because those two say a machine is in
// trouble and a task that happened to be drawn in amber would say it too. These
// are mid-tone, evenly spaced round the wheel, and distinguishable at the size a
// lane is actually drawn.
export const TASKS = [
  '#3C6E9F', // blue
  '#4F8A5B', // green
  '#8E6BA8', // violet
  '#3E8E8A', // teal
  '#96654A', // brown
  '#B0567F', // magenta
  '#5C7A2E', // olive
  '#5B6BB5', // indigo
] as const;

/** The ink for one unit of work. Stable, so a task keeps its colour. */
export function taskColour(n: number | null | undefined): string {
  if (n === null || n === undefined) return PENCIL;
  const i = Math.trunc(n) % TASKS.length;
  return TASKS[i < 0 ? i + TASKS.length : i];
}

// Ordinary work is ink; work that has been slowed down is not a different colour
// but a different *texture*, because a degraded machine is not in trouble — it is
// doing exactly what it should, more slowly, and colouring it like a fault would
// say otherwise.
export const HATCH = '#9AA3AE';

// Every state a machine can be in, and the one colour that says so. Kept here
// rather than in the views so that "amber means near the cap" is a fact about
// losim and not a coincidence between two files.
export const LEVEL_OK = DATA_FILL;
export const LEVEL_WARN = WARN;
export const LEVEL_FULL = ALARM;
export const WARN_AT = 0.75; // of cap. Below this a machine is simply holding things.

// What every state a machine can be in is drawn as.
//
//   alive      white, filled to what it holds, lanes lit while it works
//   degraded   the same, hatched — still working, and slower
//   frozen     dashed, contents kept, lanes dark: it will answer again
//   dead       dashed, contents gone, struck through: it will not
//   reclaiming amber tag counting down — a spot machine says it is going first,
//              and that notice is the whole lesson
export const STATES = ['alive', 'degraded', 'frozen', 'dead', 'reclaiming'] as const;
export type State = (typeof STATES)[number];

// ------------------------------------------------------------------ proportion
//
// In frame units. The Python renderer used manim's 14.22 x 8 frame; the browser
// scales the same numbers into its viewBox, so a machine is the same fraction of
// the picture in both, and the layout port can be diffed number for number.

export const MACHINE_W = 2.05;
export const MACHINE_H = 1.02;

// A machine is drawn the size it is: **wider with more memory, taller with more
// cores.** Two axes because the two resources fail differently and a fleet is
// usually short of one of them — a c5.4xlarge and an r5.large are not "one bigger
// than the other", they are bigger in different directions, and a scenario that
// puts the map on one and the shuffle on the other is making exactly that
// distinction.
//
// Compressed hard, and against the fleet's own median rather than against an
// absolute. A fleet spanning a1.nano to c5.4xlarge covers sixty-four times the
// memory, and drawn linearly the small machines vanish. The exponents below turn
// that sixty-four-fold spread into about two and a half, which is as much as a
// picture can carry while keeping the smallest machine legible.
export const SIZE_BY_MEMORY = 0.3; // width
export const SIZE_BY_CORES = 0.34; // height
export const SIZE_MIN = 0.74;
export const SIZE_MAX = 1.5;

/** How wide and how tall to draw one machine, relative to its fleet. */
export function sizeOf(
  memoryMb: number,
  vcpu: number,
  medianMemory: number,
  medianVcpu: number,
): [number, number] {
  const scaled = (value: number, middle: number, power: number): number => {
    if (!value || !middle || value <= 0 || middle <= 0) return 1.0;
    return Math.max(SIZE_MIN, Math.min(SIZE_MAX, Math.pow(value / middle, power)));
  };
  return [
    MACHINE_W * scaled(memoryMb, medianMemory, SIZE_BY_MEMORY),
    MACHINE_H * scaled(vcpu, medianVcpu, SIZE_BY_CORES),
  ];
}

// Zones are drawn, not implied. A machine's availability zone decides what every
// call it makes costs — same-zone latency or cross-zone latency, free or billed —
// so it is the one fact about a fleet that a picture of the fleet must not leave
// to the reader to remember from the YAML.
export const ZONE_EDGE = '#C9CFD6';
export const ZONE_LABEL = '#7C8794';

// One tint per zone, because "which of these is a different place" is a question
// the reader asks constantly and counting bands is a slow way to answer it. Very
// pale on purpose: a zone is the ground the fleet stands on and must stay behind
// every machine drawn on it — the moment a background competes with a fill it
// starts to look like it means something about capacity, which is the one thing
// it must never say.
export const ZONE_TINTS = ['#EDF1F5', '#F1F0EA', '#ECF2EE', '#F3EEF1', '#EFEFF4', '#F2F1E9'] as const;
export const ZONE_TINT = ZONE_TINTS[0];

export function zoneTint(i: number): string {
  const k = i % ZONE_TINTS.length;
  return ZONE_TINTS[k < 0 ? k + ZONE_TINTS.length : k];
}

export const PACKET_W = 0.42;
export const PACKET_H = 0.3;
export const DOC_W = 1.05;
export const DOC_H = 1.32;
export const DOC_FOLD = 0.26; // the folded corner, as a fraction of width
export const TAB_W = 0.52;
export const TAB_H = 0.26;

export const STROKE_SYSTEM = 2.0;
export const STROKE_HAIR = 1.0;
export const STROKE_EDGE = 1.6;

// ----------------------------------------------------------------------- type

// Preference lists. In the browser these become CSS font stacks, so the fallback
// happens in the font engine rather than being resolved here.
//
// **Georgia, Times New Roman, Palatino, PT Serif and Baskerville are deliberately
// absent.** Measured, in the Python renderer: pango collapses their word spaces —
// "near its cap, queueing" comes out "nearitscap,queueing". The browser does not
// have that bug, but the list is kept as it was so that a picture rendered from
// this design system looks the same wherever it is rendered. Charter was cut by
// Matthew Carter for low-resolution output, which is the register this whole
// language is in.
export const SANS = ['Helvetica Neue', 'Inter', 'Helvetica', 'DejaVu Sans', 'Liberation Sans', 'Arial'];
export const SERIF = ['Charter', 'XCharter', 'Bitstream Charter', 'Source Serif 4', 'DejaVu Serif', 'Liberation Serif', 'Noto Serif'];

/** A preference list as a CSS font stack. */
export function stack(preferred: string[], generic: string): string {
  return preferred.map((f) => (f.includes(' ') ? `"${f}"` : f)).join(', ') + ', ' + generic;
}

export const SIZE_TITLE = 34;
export const SIZE_SUBTITLE = 19;
export const SIZE_MACHINE = 24;
export const SIZE_NARRATE = 24;
export const SIZE_CAPTION = 18;
export const SIZE_EDGE = 17; // the parenthesised mechanics: (3) read
export const SIZE_TAG = 15; // tabs, units, the small print on a document

// ------------------------------------------------------------------- meaning

/**
 * What colour a machine's contents are, given what it is allowed to hold.
 *
 * Three states rather than a gradient, because the question anyone actually
 * asks is "which of these is in trouble" and a gradient answers it slowly.
 */
export function levelColour(held: number, cap: number): string {
  if (cap <= 0) return LEVEL_OK;
  const share = held / cap;
  if (share >= 1.0) return LEVEL_FULL;
  if (share >= WARN_AT) return LEVEL_WARN;
  return LEVEL_OK;
}

/** How full to draw a machine, clamped so that over-full still reads as full. */
export function levelShare(held: number, cap: number): number {
  if (cap <= 0) return 0.0;
  return Math.max(0.0, Math.min(1.0, held / cap));
}
