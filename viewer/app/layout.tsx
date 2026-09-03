import type { ReactNode } from 'react';
import './globals.css';

export const metadata = {
  title: 'losim',
  description: 'A decentralized system, on one clock.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
