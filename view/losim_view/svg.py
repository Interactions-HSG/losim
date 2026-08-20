"""Frame -> static SVG. Zero dependencies, opens in any browser."""
from __future__ import annotations

from .shapes import Frame, FRAME_H, FRAME_W

BG = "#12151C"
FG = "#C9D1E0"


def _esc(s: str) -> str:
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def shape_svg(s, opacity: float = 1.0) -> str:
    o = f' opacity="{opacity:g}"' if opacity < 1 else ""
    dash = ' stroke-dasharray="6 5"' if s.style == "control" else ""
    if s.kind == "arrow":
        return (f'<line x1="{s.x:.1f}" y1="{s.y:.1f}" x2="{s.x2:.1f}" y2="{s.y2:.1f}" '
                f'stroke="{s.color}" stroke-width="2"{dash} marker-end="url(#a)"{o}/>'
                + (f'<text x="{(s.x + s.x2) / 2:.1f}" y="{(s.y + s.y2) / 2 - 6:.1f}" '
                   f'fill="{FG}" font-size="12" text-anchor="middle"{o}>{_esc(s.text)}</text>'
                   if s.text else ""))
    if s.kind == "ellipse":
        return (f'<ellipse cx="{s.x:.1f}" cy="{s.y:.1f}" rx="{s.w / 2:.1f}" ry="{s.h / 2:.1f}" '
                f'fill="{s.color}22" stroke="{s.color}" stroke-width="2"{o}/>'
                f'<text x="{s.x:.1f}" y="{s.y + 5:.1f}" fill="{FG}" font-size="15" '
                f'text-anchor="middle"{o}>{_esc(s.text)}</text>')
    if s.kind == "lane":
        return (f'<line x1="{s.x - s.w / 2:.1f}" y1="{s.y:.1f}" x2="{s.x + s.w / 2:.1f}" y2="{s.y:.1f}" '
                f'stroke="#2A2F3A" stroke-width="{max(1, s.h):.0f}"{o}/>'
                f'<text x="{s.x - s.w / 2 - 8:.1f}" y="{s.y + 5:.1f}" fill="{FG}" font-size="14" '
                f'text-anchor="end"{o}>{_esc(s.text)}</text>')
    if s.kind == "label":
        return (f'<text x="{s.x:.1f}" y="{s.y:.1f}" fill="{FG}" font-size="16" '
                f'font-weight="600" text-anchor="middle"{o}>{_esc(s.text)}</text>')
    # box | chip | state
    rx = 6 if s.kind == "box" else 12
    return (f'<rect x="{s.x - s.w / 2:.1f}" y="{s.y - s.h / 2:.1f}" width="{max(2, s.w):.1f}" '
            f'height="{max(2, s.h):.1f}" rx="{rx}" fill="{s.color}22" stroke="{s.color}" '
            f'stroke-width="1.5"{o}/>'
            + (f'<text x="{s.x:.1f}" y="{s.y + 4:.1f}" fill="{FG}" font-size="12" '
               f'text-anchor="middle"{o}>{_esc(s.text)}</text>' if s.text else ""))


def render(frame: Frame, width: float = FRAME_W, height: float = FRAME_H) -> str:
    body = "\n".join(shape_svg(s) for s in frame)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width:.0f} {height:.0f}" width="{width:.0f}" height="{height:.0f}">
<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
<path d="M 0 0 L 10 5 L 0 10 z" fill="{FG}"/></marker></defs>
<rect width="100%" height="100%" fill="{BG}"/>
<text x="30" y="42" fill="{FG}" font-size="22" font-weight="700">{_esc(frame.title)}</text>
<text x="30" y="66" fill="#8A93A6" font-size="14">{_esc(frame.subtitle)}</text>
{body}
</svg>"""
