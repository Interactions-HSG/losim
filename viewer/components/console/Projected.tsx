'use client';

/**
 * What the run stands for, where the run was a model of something bigger.
 *
 * A scaled simulation executes a workload that fits on the machine in front of
 * you and reports the one it is a model of. Every chart on the usage page draws
 * the first — that is what a chart is, a drawing of what happened — so without
 * this panel the only figures on the screen are the small ones, and the page
 * answers a question nobody asked. `0.04 MB` out of a cluster declared at
 * forty-eight thousand frames is not wrong; it is the wrong number.
 *
 * **Nothing here is calculated.** The laws are fitted, evaluated, aggregated
 * across machines and error-barred in `dissaly.scale`, and every figure below is
 * read out of the trace as the engine wrote it (D9). A projection recomputed in
 * TypeScript agreed with the engine to three digits and disagreed on the fourth,
 * which is one program too many holding an opinion about one number.
 *
 * What this file *does* decide is how a number is written: megabytes into
 * gigabytes, reference milliseconds into seconds, a multiplicative error bar
 * into a band. See `lib/units.ts` for where that line is drawn.
 */
import * as stylex from '@stylexjs/stylex';

import { Panel } from './Shell.tsx';
import { refTime } from '../../lib/playback.ts';
import { band, size } from '../../lib/units.ts';
import type { Projection, Trace } from '../../lib/trace.ts';
import { P, Table, Td, Th } from '../../lib/text.tsx';
import { ui } from '../../lib/ui.stylex.ts';
import { chrome, font } from '../../lib/tokens.stylex.ts';

/**
 * What each of the engine's resource names is, in words.
 *
 * The engine's own name is what is shown, because it is the name the notes, the
 * assumptions and `dissaly simulate`'s own output all use, and a reader who has
 * to map between two vocabularies to follow one number has been given a puzzle
 * instead of an answer. The gloss sits under it.
 */
const MEANS: Record<string, string> = {
  wireMb: 'sent across the wire',
  diskMb: 'written to disk',
  memoryMb: 'held in memory at the peak',
  allocMb: 'allocated over the whole run',
  makespanRefMs: 'from the first call to the last',
};

/** A figure in the unit its resource is measured in. */
function figure(resource: string, v: number): string {
  if (resource.endsWith('Mb')) return size(v);
  if (resource.endsWith('RefMs')) return refTime(v);
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export function Projected({ trace }: { trace: Trace }) {
  const model = trace.scaled;
  if (!model) return null;

  const nodes = trace.nodes.filter((n) => model.perNode.has(n.name));
  // The resources the cluster was fitted for, in the engine's order, kept as the
  // column order for the per-node table too — so a reader comparing a node with
  // the cluster above it is comparing the same column.
  const columns = model.projections.map((p) => p.resource);

  return (
    <>
      <Panel
        title="At full size"
        note={`a model of ${model.fullUnits.toLocaleString()} units, fitted from ${model.gridRuns} probe runs`}
        flush
      >
        <div {...stylex.props(sx.scroll)}>
          <Table>
            <thead>
              <tr>
                <Th>Resource</Th>
                <Th style={sx.right}>Measured at {model.units.toLocaleString()}</Th>
                <Th style={sx.right}>Projected at {model.fullUnits.toLocaleString()}</Th>
                <Th style={sx.right}>Band</Th>
                <Th>A function of</Th>
              </tr>
            </thead>
            <tbody>
              {model.projections.map((p) => (
                <Row key={p.resource} p={p} span={5} />
              ))}
            </tbody>
          </Table>
        </div>
        {model.notes.length > 0 && (
          <div {...stylex.props(sx.pad)}>
            {model.notes.map((n) => (
              <P key={n} style={sx.note}>{n}</P>
            ))}
          </div>
        )}
      </Panel>

      {nodes.length > 0 && (
        <Panel
          title="At full size, node by node"
          note="what the cluster figure above was assembled from"
          flush
        >
          <div {...stylex.props(sx.scroll)}>
            <Table>
              <thead>
                <tr>
                  <Th>Node</Th>
                  {columns.map((c) => (
                    <Th key={c} style={sx.right}>{c}</Th>
                  ))}
                  <Th style={sx.right}>Memory it has</Th>
                  <Th style={sx.right}>Disk it has</Th>
                </tr>
              </thead>
              <tbody>
                {nodes.map((n) => {
                  const mine = model.perNode.get(n.name) ?? [];
                  // The caps it would really be given, not the shrunk pair the
                  // probe ran under. Comparing a full-size projection against a
                  // probe-size cap is the one comparison on this page that can
                  // be read backwards, and it would say every node was about to
                  // fall over.
                  const caps = model.fullCaps[n.name] ?? [];
                  return (
                    <tr key={n.name}>
                      <Td style={sx.id}>{n.name}</Td>
                      {columns.map((c) => {
                        const p = mine.find((q) => q.resource === c);
                        return (
                          <Td key={c} num style={sx.right}>
                            {p === undefined ? <span {...stylex.props(ui.muted)}>—</span>
                              : p.projected === null ? <span {...stylex.props(ui.muted)}>not projected</span>
                              : figure(c, p.projected)}
                          </Td>
                        );
                      })}
                      <Td num style={sx.right}>{caps[0] === undefined ? '—' : size(caps[0])}</Td>
                      <Td num style={sx.right}>{caps[1] === undefined ? '—' : size(caps[1])}</Td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </div>
        </Panel>
      )}
    </>
  );
}

/**
 * One resource, and — where there is one — the condition its number came with.
 *
 * The condition is a row of its own rather than a tooltip or a footnote marker,
 * because it is the part a reader is entitled to disagree with. A projection
 * fitted from the upper half of a bent ladder is a different claim from one
 * fitted from the whole of it, and the two look identical until somebody says
 * which happened.
 */
function Row({ p, span }: { p: Projection; span: number }) {
  const bar = band(p.errorBar);
  return (
    <>
      <tr>
        <Td style={sx.id}>
          {p.resource}
          {MEANS[p.resource] && <span {...stylex.props(sx.gloss)}>{MEANS[p.resource]}</span>}
        </Td>
        <Td num style={sx.right}>{figure(p.resource, p.observed)}</Td>
        <Td num style={sx.right}>
          {p.projected === null
            ? <span {...stylex.props(ui.muted)}>not projected</span>
            : <strong {...stylex.props(sx.big)}>{figure(p.resource, p.projected)}</strong>}
        </Td>
        <Td num style={sx.right}>
          {bar ?? <span {...stylex.props(ui.muted)}>exact</span>}
        </Td>
        <Td style={ui.muted}>
          {p.of}
          {/* Where the cluster figure came from. Memory and disk peak — one
              machine runs out, not the average of them — so a law fitted
              against the pre-aggregated peak follows whichever machine happened
              to be largest at probe size, and understated the cluster by three
              orders of magnitude until it was assembled per node instead. */}
          {p.from === 'machines' && <span {...stylex.props(sx.gloss)}>assembled per node</span>}
        </Td>
      </tr>
      {(p.assumed || p.refused) && (
        <tr>
          <Td colSpan={span} style={sx.why}>
            <span {...stylex.props(sx.tag)}>{p.assumed ? 'assuming' : 'not projected'}</span>
            {p.assumed ?? p.refused}
          </Td>
        </tr>
      )}
    </>
  );
}

const sx = stylex.create({
  scroll: { overflowX: 'auto', paddingTop: 0, paddingInline: '20px', paddingBottom: '8px' },
  pad: { paddingTop: 0, paddingInline: '20px', paddingBottom: '4px' },
  right: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  id: { fontFamily: font.mono, fontWeight: 500 },
  big: { fontSize: '14px' },
  gloss: { display: 'block', fontFamily: font.sans, fontWeight: 400, fontSize: '11.5px', color: chrome.text3 },
  note: { fontSize: '12.5px', color: chrome.text2 },
  why: { fontSize: '12.5px', color: chrome.text2, paddingTop: 0 },
  tag: {
    display: 'inline-block',
    marginRight: '8px',
    padding: '1px 6px',
    borderRadius: '3px',
    backgroundColor: chrome.border,
    color: chrome.text2,
    fontFamily: font.mono,
    fontSize: '11px',
  },
});
