/**
 * Recording a film of the fleet, frame by frame.
 *
 * **The clock is an input, not a consequence.** Every frame's timestamp is an
 * argument to the encoder, so the file a slow laptop produces is byte-for-byte
 * the same length, at the same rate, as the one a fast laptop produces — it just
 * takes longer to make. That is the whole reason not to record the live canvas
 * stream through `MediaRecorder`, where a frame the browser failed to paint in
 * time becomes a frame missing from the file, and a throttled machine silently
 * produces a shorter, jerkier video of the same run.
 *
 * It is also why recording is the *same code path* as playing. The viewer draws
 * every frame as a pure function of `(trace, t, selection)`; playing lets a timer
 * advance `t`, and recording advances it by hand. Neither knows which is happening.
 */
import {
  BufferTarget,
  CanvasSource,
  Mp4OutputFormat,
  Output,
  Quality,
  WebMOutputFormat,
  getFirstEncodableVideoCodec,
} from 'mediabunny';

export interface Recording {
  blob: Blob;
  mime: string;
  extension: string;
  codec: string;
  frames: number;
  /** Seconds of film, which is frames ÷ fps and nothing to do with how long it took. */
  duration: number;
  /** How long the recording took to make. Reported, never used. */
  tookMs: number;
}

export interface RecordRequest {
  width: number;
  height: number;
  fps: number;
  frames: number;
  /**
   * Put the scene into the state it should be in for frame `f`, and resolve once
   * the DOM actually shows it. In React that is a `flushSync` and a paint.
   */
  showFrame: (f: number) => Promise<void> | void;
  /** The `<svg>` holding the frame that `showFrame` just committed. */
  svgOf: () => SVGSVGElement;
  quality?: 'very-low' | 'low' | 'medium' | 'high' | 'very-high';
  onProgress?: (done: number, total: number) => void;
  signal?: AbortSignal;
}

/**
 * MP4 where the browser can encode it, WebM where it cannot.
 *
 * Asked rather than assumed: WebCodecs support for H.264 differs between
 * browsers and even between builds of one, and a recorder that assumes it and is
 * wrong fails at the end of a long render rather than at the start of it.
 */
async function pickFormat(width: number, height: number) {
  const mp4 = new Mp4OutputFormat();
  const codec = await getFirstEncodableVideoCodec(mp4.getSupportedVideoCodecs(), { width, height });
  if (codec) return { format: mp4, codec, mime: 'video/mp4', extension: 'mp4' };

  const webm = new WebMOutputFormat();
  const fallback = await getFirstEncodableVideoCodec(webm.getSupportedVideoCodecs(), { width, height });
  if (fallback) {
    return { format: webm, codec: fallback, mime: 'video/webm', extension: 'webm' };
  }
  throw new Error('this browser cannot encode video: no codec in either MP4 or WebM');
}

export async function record(req: RecordRequest): Promise<Recording> {
  const { width, height, fps, frames } = req;
  const began = performance.now();

  const { format, codec, mime, extension } = await pickFormat(width, height);
  const target = new BufferTarget();
  const output = new Output({ format, target });

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('no 2d context to draw frames into');

  const source = new CanvasSource(canvas, {
    codec,
    quality: new Quality(req.quality ?? 'high'),
  });
  output.addVideoTrack(source, { frameRate: fps });
  await output.start();

  try {
    for (let f = 0; f < frames; f++) {
      req.signal?.throwIfAborted();
      await req.showFrame(f);
      await paint();
      await drawInto(ctx, req.svgOf(), width, height);
      // The two arguments are the whole point: where this frame sits, and how
      // long it lasts. Neither is measured.
      await source.add(f / fps, 1 / fps);
      req.onProgress?.(f + 1, frames);
    }
    await output.finalize();
  } catch (e) {
    await output.cancel().catch(() => {});
    throw e;
  }

  const buffer = target.buffer;
  if (!buffer) throw new Error('the encoder finished without producing a file');
  return {
    blob: new Blob([buffer], { type: mime }),
    mime,
    extension,
    codec,
    frames,
    duration: frames / fps,
    tookMs: performance.now() - began,
  };
}

/** Resolves after the browser has painted whatever was just committed. */
function paint(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
}

/**
 * An `<svg>` element onto a canvas.
 *
 * By way of a data URL rather than a blob URL, because the encoder and the page
 * must work from a `file://` origin and out of a Codespace with no network, and
 * a data URL involves neither. The SVG has to be self-contained for this to draw
 * anything — no external stylesheet, no linked font, no remote image — which the
 * design system already requires for its own reasons.
 */
async function drawInto(
  ctx: CanvasRenderingContext2D,
  svg: SVGSVGElement,
  width: number,
  height: number,
): Promise<void> {
  const clone = svg.cloneNode(true) as SVGSVGElement;
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', String(width));
  clone.setAttribute('height', String(height));
  const text = new XMLSerializer().serializeToString(clone);
  const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(text)}`;

  const image = new Image();
  image.width = width;
  image.height = height;
  await new Promise<void>((resolve, reject) => {
    image.onload = () => resolve();
    image.onerror = () => reject(new Error('the frame could not be rasterised'));
    image.src = url;
  });
  // Cleared rather than painted over: the film's own background is part of the
  // picture, and a frame that only partly covers the last one would ghost.
  ctx.clearRect(0, 0, width, height);
  ctx.drawImage(image, 0, 0, width, height);
}

/**
 * This instant, as a picture, for a slide.
 *
 * **PNG and SVG, because they are for different jobs.** A PNG goes into Keynote
 * and behaves; an SVG is vector, so the same frame projected onto a lecture
 * theatre wall has type that is still type rather than a raster of it. Both are
 * the film exactly as it is on screen — the same serialise-and-rasterise the
 * recorder uses, one frame of it — so a still and the video cannot show
 * different pictures of the same moment.
 *
 * The SVG needs no rasterising at all, which is why it is the cheaper of the two
 * and the better one to reach for.
 */
export async function still(
  svg: SVGSVGElement,
  name: string,
  as: 'png' | 'svg',
  width = 1920,
  height = 1080,
): Promise<void> {
  if (as === 'svg') {
    const clone = svg.cloneNode(true) as SVGSVGElement;
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clone.setAttribute('width', String(width));
    clone.setAttribute('height', String(height));
    const text = new XMLSerializer().serializeToString(clone);
    download(new Blob([text], { type: 'image/svg+xml' }), `${name}.svg`);
    return;
  }

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('no 2d context to draw the frame into');
  await drawInto(ctx, svg, width, height);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error('the frame could not be encoded as a PNG');
  download(blob, `${name}.png`);
}

function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Hands the finished file to the browser as a download. */
export function save(recording: Recording, name: string): void {
  const url = URL.createObjectURL(recording.blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${name}.${recording.extension}`;
  a.click();
  // Revoked on the next turn: revoking synchronously races the click on Safari.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
