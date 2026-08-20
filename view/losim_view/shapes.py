"""Shapes: the renderer-agnostic middle layer.

A trace says *what happened*; shapes say *what to draw*. The HTML player, the
static SVG and the manim exporter all consume this, which is what keeps a
lecture video and a student's browser showing the same picture.

Deliberately dumb: primitives with positions, times and colours. No manim
imports, no DOM — it serialises to JSON and crosses any boundary.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, asdict
from typing import Iterable, Iterator

from .trace import Trace
from .values import default_visual

FRAME_W, FRAME_H = 1600.0, 900.0

# One colour per key, stable everywhere it appears.
PALETTE = ["#4C9BE8", "#E8B44C", "#63C77A", "#D96A9E", "#9B7BE8",
           "#4CD4C4", "#E87A4C", "#B4E84C"]

# What a machine remembers is one colour whatever it holds, so it reads as a
# property of the machine rather than as more data passing through.
STATE_COLOR = "#4CD4C4"
DEAD_COLOR = "#E05252"
CONTROL_COLOR = "#8A93A6"
WARN_COLOR = "#E8B44C"

STATUS_COLORS = {"ok": "#63C77A", "timeout": "#E8B44C", "dropped": "#E05252"}


def color_for(token) -> str:
    """Deterministic colour for a name.

    An explicit checksum, never hash(): Python randomises string hashing per
    process, so hash() would give the same key a different colour in the video
    than in the browser.
    """
    s = str(token)
    n = 0
    for ch in s:
        n = (n * 31 + ord(ch)) & 0xFFFFFFFF
    return PALETTE[n % len(PALETTE)]


@dataclass
class Shape:
    kind: str                      # box | ellipse | arrow | label | chip | state | lane
    x: float = 0.0
    y: float = 0.0
    w: float = 0.0
    h: float = 0.0
    x2: float = 0.0                # arrows
    y2: float = 0.0
    text: str = ""
    color: str = "#4C9BE8"
    t_in: float = 0.0              # virtual ms at which it appears
    t_out: float = -1.0            # -1 = stays
    style: str = "data"            # data | control
    meta: dict = field(default_factory=dict)

    def to_json(self) -> dict:
        return asdict(self)


@dataclass
class Frame:
    title: str = ""
    subtitle: str = ""
    shapes: list[Shape] = field(default_factory=list)
    duration_ms: float = 1.0
    scene: str = "frame"

    def add(self, s: Shape) -> Shape:
        self.shapes.append(s)
        return s

    def __iter__(self) -> Iterator[Shape]:
        return iter(self.shapes)

    def __len__(self) -> int:
        return len(self.shapes)

    def bounds(self) -> tuple[float, float, float, float]:
        xs, ys = [], []
        for s in self.shapes:
            xs += [s.x - s.w / 2, s.x + s.w / 2]
            ys += [s.y - s.h / 2, s.y + s.h / 2]
            if s.kind == "arrow":
                xs += [s.x2]
                ys += [s.y2]
        if not xs:
            return 0, 0, FRAME_W, FRAME_H
        return min(xs), min(ys), max(xs), max(ys)

    def fit(self, width: float = FRAME_W, height: float = FRAME_H, pad: float = 60.0) -> "Frame":
        """Whatever is off-camera might as well not have been drawn."""
        x0, y0, x1, y1 = self.bounds()
        sw = max(1e-6, x1 - x0)
        sh = max(1e-6, y1 - y0)
        scale = min((width - 2 * pad) / sw, (height - 2 * pad) / sh)
        for s in self.shapes:
            s.x = (s.x - x0) * scale + pad
            s.y = (s.y - y0) * scale + pad
            s.x2 = (s.x2 - x0) * scale + pad
            s.y2 = (s.y2 - y0) * scale + pad
            s.w *= scale
            s.h *= scale
        return self

    def warnings(self) -> list[str]:
        """What fitting cannot fix is said out loud, not quietly rendered."""
        out = []
        labelled = [s for s in self.shapes if s.text]
        tiny = [s for s in labelled if s.w and s.w < 24]
        if tiny:
            out.append(f"{len(tiny)} labels are too small to read at this size")
        if len(self.shapes) > 4000:
            out.append(f"{len(self.shapes)} shapes — the picture will be too busy to follow")
        lanes = {s.meta.get("vm") for s in self.shapes if s.kind == "lane"}
        if len(lanes) > 24:
            out.append(f"{len(lanes)} machines — too many to label individually")
        return out

    def to_json(self) -> dict:
        return {
            "title": self.title,
            "subtitle": self.subtitle,
            "scene": self.scene,
            "durationMs": self.duration_ms,
            "shapes": [s.to_json() for s in self.shapes],
        }


# ----------------------------------------------------------------- helpers

def _payload_text(e: dict) -> str:
    for key in ("value", "arg", "result"):
        if key in e and e[key] is not None:
            return default_visual(e[key]).text
    return ""


def _time_scale(trace: Trace, width: float) -> tuple[float, float]:
    end = max(1, trace.ended_ms)
    return width / end, end


# ----------------------------------------------------------------- builders

def spacetime(trace: Trace) -> Frame:
    """The Lamport diagram: one lane per machine, time running left to right."""
    f = Frame(title=f"{trace.name} — space-time", scene="spacetime",
              subtitle="each lane is a machine; each arrow is a message")
    vms = trace.vm_names
    lane_gap = 120.0
    width = 1400.0
    scale, end = _time_scale(trace, width)
    f.duration_ms = end

    y_of = {vm: i * lane_gap for i, vm in enumerate(vms)}
    for vm, y in y_of.items():
        f.add(Shape("lane", x=width / 2, y=y, w=width, h=2, text=vm,
                    color=color_for(vm), meta={"vm": vm}))

    for e in trace.events:
        vm, t = e.get("vm"), e.get("t", 0)
        if vm not in y_of:
            continue
        x, y = t * scale, y_of[vm]
        kind = e["kind"]
        if kind in ("send", "rpc_call"):
            target = e.get("to")
            if target in y_of:
                f.add(Shape("arrow", x=x, y=y, x2=x + max(6.0, scale * 8), y2=y_of[target],
                            text=_payload_text(e), color=color_for(vm), t_in=t,
                            style="control" if kind == "rpc_call" else "data",
                            meta={"from": vm, "to": target, "bytes": e.get("bytes")}))
        elif kind == "state":
            f.add(Shape("state", x=x, y=y - 26, w=90, h=26,
                        text=f"{e.get('key')}={default_visual(e.get('value'), 14).text}",
                        color=STATE_COLOR, t_in=t,
                        meta={"vm": vm, "key": e.get("key")}))
        elif kind in ("kill", "freeze"):
            f.add(Shape("chip", x=x, y=y + 22, w=54, h=22, text=kind,
                        color=DEAD_COLOR if kind == "kill" else WARN_COLOR, t_in=t,
                        meta={"vm": vm}))
        elif kind == "rpc_timeout":
            f.add(Shape("chip", x=x, y=y + 22, w=64, h=22, text="timeout",
                        color=STATUS_COLORS["timeout"], t_in=t, meta={"vm": vm}))
    return f


def topology(trace: Trace) -> Frame:
    """Machines in a circle, with messages flying at simulated latency."""
    f = Frame(title=f"{trace.name} — topology", scene="topology",
              subtitle="messages carry their real payload and their real size")
    vms = trace.vm_names
    n = max(1, len(vms))
    radius = 320.0
    pos = {}
    for i, vm in enumerate(vms):
        angle = -math.pi / 2 + 2 * math.pi * i / n
        pos[vm] = (radius * math.cos(angle), radius * math.sin(angle))

    dead_at = {v["name"]: v.get("diedAt", -1) for v in trace.vms}
    for vm in vms:
        x, y = pos[vm]
        spec = next((v for v in trace.vms if v["name"] == vm), {})
        f.add(Shape("ellipse", x=x, y=y, w=130, h=76, text=vm, color=color_for(vm),
                    meta={"vm": vm, "instance": spec.get("instance"),
                          "zone": spec.get("zone"), "diedAt": dead_at.get(vm, -1)}))

    for e in trace.events:
        if e["kind"] not in ("send", "rpc_call"):
            continue
        a, b = e.get("vm"), e.get("to")
        if a not in pos or b not in pos:
            continue
        (x1, y1), (x2, y2) = pos[a], pos[b]
        f.add(Shape("arrow", x=x1, y=y1, x2=x2, y2=y2, text=_payload_text(e),
                    color=color_for(a), t_in=e["t"],
                    style="control" if e["kind"] == "rpc_call" else "data",
                    meta={"from": a, "to": b, "bytes": e.get("bytes"),
                          "locality": e.get("locality")}))

    for e in trace.of_kind("state"):
        vm = e["vm"]
        if vm not in pos:
            continue
        x, y = pos[vm]
        f.add(Shape("state", x=x, y=y + 56, w=120, h=24,
                    text=f"{e.get('key')}={default_visual(e.get('value'), 14).text}",
                    color=STATE_COLOR, t_in=e["t"], meta={"vm": vm, "key": e.get("key")}))
    return f


def gantt(trace: Trace) -> Frame:
    """Per-machine occupancy. This is the view where a straggler is obvious."""
    f = Frame(title=f"{trace.name} — occupancy", scene="gantt",
              subtitle="a straggler holds its lane while the others sit idle, on the clock")
    vms = trace.vm_names
    row_gap = 64.0
    width = 1400.0
    scale, end = _time_scale(trace, width)
    f.duration_ms = end

    y_of = {vm: i * row_gap for i, vm in enumerate(vms)}
    for vm, y in y_of.items():
        f.add(Shape("lane", x=width / 2, y=y, w=width, h=34, text=vm,
                    color="#2A2F3A", meta={"vm": vm}))

    open_calls: dict[str, list[dict]] = {}
    for e in trace.events:
        vm = e.get("vm")
        if vm not in y_of:
            continue
        if e["kind"] == "handler_start":
            open_calls.setdefault(vm, []).append(e)
        elif e["kind"] == "handler_end":
            stack = open_calls.get(vm) or []
            start = None
            for i, s in enumerate(stack):
                if s.get("call") == e.get("call"):
                    start = stack.pop(i)
                    break
            if start is None and stack:
                start = stack.pop(0)
            if start is None:
                continue
            t0, t1 = start["t"], e["t"]
            x0, x1 = t0 * scale, max(t1 * scale, t0 * scale + 3)
            f.add(Shape("box", x=(x0 + x1) / 2, y=y_of[vm], w=max(3.0, x1 - x0), h=28,
                        text=str(start.get("method", "")).split(".")[-1],
                        color=color_for(vm), t_in=t0,
                        meta={"vm": vm, "ms": t1 - t0, "method": start.get("method")}))

    for e in trace.of_kind("kill"):
        vm = e.get("vm")
        if vm in y_of:
            f.add(Shape("chip", x=e["t"] * scale, y=y_of[vm], w=46, h=28, text="dead",
                        color=DEAD_COLOR, t_in=e["t"], meta={"vm": vm}))
    return f


def dataflow(trace: Trace) -> Frame:
    """The execution overview: phase lanes, left to right.

    Lanes are phases, not machines — so a colocated worker appears in both the
    map lane and the reduce lane, and a local hand-off draws short while a
    remote read draws long. The locality tiers become visible as arrow length.
    """
    f = Frame(title=f"{trace.name} — dataflow", scene="dataflow",
              subtitle="lanes are phases; a machine may appear in more than one")

    methods = []
    for e in trace.of_kind("handler_start"):
        m = str(e.get("method", ""))
        if m and m not in methods:
            methods.append(m)
    phases = methods or ["work"]

    col_gap = 340.0
    row_gap = 110.0
    x_of_phase = {p: i * col_gap for i, p in enumerate(phases)}

    workers_in_phase: dict[str, list[str]] = {p: [] for p in phases}
    for e in trace.of_kind("handler_start"):
        p = str(e.get("method", "work"))
        if e["vm"] not in workers_in_phase.setdefault(p, []):
            workers_in_phase[p].append(e["vm"])

    pos: dict[tuple[str, str], tuple[float, float]] = {}
    for p, xs in x_of_phase.items():
        for i, vm in enumerate(workers_in_phase.get(p, [])):
            y = i * row_gap
            pos[(p, vm)] = (xs, y)
            f.add(Shape("ellipse", x=xs, y=y, w=150, h=70, text=vm, color=color_for(vm),
                        meta={"vm": vm, "phase": p}))
        f.add(Shape("label", x=xs, y=-90, w=col_gap - 40, h=30, text=p.split(".")[-1],
                    color="#C9D1E0", meta={"phase": p}))

    for e in trace.of_kind("handler_end"):
        p = str(e.get("method", "work"))
        key = (p, e["vm"])
        if key not in pos:
            continue
        x, y = pos[key]
        f.add(Shape("chip", x=x + 96, y=y, w=150, h=28, text=_payload_text(e),
                    color=STATE_COLOR, t_in=e["t"],
                    meta={"vm": e["vm"], "phase": p, "bytes": e.get("bytes")}))

    ordered = list(x_of_phase)
    for i in range(len(ordered) - 1):
        a, b = ordered[i], ordered[i + 1]
        for va in workers_in_phase.get(a, []):
            for vb in workers_in_phase.get(b, []):
                (x1, y1), (x2, y2) = pos[(a, va)], pos[(b, vb)]
                local = va == vb
                f.add(Shape("arrow", x=x1 + 75, y=y1, x2=x2 - 75, y2=y2,
                            color=CONTROL_COLOR if local else color_for(va),
                            style="control" if local else "data",
                            text="local" if local else "",
                            meta={"from": va, "to": vb, "local": local}))
    return f


SCENES = {
    "spacetime": spacetime,
    "topology": topology,
    "gantt": gantt,
    "dataflow": dataflow,
}


def build(trace: Trace, scene: str) -> Frame:
    if scene not in SCENES:
        raise ValueError(f"unknown scene '{scene}'; known scenes: {', '.join(sorted(SCENES))}")
    return SCENES[scene](trace)
