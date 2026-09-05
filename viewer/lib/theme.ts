'use client';

/**
 * What the film is drawn *on*, as opposed to what it means.
 *
 * The design system (lib/design.ts) decides what things mean: amber is a machine
 * near its cap, red is one past it, a task keeps its hue for the whole run. None
 * of that is negotiable and none of it is here.
 *
 * What *is* here is the ground those meanings sit on — the surface, the strokes,
 * the two or three greys that carry text — and that follows the viewer's theme,
 * because a picture that stays on white paper inside a dark application looks
 * like a scan of something rather than a thing that is running.
 *
 * Passed down as plain values rather than read through CSS variables, and that is
 * necessary: the recorder serialises the `<svg>` into a standalone image, and
 * a standalone image has no page to inherit `var(--ink)` from. Everything the
 * film draws has to be a literal colour by the time it reaches the DOM.
 */
import { useEffect, useState } from 'react';

import * as D from './design.ts';

export interface Theme {
  dark: boolean;
  /** The ground the fleet stands on. */
  surface: string;
  /** A machine's body, and the shade it is lit from. */
  machine: string;
  machineLow: string;
  /** Every stroke of the system itself. */
  ink: string;
  /** Read second: captions, units, instance types. */
  pencil: string;
  /** Read last, or not at all: hairlines, wires, empty lanes. */
  rule: string;
  faint: string;
  /** Data at rest and in flight. */
  dataFill: string;
  dataFillLow: string;
  dataEdge: string;
  zones: readonly string[];
  zoneEdge: string;
  zoneLabel: string;
  /** How hard a shadow may be, if at all. */
  shadow: number;
}

export const LIGHT: Theme = {
  dark: false,
  surface: '#fbfbfa',
  machine: '#ffffff',
  machineLow: '#f4f5f6',
  ink: '#1a1d21',
  pencil: '#6b7280',
  rule: '#c9cdd4',
  faint: '#e9ebee',
  dataFill: '#d3e7da',
  dataFillLow: '#bcd9c6',
  dataEdge: '#4f8f6c',
  zones: ['#eef2f7', '#f4f1ea', '#eef4f0', '#f5eff2', '#f0f0f6', '#f4f2ea'],
  zoneEdge: '#dfe3e9',
  zoneLabel: '#98a0ab',
  shadow: 0.16,
};

/**
 * Not the light palette inverted.
 *
 * A dark film needs its data *lighter* than its ground where a light one needs it
 * darker, and the pale green that recedes correctly on paper turns into a glowing
 * mint on black. So the greens are deepened and the greys re-picked for contrast
 * against the surface they actually sit on, and the result is checked by looking
 * rather than computed by inverting.
 */
export const DARK: Theme = {
  dark: true,
  surface: '#111419',
  machine: '#1b1f26',
  machineLow: '#161a20',
  ink: '#e6e9ee',
  pencil: '#98a1ad',
  rule: '#333a44',
  faint: '#222831',
  dataFill: '#224532',
  dataFillLow: '#1a3628',
  dataEdge: '#4f9d73',
  zones: ['#161b23', '#1c1a16', '#151d19', '#1e171c', '#181822', '#1c1b16'],
  zoneEdge: '#252b34',
  zoneLabel: '#69727e',
  shadow: 0.5,
};

/**
 * A task's hue, lifted for a dark ground.
 *
 * The eight hues were chosen against paper. On a dark surface the same values are
 * muddy, so they are brightened — but by the same amount, in the same order, so
 * that a task keeps its identity between the two and two people looking at
 * different themes are still talking about the same green.
 */
export function taskColour(theme: Theme, n: number | null | undefined): string {
  const base = D.taskColour(n);
  return theme.dark ? lift(base, 0.26) : base;
}

export function warn(theme: Theme): string {
  return theme.dark ? lift(D.WARN, 0.1) : D.WARN;
}

export function alarm(theme: Theme): string {
  return theme.dark ? lift(D.ALARM, 0.22) : D.ALARM;
}

export function chill(theme: Theme): string {
  return theme.dark ? lift(D.CHILL, 0.12) : D.CHILL;
}

/** Towards white, in sRGB. Good enough for a hue that only has to stay itself. */
function lift(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16);
  const r = (n >> 16) & 255;
  const g = (n >> 8) & 255;
  const b = n & 255;
  const up = (v: number) => Math.round(v + (255 - v) * amount);
  return `#${((up(r) << 16) | (up(g) << 8) | up(b)).toString(16).padStart(6, '0')}`;
}

/**
 * Which theme the viewer is in.
 *
 * Starts light on the server and on the first client render, then corrects —
 * guessing during hydration is how a page ends up with a flash of the wrong
 * colours and a React mismatch warning at the same time.
 */
export function useTheme(): Theme {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const q = window.matchMedia('(prefers-color-scheme: dark)');
    setDark(q.matches);
    const on = (e: MediaQueryListEvent) => setDark(e.matches);
    q.addEventListener('change', on);
    return () => q.removeEventListener('change', on);
  }, []);
  return dark ? DARK : LIGHT;
}
