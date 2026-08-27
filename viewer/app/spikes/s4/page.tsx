'use client';

/**
 * S4 — is a frame-stepped recording deterministic, and does it produce a real file?
 *
 * The claim the recorder rests on is that the clock is an *input*: every frame's
 * timestamp is an argument to the encoder, so how fast the machine drew the frame
 * cannot reach the file. The cheap way to be wrong about this is to record the
 * live canvas stream, where a frame the browser failed to paint in time becomes a
 * frame missing from the video — and the failure is invisible on the machine that
 * has the spare cycles to do it, which is always the machine it was tested on.
 *
 * So this records the same scene twice — once at full speed, once with a real
 * busy-wait burning CPU on every frame — then reads both files back with the
 * decoder and compares what is actually in them: how many frames, how long, how
 * big, and at what rate. Same numbers or the claim is false.
 *
 * It is deliberately not a screenshot test. "It looked right" is what a recorder
 * says on a fast laptop right up until it is run in a Codespace.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { ALL_FORMATS, BlobSource, EncodedPacketSink, Input } from 'mediabunny';

import * as D from '../../../lib/design.ts';
import * as G from '../../../lib/glyphs.ts';
import { record, save, type Recording } from '../../../lib/record.ts';

const W = 960;
const H = 540;
const FPS = 30;
const FRAMES = 90; // three seconds — long enough for a drift to show, short enough to wait for

/** What was actually in the file, as opposed to what we asked for. */
interface Read {
  frames: number;
  duration: number;
  width: number;
  height: number;
  frameRate: number;
  bytes: number;
  codec: string;
  tookMs: number;
}

async function readBack(r: Recording): Promise<Read> {
  const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(r.blob) });
  const track = await input.getPrimaryVideoTrack();
  if (!track) throw new Error('the file has no video track');
  const sink = new EncodedPacketSink(track);
  let frames = 0;
  for await (const _packet of sink.packets()) frames++;
  const metrics = await track.computeFrameRateMetrics();
  return {
    frames,
    duration: await input.computeDuration(),
    width: await track.getDisplayWidth(),
    height: await track.getDisplayHeight(),
    frameRate: metrics.bestGuessFrameRate,
    bytes: r.blob.size,
    codec: r.codec,
    tookMs: r.tookMs,
  };
}

export default function S4() {
  const [frame, setFrame] = useState(0);
  const [note, setNote] = useState('');
  const [rows, setRows] = useState<[string, Read][]>([]);
  const [verdict, setVerdict] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [keep, setKeep] = useState<Recording | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  const takeOne = useCallback(
    async (label: string, stallMs: number) => {
      setNote(`${label}: rendering…`);
      const r = await record({
        width: W,
        height: H,
        fps: FPS,
        frames: FRAMES,
        showFrame: (f) => {
          flushSync(() => setFrame(f));
          // A real busy-wait, not a sleep. The question is what happens when the
          // machine cannot keep up with the frame rate, and a sleep that yields
          // the thread is not that — it is a machine with time to spare, waiting.
          if (stallMs) {
            const until = performance.now() + stallMs;
            while (performance.now() < until) {
              /* burn */
            }
          }
        },
        svgOf: () => svgRef.current!,
        onProgress: (done, total) => {
          if (done % 15 === 0) setNote(`${label}: ${done}/${total}`);
        },
      });
      return r;
    },
    [],
  );

  const run = useCallback(async () => {
    setBusy(true);
    setRows([]);
    setVerdict(null);
    try {
      const fast = await takeOne('fast', 0);
      const slow = await takeOne('throttled', 25);
      const a = await readBack(fast);
      const b = await readBack(slow);
      setRows([
        ['fast', a],
        ['throttled', b],
      ]);
      setKeep(fast);

      const complaints: string[] = [];
      if (a.frames !== b.frames) complaints.push(`frame count ${a.frames} vs ${b.frames}`);
      if (Math.abs(a.duration - b.duration) > 1e-6) {
        complaints.push(`duration ${a.duration.toFixed(6)}s vs ${b.duration.toFixed(6)}s`);
      }
      if (a.width !== b.width || a.height !== b.height) complaints.push('dimensions');
      if (a.frames !== FRAMES) complaints.push(`asked for ${FRAMES} frames, file holds ${a.frames}`);
      if (Math.abs(a.duration - FRAMES / FPS) > 1e-6) {
        complaints.push(`asked for ${(FRAMES / FPS).toFixed(3)}s, file is ${a.duration.toFixed(3)}s`);
      }

      const slowdown = b.tookMs / a.tookMs;
      setVerdict(
        complaints.length
          ? { ok: false, text: `FAIL — ${complaints.join('; ')}` }
          : {
              ok: true,
              text:
                `PASS — the throttled run took ${slowdown.toFixed(1)}x as long to make ` +
                `and produced the same ${a.frames} frames over the same ${a.duration.toFixed(3)}s ` +
                `at ${a.frameRate.toFixed(2)} fps, as ${a.codec}. The clock is an input.`,
            },
      );
      setNote('');
    } catch (e) {
      setVerdict({ ok: false, text: `FAIL — ${(e as Error).message}` });
      setNote('');
    } finally {
      setBusy(false);
    }
  }, [takeOne]);

  // Runnable rather than clickable: `?auto` starts it on load, so the spike can be
  // driven from a headless browser in CI and on a machine that has been throttled
  // on purpose — which is the only place its claim can actually be tested.
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    if (!new URLSearchParams(window.location.search).has('auto')) return;
    started.current = true;
    void run();
  }, [run]);

  return (
    <main style={{ padding: '2.5rem' }}>
      <h1>S4 — is a frame-stepped recording deterministic?</h1>
      <p>
        The same scene, recorded twice: once at full speed, and once with 25 ms of
        CPU burned on every frame. Both files are then read back and compared on
        what is in them rather than on how they looked. If the timestamp is really
        an argument and not a consequence, a machine that cannot keep up produces
        the same film, more slowly.
      </p>

      <p>
        <button onClick={run} disabled={busy}>
          {busy ? 'recording…' : `record ${FRAMES} frames, twice`}
        </button>{' '}
        {keep && !busy && (
          <button onClick={() => save(keep, 's4-fast')}>download the fast one</button>
        )}{' '}
        <span style={{ color: 'var(--pencil)' }}>{note}</span>
      </p>

      {verdict && (
        <p
          id="verdict"
          data-status={verdict.ok ? 'pass' : 'fail'}
          style={{ color: verdict.ok ? 'var(--ink)' : D.NARRATE, maxWidth: '52rem' }}
        >
          {verdict.text}
        </p>
      )}

      {rows.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>run</th>
              <th>frames</th>
              <th>duration</th>
              <th>rate</th>
              <th>size</th>
              <th>codec</th>
              <th>took</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([label, r]) => (
              <tr key={label}>
                <td>{label}</td>
                <td className="n">{r.frames}</td>
                <td className="n">{r.duration.toFixed(4)}s</td>
                <td className="n">{r.frameRate.toFixed(2)}</td>
                <td className="n">
                  {r.width}x{r.height}, {(r.bytes / 1024).toFixed(0)} KiB
                </td>
                <td className="n">{r.codec}</td>
                <td className="n">{(r.tookMs / 1000).toFixed(1)}s</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>the scene, at frame {frame}</h2>
      <Scene ref={svgRef} frame={frame} />
    </main>
  );
}

/**
 * A stand-in for the film, drawn from the real design system.
 *
 * Not a coloured square. What the recorder has to survive is what the film
 * actually contains — arcs, fills that move a fraction of a megabyte at a time,
 * text at the size a caption is really set in — and a scene made of rectangles
 * would prove the encoder works on rectangles.
 */
function Scene({ frame, ref }: { frame: number; ref: React.Ref<SVGSVGElement> }) {
  const t = frame / FRAMES;
  const zones = ['eu-central-1a', 'eu-central-1b'];
  const machines = Array.from({ length: 6 }, (_, i) => ({
    name: `m${i}`,
    zone: i % 2,
    x: 2.2 + (i % 3) * 3.6,
    y: i < 3 ? 1.55 : 4.0,
    // A fill that climbs and then falls back, so every level the design system
    // has a colour for is visited during the recording.
    share: Math.min(1, Math.max(0, 0.15 + 0.95 * Math.sin(Math.PI * (t + i * 0.11)))),
  }));

  return (
    <svg
      ref={ref}
      viewBox="0 0 12.4 5.6"
      width={W / 2}
      height={H / 2}
      style={{ border: `1px solid ${D.RULE}`, background: D.PAPER }}
    >
      <rect x={0} y={0} width={12.4} height={5.6} fill={D.PAPER} />
      {zones.map((z, i) => (
        <g key={z}>
          <rect
            x={0.25}
            y={0.35 + i * 2.45}
            width={11.9}
            height={2.3}
            fill={D.zoneTint(i)}
            stroke={D.ZONE_EDGE}
            strokeWidth={0.012}
            rx={0.08}
          />
          <text x={0.42} y={0.68 + i * 2.45} fontSize={0.16} fill={D.ZONE_LABEL} fontFamily="var(--sans)">
            {z}
          </text>
        </g>
      ))}

      {machines.map((m, i) => {
        const [w, h] = [D.MACHINE_W, D.MACHINE_H];
        const fill = G.liquid(w, h, m.share);
        return (
          <g key={m.name} transform={`translate(${m.x} ${m.y})`}>
            <path d={G.ellipse(w, h)} fill={D.MACHINE} stroke={D.INK} strokeWidth={0.022} />
            {fill && (
              <path d={fill} fill={D.levelColour(m.share, 1)} stroke={D.DATA_EDGE} strokeWidth={0.014} />
            )}
            <path d={G.ellipse(w, h)} fill="none" stroke={D.INK} strokeWidth={0.022} />
            <text
              textAnchor="middle"
              y={0.06}
              fontSize={0.2}
              fill={D.INK}
              fontFamily="var(--sans)"
            >
              {m.name}
            </text>
            <text
              textAnchor="middle"
              y={h / 2 + 0.26}
              fontSize={0.13}
              fill={D.PENCIL}
              fontFamily="var(--sans)"
            >
              {((1 - m.share) * 512).toFixed(0)} MB left
            </text>
            {/* One lane per vCPU, lit while the machine is working. */}
            {[0, 1, 2, 3].map((k) => (
              <rect
                key={k}
                x={-0.44 + k * 0.24}
                y={h / 2 + 0.05}
                width={0.18}
                height={0.07}
                fill={(frame + i * 3 + k) % 7 < 3 ? D.taskColour(i) : D.FAINT}
              />
            ))}
          </g>
        );
      })}

      {/* A packet in flight, whose position is a pure function of the frame. */}
      <g transform={`translate(${1.4 + 9.6 * ((t * 3) % 1)} ${2.9})`}>
        <rect
          x={-D.PACKET_W / 2}
          y={-D.PACKET_H / 2}
          width={D.PACKET_W}
          height={D.PACKET_H}
          fill={D.DATA_FILL}
          stroke={D.DATA_EDGE}
          strokeWidth={0.016}
          rx={0.03}
        />
        <text textAnchor="middle" y={-D.PACKET_H / 2 - 0.09} fontSize={0.12} fill={D.PENCIL} fontFamily="var(--sans)">
          the 1,729 · cat 402 · +1,116 more
        </text>
      </g>

      <text x={0.25} y={5.45} fontSize={0.15} fill={D.PENCIL} fontFamily="var(--sans)">
        frame {frame} of {FRAMES} — t = {(frame / FPS).toFixed(3)}s
      </text>
    </svg>
  );
}
