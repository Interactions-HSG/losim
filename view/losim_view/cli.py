"""losim-view — the framework's viewer. Works for any lab, unchanged."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import bill as bill_mod
from . import player, svg
from .shapes import SCENES, build
from .trace import load


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="losim-view",
                                description="draw what the simulator ran")
    sub = p.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("view", help="a self-contained HTML player (all scenes)")
    v.add_argument("trace")
    v.add_argument("--out", default="view.html")

    s = sub.add_parser("svg", help="one scene as a static SVG")
    s.add_argument("trace")
    s.add_argument("--scene", default="spacetime", choices=sorted(SCENES))
    s.add_argument("--out", default="scene.svg")

    b = sub.add_parser("bill", help="the five-bucket cost view")
    b.add_argument("trace")
    b.add_argument("--svg", help="also write a bar chart here")

    r = sub.add_parser("render", help="a manim video (needs manim + ffmpeg)")
    r.add_argument("trace")
    r.add_argument("--scene", default="spacetime", choices=sorted(SCENES))
    r.add_argument("--quality", default="l", choices=list("lmhp"))
    r.add_argument("--out", default="media")

    a = p.parse_args(argv)
    trace = load(a.trace)

    if a.cmd == "view":
        frames = {}
        for name in SCENES:
            f = build(trace, name).fit()
            for w in f.warnings():
                print(f"losim-view: {name}: {w}", file=sys.stderr)
            frames[name] = f
        meta = {"seed": trace.meta.get("seed"), "codec": trace.meta.get("codec"),
                "endedAtMs": trace.ended_ms, "metrics": trace.metrics,
                "cost": trace.bill.get("cost")}
        Path(a.out).write_text(player.render(frames, trace.name, meta))
        print(f"wrote {a.out} — open it in a browser")
        return 0

    if a.cmd == "svg":
        f = build(trace, a.scene).fit()
        for w in f.warnings():
            print(f"losim-view: {w}", file=sys.stderr)
        Path(a.out).write_text(svg.render(f))
        print(f"wrote {a.out} ({len(f)} shapes)")
        return 0

    if a.cmd == "bill":
        print(bill_mod.render_text(trace.bill))
        if a.svg:
            Path(a.svg).write_text(bill_mod.render_svg(trace.bill))
            print(f"\nwrote {a.svg}")
        return 0

    if a.cmd == "render":
        try:
            from manim import config, tempconfig
        except ImportError:
            print("manim is not installed. Either:\n"
                  "  pip install manim   (and install ffmpeg)\n"
                  "or use the dependency-free player:\n"
                  f"  losim-view view {a.trace} --out view.html", file=sys.stderr)
            return 2
        from .manim_render import scene_for
        f = build(trace, a.scene).fit()
        Scene = scene_for(f, name=f"{trace.name.replace('-', '_')}_{a.scene}")
        with tempconfig({"quality": {"l": "low_quality", "m": "medium_quality",
                                     "h": "high_quality", "p": "production_quality"}[a.quality],
                         "media_dir": a.out}):
            Scene().render()
        print(f"rendered {a.scene} -> {a.out}")
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
