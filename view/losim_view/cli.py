"""losim-view — the framework's viewer. Works for any lab, unchanged."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import bill as bill_mod
from . import manim_runtime, player, sidecar, svg
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

    r = sub.add_parser("render", help="a manim video, rendered in a sidecar")
    r.add_argument("trace")
    r.add_argument("--scene", default="spacetime", choices=sorted(SCENES))
    r.add_argument("--quality", default="l", choices=list("lmhp"))
    r.add_argument("--out", default="build/media")
    r.add_argument("--install", action="store_true",
                   help="install a manim sidecar first if there is none")

    w = sub.add_parser("serve", help="the studio: a page for watching runs as they happen")
    w.add_argument("watch", nargs="*", default=["build"],
                   help="directories to watch for traces (default: build)")
    w.add_argument("--port", type=int, default=8000)
    w.add_argument("--host", default="127.0.0.1")
    w.add_argument("--media", help="where videos are written (default: <watch>/media)")

    d = sub.add_parser("doctor", help="say where a video would be rendered, and how to fix it")
    d.add_argument("--install", metavar="KIND", nargs="?", const="",
                   help="install a sidecar: venv or docker")

    a = p.parse_args(argv)

    root = _root()
    if a.cmd == "serve":
        sidecar.serve(root, [Path(w) for w in a.watch], host=a.host, port=a.port,
                      media=Path(a.media) if a.media else None)
        return 0

    if a.cmd == "doctor":
        return _doctor(root, a)

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
        rt = manim_runtime.best(root)
        if rt is None and a.install:
            rt = manim_runtime.provision(root, None, print)
        if rt is None:
            print(_no_manim(root, a.trace), file=sys.stderr)
            return 2
        name = f"{trace.name.replace('-', '_')}_{a.scene}"
        out = Path(a.out) / name
        out.mkdir(parents=True, exist_ok=True)
        # The Frame is the whole contract with the sidecar: it never sees the trace.
        frame_path = out / "frame.json"
        frame_path.write_text(json.dumps(build(trace, a.scene).fit().to_json()))
        print(f"rendering {a.scene} in the {rt.kind} sidecar ({rt.detail})")
        video = rt.render(frame_path, a.scene, out, quality=a.quality, name=name,
                          log=lambda l: print("  " + l))
        print(f"wrote {video}")
        return 0

    return 1


def _root() -> Path:
    """The losim checkout — the docker sidecar mounts exactly this."""
    return Path(__file__).resolve().parents[2]


def _no_manim(root: Path, trace: str) -> str:
    st = manim_runtime.status(root)
    how = (f"  losim-view doctor --install {st['installable'][0]}"
           if st["installable"] else
           "  install docker, or install ffmpeg and rerun with --install")
    return ("no manim sidecar found. Either install one:\n" + how +
            "\nor use the dependency-free player, which needs nothing:\n"
            f"  losim-view view {trace} --out view.html")


def _doctor(root: Path, a) -> int:
    if a.install is not None:
        manim_runtime.provision(root, a.install or None, print)
    st = manim_runtime.status(root)
    print("manim sidecar")
    for r in st["available"]:
        print(f"  ✓ {r['kind']:<10} {r['detail']}")
    if not st["available"]:
        print("  ✗ none installed")
        print(f"  installable here: {', '.join(st['installable']) or 'nothing — install docker or ffmpeg'}")
        return 1
    print(f"\nvideos will render in the {st['kind']} sidecar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
