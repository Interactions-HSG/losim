'use client';

/**
 * Three views of one tree, sharing one selection and one axis.
 *
 * The film is what makes someone want to look. This is what makes them
 * understand — so where effort is spent unevenly, it is spent here.
 *
 * **A · Waterfall** — causality, the primary view.
 * **B · Swimlanes** — place: what was this machine doing, and when did the work move.
 * **C · Rollup** — what the job is made of.
 *
 * One time window across all three, so zoom and pan are a single transform; one
 * selected span, so hovering a row lights the same call in the film, the same
 * machine in the panel, and the same instant on the scrubber. Clicking seeks.
 *
 * The filters are the ones that find something rather than the ones that are
 * easy to write: by machine, by method, by status, and **slower than** — which
 * is how a straggler is *found* rather than noticed.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { Rollup, BYS, type By } from './spans/Rollup.tsx';
import { Swimlanes } from './spans/Swimlanes.tsx';
import { Waterfall } from './spans/Waterfall.tsx';
import { ms, SpanTree, type Node } from '../lib/spans.ts';
import type { Theme } from '../lib/theme.ts';
import type { Trace } from '../lib/trace.ts';

type View = 'waterfall' | 'swimlanes' | 'rollup';

const GUTTER = 320;

export function Spans({
  trace,
  theme,
  t,
  onSeek,
  hovered,
  onHoverMachine,
}: {
  trace: Trace;
  theme: Theme;
  t: number;
  onSeek: (t: number) => void;
  hovered: string | null;
  onHoverMachine: (m: string | null) => void;
}) {
  const tree = useMemo(() => new SpanTree(trace), [trace]);
  const [view, setView] = useState<View>('waterfall');
  // Which of the three, and what is being filtered, are part of what someone is
  // pointing at — "look at the failures in mr-kill-mapper" is a link, not an
  // instruction to go and click three things.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const sv = q.get('sv');
    if (sv === 'waterfall' || sv === 'swimlanes' || sv === 'rollup') setView(sv);
    if (q.get('fail') === '1') setFailing(true);
    const m = q.get('method');
    if (m) setQuery(m);
  }, []);
  const [by, setBy] = useState<By>('method');
  const [collapsed, setCollapsed] = useState<ReadonlySet<number>>(new Set());
  const [selected, setSelected] = useState<number | null>(null);
  const [machine, setMachine] = useState('');
  const [query, setQuery] = useState('');
  const [failing, setFailing] = useState(false);
  const [slower, setSlower] = useState(0);
  const [critOnly, setCritOnly] = useState(false);
  const [win, setWin] = useState<[number, number]>([0, trace.duration]);
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(1100);
  const [height, setHeight] = useState(420);

  useEffect(() => setWin([0, trace.duration]), [trace]);

  useEffect(() => {
    const url = new URL(window.location.href);
    if (view !== 'waterfall') url.searchParams.set('sv', view);
    else url.searchParams.delete('sv');
    if (failing) url.searchParams.set('fail', '1');
    else url.searchParams.delete('fail');
    if (query) url.searchParams.set('method', query);
    else url.searchParams.delete('method');
    window.history.replaceState(null, '', url);
  }, [view, failing, query]);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      setWidth(el.clientWidth);
      setHeight(Math.max(200, el.clientHeight));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const [w0, w1] = win;
  const left = view === 'waterfall' ? GUTTER : 100;
  const x = useCallback(
    (at: number) => left + ((at - w0) / Math.max(1e-6, w1 - w0)) * Math.max(1, width - left - 12),
    [left, w0, w1, width],
  );

  const keep = useMemo(() => {
    const any = machine || query || failing || slower > 0 || critOnly;
    if (!any) return undefined;
    const q = query.toLowerCase();
    return (n: Node) =>
      (!machine || n.span.vm === machine || n.to === machine) &&
      (!q || n.method.toLowerCase().includes(q) || n.span.label.toLowerCase().includes(q)) &&
      (!failing || !n.ok) &&
      (slower <= 0 || n.t1 - n.t0 >= slower) &&
      (!critOnly || tree.critical.has(n.id));
  }, [machine, query, failing, slower, critOnly, tree]);

  const rows = useMemo(() => tree.rows(collapsed, keep), [tree, collapsed, keep]);

  const toggle = useCallback((id: number) => {
    setCollapsed((was) => {
      const next = new Set(was);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // Zoom about the cursor, pan by dragging. One transform, three views.
  const onWheel = (e: React.WheelEvent) => {
    if (!e.ctrlKey && !e.metaKey && Math.abs(e.deltaX) < Math.abs(e.deltaY)) return;
    e.preventDefault();
    const rect = box.current?.getBoundingClientRect();
    if (!rect) return;
    const span = w1 - w0;
    if (e.ctrlKey || e.metaKey) {
      const at = w0 + ((e.clientX - rect.left - left) / Math.max(1, width - left - 12)) * span;
      const k = Math.exp(e.deltaY * 0.002);
      const a = Math.max(0, at - (at - w0) * k);
      const b = Math.min(trace.duration, at + (w1 - at) * k);
      if (b - a > trace.duration / 5000) setWin([a, b]);
    } else {
      const by = (e.deltaX / Math.max(1, width - left)) * span;
      setWin([Math.max(0, w0 + by), Math.min(trace.duration, w1 + by)]);
    }
  };

  const zoomed = w0 > 0.5 || w1 < trace.duration - 0.5;

  return (
    <div className="spans">
      <div className="sbar card">
        <div className="seg" role="group" aria-label="view">
          {(['waterfall', 'swimlanes', 'rollup'] as View[]).map((v) => (
            <button key={v} aria-pressed={view === v} onClick={() => setView(v)}>
              {v}
            </button>
          ))}
        </div>

        {view === 'rollup' ? (
          <div className="seg" role="group" aria-label="gather by">
            {BYS.map((b) => (
              <button key={b} aria-pressed={by === b} onClick={() => setBy(b)}>
                {b}
              </button>
            ))}
          </div>
        ) : (
          <>
            <select value={machine} onChange={(e) => setMachine(e.target.value)} aria-label="machine">
              <option value="">every machine</option>
              {trace.machines.map((m) => (
                <option key={m.name} value={m.name}>
                  {m.name}
                </option>
              ))}
            </select>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="method"
              aria-label="method"
              size={10}
            />
            <button className="btn" aria-pressed={failing} onClick={() => setFailing(!failing)}>
              failed only
            </button>
            <button className="btn" aria-pressed={critOnly} onClick={() => setCritOnly(!critOnly)}>
              critical path
            </button>
            <label className="slow">
              slower than
              <input
                type="number"
                value={slower || ''}
                onChange={(e) => setSlower(Number(e.target.value) || 0)}
                placeholder="0"
                size={5}
              />
              ms
            </label>
          </>
        )}

        <span className="count muted">
          {view === 'rollup' ? `${tree.flat.length} spans` : `${rows.length} of ${tree.flat.length} spans`}
        </span>
        {zoomed && (
          <button className="btn" onClick={() => setWin([0, trace.duration])}>
            {ms(w1 - w0)} shown — reset
          </button>
        )}
      </div>

      <div className="sbody card" ref={box} onWheel={onWheel}>
        {view === 'waterfall' && (
          <Waterfall
            rows={rows}
            x={x}
            width={width}
            height={height}
            theme={theme}
            critical={tree.critical}
            collapsed={collapsed}
            onToggle={toggle}
            selected={selected}
            onSelect={setSelected}
            onHoverMachine={onHoverMachine}
            t={t}
            onSeek={onSeek}
          />
        )}
        {view === 'swimlanes' && (
          <Swimlanes
            tree={tree}
            trace={trace}
            x={x}
            width={width}
            height={height}
            theme={theme}
            critical={tree.critical}
            selected={selected}
            onSelect={setSelected}
            hovered={hovered}
            onHoverMachine={onHoverMachine}
            t={t}
            onSeek={onSeek}
          />
        )}
        {view === 'rollup' && (
          <Rollup tree={tree} trace={trace} by={by} theme={theme} height={height} onHoverMachine={onHoverMachine} />
        )}
      </div>

      <p className="hint muted">
        Click a span to seek the film to it. ⌘-scroll to zoom the axis, shift-scroll to pan.
        The outlined chain is the critical path — at every level, the child that finished last,
        which is what the makespan is actually made of.
      </p>

      <style>{`
        .spans { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }
        .sbar {
          display: flex; align-items: center; gap: 8px; padding: 7px 10px;
          flex: none; flex-wrap: wrap;
        }
        .sbar .count { font-size: 11.5px; margin-left: auto; }
        .slow { font-size: 11.5px; color: var(--text-3); display: flex; align-items: center; gap: 5px; }
        .slow input { width: 62px; }
        .sbody { flex: 1; min-height: 0; overflow: hidden; padding: 0; position: relative; }
        .hint { font-size: 11px; flex: none; margin: 0; }
      `}</style>
    </div>
  );
}
