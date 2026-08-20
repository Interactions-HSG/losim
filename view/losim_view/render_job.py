"""The sidecar's payload: one Frame in, one video out.

This is the only module that needs manim, and it runs in whichever interpreter
has it — this one, a virtualenv, or a container. It reads a Frame that someone
else already built, so it never touches a trace and never decides what to draw.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

QUALITY = {"l": "low_quality", "m": "medium_quality",
           "h": "high_quality", "p": "production_quality"}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="losim-view render-job",
                                description="render one Frame with manim")
    p.add_argument("frame", help="frame JSON, as written by Frame.to_json()")
    p.add_argument("--scene", default="scene", help="only used to name the output")
    p.add_argument("--quality", default="l", choices=sorted(QUALITY))
    p.add_argument("--name", default="scene")
    p.add_argument("--out", default="media")
    a = p.parse_args(argv)

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from losim_view.shapes import Frame
    from losim_view.manim_render import scene_for

    frame = Frame.from_json(json.loads(Path(a.frame).read_text()))
    print(f"frame: {frame.scene} — {len(frame)} shapes, {frame.duration_ms:.0f} ms simulated",
          flush=True)
    for w in frame.warnings():
        print(f"warning: {w}", flush=True)

    from manim import config, tempconfig
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in a.name) or "scene"
    with tempconfig({"quality": QUALITY[a.quality], "media_dir": str(Path(a.out)),
                     "output_file": safe, "input_file": f"{safe}.py",
                     "verbosity": "WARNING", "progress_bar": "none"}):
        # The Frame is already fitted by the caller, so this only maps
        # primitives to mobjects — same shapes as the browser draws.
        scene = scene_for(frame, name=safe)()
        scene.render()
        video = scene.renderer.file_writer.movie_file_path

    print(f"VIDEO: {Path(video).resolve()}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
