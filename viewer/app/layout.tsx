import type { ReactNode } from 'react';
import * as stylex from '@stylexjs/stylex';

import './globals.css';
import { chrome, font, size } from '../lib/tokens.stylex.ts';
import { hsg, hsgBar, hsgType } from '../lib/themes.stylex.ts';

export const metadata = {
  title: 'DISSALy',
  description: 'Distributed System Simulation Analysis.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" {...stylex.props(sx.page)}>
      {/* Apply the theme before styles resolve its tokens. */}
      <body {...stylex.props(hsg, hsgBar, hsgType, sx.page, sx.body)}>{children}</body>
    </html>
  );
}

/**
 * Root page styles formerly defined in globals.css.
 *
 * `color-scheme` lets native controls and the scrollbar follow the active theme.
 */
const sx = stylex.create({
  page: { backgroundColor: chrome.bg, colorScheme: 'light dark' },
  body: {
    color: chrome.text,
    fontFamily: font.sans,
    fontSize: size.base,
    lineHeight: 1.55,
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
    fontFeatureSettings: "'cv05', 'ss01'",
  },
});
