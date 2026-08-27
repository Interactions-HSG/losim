'use client';

/**
 * S1 — the glyphs, drawn from the TypeScript port.
 *
 * The other half of the check. `node viewer/spikes/s1-glyphs.ts` already proves
 * every path string is identical to the Python renderer's, character for
 * character, over 1,350 glyphs — which settles whether the port is *faithful*.
 * It cannot settle whether the geometry it is faithful to is right, and that is
 * a thing to look at rather than to assert.
 *
 * The fill row is the one to read. `liquid`'s large-arc flag turns over at
 * exactly half, and when it is wrong everything past 0.5 draws as a lens
 * floating in the middle of the machine rather than as a machine nearly full.
 * In any single cell that looks like a design choice; across a row it is
 * obviously a bug, which is why they are drawn as a row.
 */
import * as D from '../../../lib/design.ts';
import * as G from '../../../lib/glyphs.ts';

const W = D.MACHINE_W;
const H = D.MACHINE_H;

function Cell({ title, children, w, h }: { title: string; children: React.ReactNode; w: number; h: number }) {
  return (
    <figure style={{ margin: 0, width: '9rem' }}>
      <svg
        viewBox={`${-w / 2 - 0.3} ${-h / 2 - 0.4} ${w + 0.6} ${h + 0.8}`}
        style={{ width: '100%', height: '5rem' }}
      >
        {children}
      </svg>
      <figcaption style={{ color: D.PENCIL, fontSize: 12, textAlign: 'center' }}>{title}</figcaption>
    </figure>
  );
}

function Rim({ fill = D.MACHINE }: { fill?: string }) {
  return <path d={G.ellipse(W, H)} fill={fill} stroke={D.INK} strokeWidth={0.03} />;
}

export default function S1() {
  const [bars, weight] = G.hatch(W, H);
  const [sheet, corner] = G.document(D.DOC_W, D.DOC_H);

  return (
    <main style={{ padding: '2.5rem' }}>
      <h1>S1 — the glyphs, drawn from the TypeScript port</h1>
      <p>
        The path strings are already proved identical to the Python renderer&rsquo;s —{' '}
        <code>node viewer/spikes/s1-glyphs.ts</code>, 1,350 of them. This is the other
        half: that the geometry they agree on is the geometry that was wanted. Read
        the fill row. If the large-arc flag were wrong, everything past 0.5 would
        draw as a lens floating in the middle rather than as a machine nearly full.
      </p>

      <h2>what a machine is holding</h2>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem' }}>
        {Array.from({ length: 11 }, (_, i) => i / 10).map((share) => {
          const fill = G.liquid(W, H, share);
          return (
            <Cell key={share} title={`liquid ${share.toFixed(1)}`} w={W} h={H}>
              <Rim />
              {fill && (
                <path d={fill} fill={D.levelColour(share, 1)} stroke={D.DATA_EDGE} strokeWidth={0.02} />
              )}
              <Rim fill="none" />
            </Cell>
          );
        })}
      </div>

      <h2>and what has gone wrong with it</h2>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem' }}>
        <Cell title="hatch — slowed" w={W} h={H}>
          <Rim />
          {bars.map((d, i) => (
            <path key={i} d={d} stroke={D.HATCH} strokeWidth={weight} fill="none" />
          ))}
          <Rim fill="none" />
        </Cell>
        <Cell title="struck — dead" w={W} h={H}>
          <Rim />
          {G.struck(W, H).map((d, i) => (
            <path key={i} d={d} stroke={D.ALARM} strokeWidth={0.04} />
          ))}
        </Cell>
        <Cell title="overflow — past its cap" w={W} h={H}>
          <Rim />
          <path d={G.overflow(W, H)} stroke={D.ALARM} strokeWidth={0.04} fill="none" />
        </Cell>
      </div>

      <h2>data, at rest and moving</h2>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem' }}>
        <Cell title="document" w={D.DOC_W} h={D.DOC_H}>
          <path d={sheet} fill={D.DATA_FILL} stroke={D.DATA_EDGE} strokeWidth={0.02} />
          <path d={corner} fill={D.PAPER} stroke={D.DATA_EDGE} strokeWidth={0.02} />
          {G.ruleLines(D.DOC_W, D.DOC_H, 6).map((d, i) => (
            <path key={i} d={d} stroke={D.DATA_EDGE} strokeWidth={0.015} fill="none" />
          ))}
        </Cell>
        <Cell title="block arrow" w={2.0} h={1.1}>
          <path d={G.blockArrow(2.0, 0.5)} fill={D.FLOW} />
        </Cell>
        <Cell title="packet" w={1.0} h={0.6}>
          <rect
            x={-D.PACKET_W / 2}
            y={-D.PACKET_H / 2}
            width={D.PACKET_W}
            height={D.PACKET_H}
            rx={0.04}
            fill={D.DATA_FILL}
            stroke={D.DATA_EDGE}
            strokeWidth={0.02}
          />
        </Cell>
      </div>

      <h2>the fleet&rsquo;s own sizes</h2>
      <p>
        A machine is drawn the size it is: wider with more memory, taller with more
        cores, and against the fleet&rsquo;s own median rather than an absolute — so
        the picture answers &ldquo;which of these is the big one&rdquo; rather than
        &ldquo;how many gigabytes is this&rdquo;.
      </p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem', alignItems: 'center' }}>
        {[
          ['t3.nano', 512, 2],
          ['m5.large', 8192, 2],
          ['c5.2xlarge', 16384, 8],
          ['r5.4xlarge', 131072, 16],
        ].map(([name, mem, cpu]) => {
          const [w, h] = D.sizeOf(mem as number, cpu as number, 8192, 4);
          return (
            <Cell key={name as string} title={`${name} ${w.toFixed(2)}x${h.toFixed(2)}`} w={3.2} h={1.7}>
              <path d={G.ellipse(w, h)} fill={D.MACHINE} stroke={D.INK} strokeWidth={0.03} />
              <text textAnchor="middle" y={0.07} fontSize={0.2} fill={D.INK}>
                {name}
              </text>
            </Cell>
          );
        })}
      </div>
    </main>
  );
}
