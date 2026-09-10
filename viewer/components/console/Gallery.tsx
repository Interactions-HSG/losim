'use client';

/**
 * Every run there is, as something you can choose between.
 *
 * A picker with a hundred and five lines in it is a filing cabinet. What a
 * student actually wants from this page is the comparison — this design took
 * five seconds and cost 1.27; the same design with a mapper killed took seven
 * and cost 1.53 — and a comparison needs the two numbers on the same screen,
 * not one at a time behind a dropdown.
 *
 * So the cards carry what the sweep copied out of the trace and the bill: how
 * many nodes, how far apart they were, how long it took, what it cost, and
 * whether it finished. None of it is computed here. The viewer inventing its own
 * prices would be a second accountant, and two accountants disagree.
 */
import { useMemo, useState } from 'react';
import * as stylex from '@stylexjs/stylex';

import { Head, Panel } from './Shell.tsx';
import { COLOUR } from '../Ledger.tsx';
import { useConsole } from '../../lib/console.tsx';
import { BUCKETS, money } from '../../lib/ledger.ts';
import { refTime } from '../../lib/playback.ts';
import type { RunRef } from '../../lib/runs.ts';
import { Code, P } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome, font, radius, shadow } from '../../lib/tokens.stylex.ts';

const GROUPS = [
  { key: 'yours', label: 'Your runs', note: 'whatever you have run in this project' },
  { key: 'suite', label: 'Reference suite', note: 'the runs losim checks itself against' },
  { key: 'gallery', label: 'Gallery', note: 'worked examples, written to teach with' },
] as const;

export function Gallery() {
  const { runs, run, open } = useConsole();
  const [q, setQ] = useState('');
  const [only, setOnly] = useState<string>('');

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return runs.filter(
      (r) =>
        (!only || (r.from ?? 'gallery') === only)
        && (!needle
          || r.name.toLowerCase().includes(needle)
          || (r.entry ?? '').toLowerCase().includes(needle)
          || (r.simulation ?? '').toLowerCase().includes(needle)),
    );
  }, [runs, q, only]);

  const currency = runs.find((r) => r.currency)?.currency ?? 'CHF';

  return (
    <>
      <Head
        title="Runs"
        sub={
          <>
            Every trace beside this app. Open one and the clock above governs all four views of
            it — the film, the execution graph, what each node was doing, and what it had
            cost by then.
          </>
        }
        actions={
          <input
            {...stylex.props(sx.find)}
            placeholder="Filter by name, entry or simulation"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="filter runs"
          />
        }
      />

      <div {...stylex.props(ui.seg)} role="group" aria-label="whose runs">
        <button
          {...stylex.props(ui.segButton, only === '' && ui.segOn)}
          aria-pressed={only === ''}
          onClick={() => setOnly('')}
        >
          all {runs.length}
        </button>
        {GROUPS.map((g) => {
          const n = runs.filter((r) => (r.from ?? 'gallery') === g.key).length;
          if (!n) return null;
          return (
            <button
              key={g.key}
              {...stylex.props(ui.segButton, only === g.key && ui.segOn)}
              aria-pressed={only === g.key}
              onClick={() => setOnly(g.key)}
            >
              {g.label.toLowerCase()} {n}
            </button>
          );
        })}
      </div>

      {GROUPS.map((g) => {
        const some = shown.filter((r) => (r.from ?? 'gallery') === g.key);
        if (!some.length) return null;
        return (
          <Panel key={g.key} title={g.label} note={`${some.length} · ${g.note}`}>
            <div {...stylex.props(sx.cards)}>
              {some.map((r) => (
                <Card
                  key={r.name}
                  r={r}
                  here={r.name === run?.name}
                  currency={currency}
                  onOpen={() => void open(r.name, 'overview')}
                />
              ))}
            </div>
          </Panel>
        );
      })}

      {!shown.length && (
        <Panel>
          <P style={ui.muted}>
            Nothing matches <strong>{q}</strong>. Every result is named for the simulation it
            came from, so <Code>kill</Code>, <Code>scale</Code> and <Code>deadline</Code> are
            all worth trying.
          </P>
        </Panel>
      )}

    </>
  );
}

function Card({
  r,
  here,
  currency,
  onOpen,
}: {
  r: RunRef;
  here: boolean;
  currency: string;
  onOpen: () => void;
}) {
  const zones = r.zones ?? [];
  const regions = [...new Set(zones.map((z) => z.replace(/[a-z0-9]$/, '')))];
  return (
    <article {...stylex.props(sx.run, here && sx.here)}>
      <button {...stylex.props(sx.cover)} onClick={onOpen} aria-label={`open ${r.name}`}>
        <Cover nodes={r.nodes ?? 1} zones={zones} broke={r.completed === false} />
      </button>
      <div {...stylex.props(sx.in)}>
        <div {...stylex.props(sx.line)}>
          <button {...stylex.props(sx.name)} onClick={onOpen}>{r.name}</button>
          {here && <span {...stylex.props(ui.chip)}>open</span>}
        </div>
        <P style={sx.of}>
          {r.simulation ?? 'a simulation'}
          {r.entry && <span {...stylex.props(ui.muted)}> · entered at {r.entry}</span>}
        </P>
        <dl {...stylex.props(sx.dl)}>
          <div {...stylex.props(sx.pair)}>
            <dt {...stylex.props(sx.dt)}>nodes</dt>
            <dd {...stylex.props(sx.dd)}>{r.nodes ?? '—'}</dd>
          </div>
          <div {...stylex.props(sx.pair)}>
            <dt {...stylex.props(sx.dt)}>zones</dt>
            <dd {...stylex.props(sx.dd)}>
              {zones.length || '—'}
              {regions.length > 1 && <span {...stylex.props(sx.far)}> · {regions.length} regions</span>}
            </dd>
          </div>
          <div {...stylex.props(sx.pair)}>
            <dt {...stylex.props(sx.dt)}>took</dt>
            <dd {...stylex.props(sx.dd)}>{r.durationRefMs === undefined ? '—' : refTime(r.durationRefMs)}</dd>
          </div>
          <div {...stylex.props(sx.pair)}>
            <dt {...stylex.props(sx.dt)}>cost</dt>
            <dd {...stylex.props(sx.dd, sx.cost)}>
              {r.cost === undefined ? '—' : money(r.cost, r.currency ?? currency)}
            </dd>
          </div>
        </dl>
        {r.buckets && (
          <div {...stylex.props(sx.stack)} title="build · capacity · consumption · incidents">
            {BUCKETS.map((b) => {
              const v = r.buckets?.[b] ?? 0;
              if (v <= 0) return null;
              return (
                <i
                  key={b}
                  {...stylex.props(sx.slice)}
                  style={{
                    width: `${(v / Math.max(r.cost ?? 1, 1e-9)) * 100}%`,
                    background: COLOUR[b],
                  }}
                />
              );
            })}
          </div>
        )}
        {r.completed === false && <span {...stylex.props(ui.chip, sx.badChip)}>did not finish</span>}
      </div>

    </article>
  );
}

/**
 * A run, drawn small: its zones as boxes and a dot per node.
 *
 * Not decoration. Twelve dots in one box and twelve spread over three are two
 * different designs, and the difference is the thing this course is about —
 * which makes it the thing worth being able to see without opening either.
 */
function Cover({ nodes, zones, broke }: { nodes: number; zones: string[]; broke: boolean }) {
  const W = 300;
  const H = 104;
  const n = Math.max(zones.length, 1);
  const pad = 12;
  const gap = 10;
  const w = (W - pad * 2 - gap * (n - 1)) / n;
  const per = Math.ceil(nodes / n);
  const cols = Math.min(4, Math.max(1, Math.floor((w - 16) / 18) || 1));

  let left = nodes;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} {...stylex.props(sx.cvr)} aria-hidden>
      <rect x={0} y={0} width={W} height={H} {...stylex.props(sx.plate)} />
      {Array.from({ length: n }, (_, i) => {
        const x = pad + i * (w + gap);
        const here = Math.min(left, per);
        left -= here;
        return (
          <g key={i}>
            <rect
              x={x} y={12} width={w} height={H - 30} rx={6}
              {...stylex.props(sx.zone)}
            />
            {Array.from({ length: here }, (_, k) => (
              <circle
                key={k}
                cx={x + 14 + (k % cols) * 17}
                cy={28 + Math.floor(k / cols) * 17}
                r={5.5}
                {...stylex.props(broke && k === 0 && i === 0 ? sx.dotBad : sx.dot)}
                opacity={0.82}
              />
            ))}
            <text x={x + 6} y={H - 6} {...stylex.props(sx.zl)}>{zones[i] ?? 'one zone'}</text>
          </g>
        );
      })}
    </svg>
  );
}

const sx = stylex.create({
  find: {
    height: '36px',
    width: '300px',
    maxWidth: '46vw',
    paddingBlock: 0,
    paddingInline: '14px',
    font: 'inherit',
    fontSize: '13.5px',
    color: chrome.text,
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: '999px',
    boxShadow: shadow.s1,
  },
  cards: {
    display: 'grid',
    gap: '16px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(270px, 1fr))',
  },
  run: {
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
    backgroundColor: chrome.surface,
    borderWidth: '1px',
    borderStyle: 'solid',
    borderColor: chrome.border,
    borderRadius: radius.lg,
    boxShadow: { default: shadow.s1, ':hover': shadow.s2 },
    transitionProperty: 'box-shadow, border-color',
    transitionDuration: '.14s',
    transitionTimingFunction: 'ease',
  },
  /** The run that is open, named by its border rather than by a badge. */
  here: { borderColor: chrome.accent },
  cover: { display: 'block', padding: 0, borderWidth: 0, background: 'none', cursor: 'pointer' },
  in: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    paddingTop: '14px',
    paddingInline: '16px',
    paddingBottom: '16px',
  },
  line: { display: 'flex', alignItems: 'center', gap: '8px' },
  name: {
    padding: 0,
    borderWidth: 0,
    background: 'none',
    cursor: 'pointer',
    fontFamily: font.mono,
    fontSize: '13.5px',
    fontWeight: 500,
    color: { default: chrome.text, ':hover': chrome.accent },
    textAlign: 'left',
  },
  of: { margin: 0, fontSize: '12.5px', color: chrome.text2 },
  dl: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: '6px 12px',
    marginTop: '2px',
    marginRight: 0,
    marginBottom: 0,
    marginLeft: 0,
  },
  pair: { display: 'flex', justifyContent: 'space-between', gap: '8px' },
  dt: { fontSize: '11.5px', color: chrome.text3 },
  dd: {
    margin: 0,
    fontFamily: font.mono,
    fontSize: '12px',
    fontVariantNumeric: 'tabular-nums',
    color: chrome.text2,
  },
  cost: { color: chrome.text, fontWeight: 500 },
  /** More than one region is worth noticing before you read the bill. */
  far: { color: chrome.warn },
  stack: {
    display: 'flex',
    height: '6px',
    borderRadius: '3px',
    overflow: 'hidden',
    backgroundColor: chrome.surface2,
    marginTop: '2px',
  },
  slice: { display: 'block', height: '100%' },
  badChip: { color: '#fff', backgroundColor: chrome.danger, borderColor: 'transparent', alignSelf: 'flex-start' },
  cvr: { display: 'block', width: '100%', height: 'auto' },
  plate: { fill: chrome.surface2 },
  zone: { fill: chrome.surface, stroke: chrome.borderStrong },
  dot: { fill: chrome.accent },
  dotBad: { fill: chrome.danger },
  zl: { fontFamily: font.mono, fontWeight: 400, fontSize: '9.5px', fill: chrome.text3 },
});
