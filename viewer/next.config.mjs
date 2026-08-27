/**
 * The viewer is a static export, and that is a decision rather than a default.
 *
 * D10 says the toolchain must be identical in the devcontainer, on a laptop and
 * in a Codespace, with no setup step and no first-run download. A student
 * therefore never runs `npm` — the built page is committed the way the generated
 * protobuf sources are, and serving it is `python3 -m http.server -d build/viewer`.
 * `npm install` happens here, and only for whoever changes the viewer.
 *
 * Everything the page needs is bundled: d3, the encoder, and a font stack of
 * faces the host already has. Nothing is fetched at runtime, so nothing depends
 * on the network being there.
 *
 * @type {import('next').NextConfig}
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export default {
  output: 'export',
  // Pinned, because a package.json somewhere up the tree would otherwise decide
  // where the project root is — and on a laptop that is a stray directory nobody
  // knows about, while in a Codespace it is somewhere else entirely.
  turbopack: { root: dirname(fileURLToPath(import.meta.url)) },
  // `/spikes/s4/index.html` rather than `/spikes/s4.html`, because the server a
  // student runs is `python3 -m http.server`, which has never heard of Next and
  // will not try `.html` for a path that does not have it. Without this every
  // link in the exported app 404s on exactly the setup D10 exists to support.
  trailingSlash: true,
  images: { unoptimized: true },
  eslint: { ignoreDuringBuilds: true },
};
