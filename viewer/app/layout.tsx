import type { ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import './globals.css';
import { chrome, font } from '../lib/tokens.stylex.ts';
import { hsg, hsgBar, hsgType } from '../lib/themes.stylex.ts';

export const metadata = {
  title: 'losim',
  description: 'A decentralized system, on one clock.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" {...stylex.props(sx.page)}>
      {/* The theme goes on before the styles that read it, so every token in
          the tree below resolves to the university's value. */}
      <body {...stylex.props(hsg, hsgBar, hsgType, sx.page, sx.body)}>{children}</body>
    </html>
  );
}

/**
 * What `html, body` used to say in globals.css, from the same tokens as the
 * rest of the app rather than from a second copy of them.
 *
 * `color-scheme` is on the root so the browser's own furniture — form controls,
 * the scrollbar's gutter — follows the theme the tokens are switching on.
 */
const sx = stylex.create({
  page: { backgroundColor: chrome.bg, colorScheme: 'light dark' },
  body: {
    color: chrome.text,
    fontFamily: font.sans,
    fontSize: '14.5px',
    lineHeight: 1.55,
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
    fontFeatureSettings: "'cv05', 'ss01'",
  },
});
