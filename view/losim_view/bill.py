"""The cost view. Every lab gets one, because every run produces a bill."""
from __future__ import annotations

BUCKETS = ["revenue", "build", "capacity", "consumption", "incidents"]

WHY = {
    "build": "Carried whether or not the thing it protects against ever happens.",
    "capacity": "An idle machine costs exactly as much as a busy one.",
    "consumption": "Scales with load — this is the line a better algorithm moves.",
    "incidents": "Zero until something breaks, then large.",
    "revenue": "Earned only when it works.",
}


def render_text(bill: dict) -> str:
    if not bill:
        return "no bill in this trace"
    cur = bill.get("currency", "CHF")
    out = []
    for b in BUCKETS:
        lines = [l for l in bill.get("lines", []) if l["bucket"] == b]
        if not lines:
            continue
        out.append(f"{b}")
        for l in lines:
            qty = f"{l['quantity']:,.4g}"
            out.append(f"    {l['what']:<30} {qty:>14} {l['unit']:<14} "
                       f"× {l['unitPrice']:.4f} = {cur} {l['amount']:.4f}")
        out.append(f"    {'':<30} {'':>14} {'':<14}   {WHY.get(b, '')}")
    buckets = bill.get("buckets", {})
    out.append("-" * 92)
    for b in BUCKETS:
        out.append(f"    {b:<30} {cur} {buckets.get(b, 0):>12.4f}")
    out.append(f"    {'TOTAL COST':<30} {cur} {bill.get('cost', 0):>12.4f}")
    out.append(f"    {'PROFIT':<30} {cur} {bill.get('profit', 0):>12.4f}")
    out.append("")
    out.append("Money is the aggregator, never the replacement: every line above keeps the")
    out.append("technical quantity it came from, so the measure stays visible.")
    return "\n".join(out)


def render_svg(bill: dict, width: int = 900, height: int = 420) -> str:
    """A bar per bucket. Reported separately, never summed — the see-saw is the lesson."""
    cur = bill.get("currency", "CHF")
    buckets = bill.get("buckets", {})
    cost_buckets = [b for b in BUCKETS if b != "revenue"]
    peak = max([abs(buckets.get(b, 0)) for b in BUCKETS] + [1e-9])
    bar_w = (width - 160) / max(1, len(BUCKETS))
    colors = {"revenue": "#63C77A", "build": "#9B7BE8", "capacity": "#4C9BE8",
              "consumption": "#4CD4C4", "incidents": "#E05252"}
    bars = []
    for i, b in enumerate(BUCKETS):
        v = buckets.get(b, 0)
        h = (abs(v) / peak) * (height - 170)
        x = 100 + i * bar_w
        y = height - 90 - h
        bars.append(
            f'<rect x="{x:.0f}" y="{y:.0f}" width="{bar_w - 24:.0f}" height="{h:.0f}" '
            f'rx="6" fill="{colors[b]}33" stroke="{colors[b]}" stroke-width="2"/>'
            f'<text x="{x + (bar_w - 24) / 2:.0f}" y="{y - 10:.0f}" fill="#C9D1E0" font-size="13" '
            f'text-anchor="middle">{cur} {v:.3f}</text>'
            f'<text x="{x + (bar_w - 24) / 2:.0f}" y="{height - 66:.0f}" fill="#8A93A6" '
            f'font-size="13" text-anchor="middle">{b}</text>')
    total = bill.get("cost", 0)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}">
<rect width="100%" height="100%" fill="#12151C"/>
<text x="40" y="44" fill="#C9D1E0" font-size="20" font-weight="700">What this design costs</text>
<text x="40" y="68" fill="#8A93A6" font-size="13">five buckets, reported separately — replication moves money between them</text>
{''.join(bars)}
<text x="40" y="{height - 28}" fill="#C9D1E0" font-size="14">total cost {cur} {total:.4f} · profit {cur} {bill.get('profit', 0):.4f}</text>
</svg>"""
