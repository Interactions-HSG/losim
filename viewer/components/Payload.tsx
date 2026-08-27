'use client';

/**
 * What a call carried, in full.
 *
 * Shared by the machine panel and the message panel on purpose: they are asking
 * the same question from two directions — "what is this machine looking at" and
 * "what is in this envelope" — and two renderings of one payload would
 * eventually disagree about a truncation marker.
 *
 * A summary line that expands. The summary is what fits at a glance; the body is
 * the trace's own record, formatted, scrollable and selectable, because
 * following one word from a split into a total means reading a list rather than
 * being told its shape.
 */
import { digest } from '../lib/trace.ts';

export function Payload({
  detail,
  open = false,
  label,
}: {
  detail: Record<string, unknown>;
  /** Start expanded — what a panel opened *about* one message should do. */
  open?: boolean;
  /** Override the side's name when there is only one and its direction is known. */
  label?: string;
}) {
  const arg = detail['arg'];
  const result = detail['result'];
  return (
    <div style={{ fontSize: 12, display: 'grid', gap: 3 }}>
      {arg !== undefined && <Side label={label ?? 'in'} body={arg} open={open} />}
      {result !== undefined && <Side label={label ?? 'out'} body={result} open={open} />}
    </div>
  );
}

function Side({ label, body, open }: { label: string; body: unknown; open: boolean }) {
  const text = digest(body, 6);
  const full = JSON.stringify(body, null, 1);
  return (
    <details open={open}>
      <summary style={{ cursor: 'pointer', listStyle: 'none' }}>
        <span className="muted" style={{ marginRight: 6 }}>
          {label}
        </span>
        {text || <span className="muted">empty</span>}
      </summary>
      <pre
        className="mono"
        style={{
          maxHeight: 180,
          overflow: 'auto',
          margin: '4px 0 0',
          padding: 8,
          fontSize: 11,
          background: 'var(--surface-2)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--r-sm)',
        }}
      >
        {full}
      </pre>
    </details>
  );
}
