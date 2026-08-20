"""Shapes: the renderer-agnostic middle layer.

A trace says *what happened*; shapes say *what to draw*. The HTML player, the
static SVG and the manim exporter all consume this, which is what keeps a
lecture video and a student's browser showing the same picture.

Deliberately dumb: primitives with positions, times and colours. No manim
imports, no DOM — it serialises to JSON and crosses any boundary.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, asdict, fields
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

    @staticmethod
    def from_json(d: dict) -> "Shape":
        return Shape(**{k: v for k, v in d.items() if k in _SHAPE_FIELDS})


_SHAPE_FIELDS = {f.name for f in fields(Shape)}


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

    def fit(self, width: float = FRAME_W, height: float = FRAME_H, pad: float = 60.0,
            top: float = 120.0) -> "Frame":
        """Whatever is off-camera might as well not have been drawn.

        `top` is headroom: the title and subtitle are drawn by the renderer at
        the top of the canvas, and content scaled into that band ends up written
        across them.
        """
        x0, y0, x1, y1 = self.bounds()
        sw = max(1e-6, x1 - x0)
        sh = max(1e-6, y1 - y0)
        scale = min((width - 2 * pad) / sw, (height - top - pad) / sh)
        # Centred in whatever room is left over, rather than pinned to a corner
        # with the slack all on one side.
        ox = (width - sw * scale) / 2 - x0 * scale
        oy = top + (height - top - pad - sh * scale) / 2 - y0 * scale
        for s in self.shapes:
            s.x = s.x * scale + ox
            s.y = s.y * scale + oy
            s.x2 = s.x2 * scale + ox
            s.y2 = s.y2 * scale + oy
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

    @staticmethod
    def from_json(d: dict) -> "Frame":
        """The inverse of to_json.

        A Frame is the only thing that crosses into the manim sidecar, so this
        is the whole contract between the process that decides what to draw and
        the process that can actually draw it.
        """
        return Frame(
            title=d.get("title", ""),
            subtitle=d.get("subtitle", ""),
            scene=d.get("scene", "frame"),
            duration_ms=float(d.get("durationMs", 1.0)),
            shapes=[Shape.from_json(x) for x in d.get("shapes", [])],
        )


# ----------------------------------------------------------------- helpers

def _duration(ms: float) -> str:
    return f"{ms / 1000:.1f} s" if ms >= 1000 else f"{ms:.0f} ms"


def _clip(text: str, n: int) -> str:
    """Long labels on a crowded axis are soup; a clipped one still reads."""
    return text if len(text) <= n else text[:n - 1] + "…"


def _thin_labels(arrows: list[Shape], dy: float = 22.0, per_char: float = 3.6) -> None:
    """Drop labels that would land on top of each other.

    Twenty-six messages inside a hundred milliseconds cannot all be captioned:
    printed anyway they overlap into a smear that hides the arrows as well. The
    payload stays on the shape's meta, so the browser still shows it on hover
    and nothing is lost — only the caption is.
    """
    placed: list[tuple[float, float, float]] = []       # x, y, half-width
    for s in arrows:
        if not s.text:
            continue
        ax, ay = (s.x + s.x2) / 2, (s.y + s.y2) / 2
        half = per_char * len(s.text)
        if any(abs(ay - py) < dy and abs(ax - px) < half + ph for px, py, ph in placed):
            s.meta = {**s.meta, "label": s.text}        # kept, just not printed
            s.text = ""
        else:
            placed.append((ax, ay, half))


def _payload_text(e: dict) -> str:
    for key in ("value", "arg", "result"):
        if key in e and e[key] is not None:
            return default_visual(e[key]).text
    return ""


def _time_scale(trace: Trace, width: float) -> tuple[float, float]:
    end = max(1, trace.ended_ms)
    return width / end, end


class TimeAxis:
    """Time along x, with long empty stretches folded up.

    A run is usually a burst of messages and then a wait — 26 RPCs in the first
    100 ms, then five seconds of nothing while a timeout runs down. On a plain
    linear axis the interesting part is a single vertical smear, so idle gaps
    are compressed to a fixed width and *marked*: the picture says how much time
    it skipped rather than quietly stretching or hiding it.
    """

    # A gap is only worth folding when it dominates the run *and* is long in
    # its own right. Both conditions matter: a quarter of a 100 ms run is 25 ms,
    # which is one message in flight, not a lull — folding that produced six
    # "quiet" markers on a six-hop ring and squeezed the whole picture into a
    # corner. At most three folds, so the axis never turns into a concertina.
    FOLD_FRACTION = 0.25
    FOLD_FLOOR_MS = 250.0
    MAX_FOLDS = 3

    def __init__(self, trace: Trace, width: float):
        end = max(1.0, float(trace.ended_ms))
        times = sorted({float(e["t"]) for e in trace.events} | {0.0, end})
        gaps = [b - a for a, b in zip(times, times[1:])]
        threshold = max(end * self.FOLD_FRACTION, self.FOLD_FLOOR_MS)
        worth_folding = sorted((g for g in gaps if g > threshold), reverse=True)
        if len(worth_folding) > self.MAX_FOLDS:
            threshold = worth_folding[self.MAX_FOLDS - 1]      # keep the longest few
        folded_width = width * 0.04                     # what a fold is worth on screen
        self.breaks: list[tuple[float, float]] = []     # (x, milliseconds skipped)

        # How much *time* survives folding, so the rest can be scaled up to fill
        # the width the folds gave back.
        kept = sum(g for g in gaps if g <= threshold)
        folds = sum(1 for g in gaps if g > threshold)
        scale = max(1e-9, (width - folds * folded_width)) / max(1e-9, kept)

        self._points = {0.0: 0.0}
        x = 0.0
        for a, b in zip(times, times[1:]):
            gap = b - a
            if gap > threshold:
                x += folded_width
                self.breaks.append((x, gap))
            else:
                x += gap * scale
            self._points[b] = x
        self.end = end
        self.width = x

    def __call__(self, t: float) -> float:
        t = float(t)
        if t in self._points:
            return self._points[t]
        below = [k for k in self._points if k <= t]
        above = [k for k in self._points if k >= t]
        if not below:
            return 0.0
        if not above:
            return self.width
        lo, hi = max(below), min(above)
        if hi == lo:
            return self._points[lo]
        f = (t - lo) / (hi - lo)
        return self._points[lo] + f * (self._points[hi] - self._points[lo])


# ----------------------------------------------------------------- builders

def spacetime(trace: Trace) -> Frame:
    """The Lamport diagram: one lane per machine, time running left to right."""
    f = Frame(title=f"{trace.name} — space-time", scene="spacetime",
              subtitle="each lane is a machine; each arrow is a message, drawn from when "
                       "it was sent to when it arrived")
    vms = trace.vm_names
    lane_gap = 120.0
    width = 1400.0
    axis = TimeAxis(trace, width)
    f.duration_ms = axis.end

    y_of = {vm: i * lane_gap for i, vm in enumerate(vms)}
    for vm, y in y_of.items():
        f.add(Shape("lane", x=width / 2, y=y, w=width, h=2, text=vm,
                    color=color_for(vm), meta={"vm": vm}))

    # A fold is announced, never silent: the reader is told what was skipped.
    floor = (len(vms) - 1) * lane_gap
    last_label_x = -1e9
    row = 0
    for x, skipped in axis.breaks:
        f.add(Shape("lane", x=x, y=floor / 2, w=2, h=floor + lane_gap,
                    text="", color=CONTROL_COLOR, style="control",
                    meta={"skippedMs": round(skipped)}))
        row = row + 1 if x - last_label_x < 150 else 0      # two folds close together
        last_label_x = x
        f.add(Shape("label", x=x, y=-46 - row * 26, w=90, h=18,
                    text=f"⋯ {_duration(skipped)} quiet ⋯", color=CONTROL_COLOR,
                    meta={"skippedMs": round(skipped)}))

    # When a message landed is in the trace too — the handler that ran it shares
    # the call id — so an arrow can slant across the latency instead of standing
    # straight up as if delivery were free.
    landed = {}
    for e in trace.of_kind("handler_start"):
        if e.get("call") is not None:
            landed.setdefault(e["call"], e["t"])

    for e in trace.events:
        vm, t = e.get("vm"), e.get("t", 0)
        if vm not in y_of:
            continue
        x, y = axis(t), y_of[vm]
        kind = e["kind"]
        if kind in ("send", "rpc_call"):
            target = e.get("to")
            if target in y_of:
                arrived = landed.get(e.get("call"), t)
                f.add(Shape("arrow", x=x, y=y, x2=max(axis(arrived), x + 6.0), y2=y_of[target],
                            text=_clip(_payload_text(e), 18), color=color_for(vm), t_in=t,
                            style="control" if kind == "rpc_call" else "data",
                            meta={"from": vm, "to": target, "bytes": e.get("bytes"),
                                  "sentAtMs": t, "arrivedAtMs": arrived,
                                  "locality": e.get("locality")}))
        elif kind == "state":
            # Close to its own lane: a badge floating midway between two lanes
            # belongs, as far as a reader can tell, to either of them.
            f.add(Shape("state", x=x, y=y - 16, w=84, h=20,
                        text=f"{e.get('key')}={default_visual(e.get('value'), 14).text}",
                        color=STATE_COLOR, t_in=t,
                        meta={"vm": vm, "key": e.get("key")}))
        elif kind in ("kill", "freeze"):
            f.add(Shape("chip", x=x, y=y + 24, w=54, h=22, text=kind,
                        color=DEAD_COLOR if kind == "kill" else WARN_COLOR, t_in=t,
                        meta={"vm": vm}))
        elif kind == "rpc_timeout":
            f.add(Shape("chip", x=x, y=y + 24, w=64, h=22, text="timeout",
                        color=STATUS_COLORS["timeout"], t_in=t, meta={"vm": vm}))

    _thin_labels([s for s in f if s.kind == "arrow"])
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

    # When each message landed, so the film can fly it across the gap it really
    # took rather than at some invented speed: an RPC is matched to the handler
    # that ran it, a plain message to the receive that logged it.
    landed: dict[object, float] = {}
    for e in trace.of_kind("handler_start"):
        if e.get("call") is not None:
            landed.setdefault(("call", e["call"]), e["t"])
    recvs: dict[tuple, list[float]] = {}
    for e in trace.of_kind("recv"):
        recvs.setdefault((e.get("from"), e["vm"]), []).append(e["t"])

    def arrival(e: dict) -> float:
        if e.get("call") is not None and ("call", e["call"]) in landed:
            return landed[("call", e["call"])]
        queue = recvs.get((e.get("vm"), e.get("to")))
        while queue:
            t = queue.pop(0)
            if t >= e["t"]:
                return t
        return e["t"]

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
        f.add(Shape("arrow", x=x1, y=y1, x2=x2, y2=y2, text=_clip(_payload_text(e), 22),
                    color=color_for(a), t_in=e["t"],
                    style="control" if e["kind"] == "rpc_call" else "data",
                    meta={"from": a, "to": b, "bytes": e.get("bytes"),
                          "sentAtMs": e["t"], "arrivedAtMs": arrival(e),
                          "locality": e.get("locality")}))

    # What a machine says about itself wins over what it happened to reveal: a
    # program that implements Drawable has decided how its machine should look,
    # and the card is rewritten in place each time it changes.
    drawn = [e for e in trace.of_kind("machine") if e["vm"] in pos]
    if drawn:
        for e in drawn:
            x, y = pos[e["vm"]]
            f.add(Shape("state", x=x, y=y + 58, w=190, h=26,
                        text=_clip(default_visual(e.get("visual"), 30).text, 30),
                        color=STATE_COLOR, t_in=e["t"],
                        meta={"vm": e["vm"], "key": "self",
                              "program": e.get("program")}))
    else:
        # One row per field, not one badge per reading: two different fields of
        # the same machine would otherwise be rewritten on top of each other.
        rows: dict[tuple, int] = {}
        for e in trace.of_kind("state"):
            vm = e["vm"]
            if vm not in pos:
                continue
            x, y = pos[vm]
            row = rows.setdefault((vm, e.get("key")), len(
                [k for k in rows if k[0] == vm]))
            f.add(Shape("state", x=x, y=y + 58 + row * 28, w=150, h=24,
                        text=f"{e.get('key')}={default_visual(e.get('value'), 14).text}",
                        color=STATE_COLOR, t_in=e["t"], meta={"vm": vm, "key": e.get("key")}))
    return f


def gantt(trace: Trace) -> Frame:
    """Per-machine occupancy. This is the view where a straggler is obvious."""
    f = Frame(title=f"{trace.name} — occupancy", scene="gantt",
              subtitle="who was working, and when — on the run's own clock, "
                       "which is where a straggler gives itself away")
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
    """The execution overview: what went in, who touched it, what came out.

    Five columns, as in the MapReduce paper: the input splits, the workers that
    mapped them, the intermediate data each one produced, the workers that
    reduced it, and the result. Lanes are phases rather than machines, so a
    worker that both maps and reduces appears twice — which is exactly what
    colocation means, and what makes a local hand-off draw short.

    Edges are what the trace says happened. The one exception is the shuffle,
    drawn faintly from every intermediate to every reducer, because that fan is
    what a shuffle *is* — and drawing it quietly keeps it from burying the rest.
    """
    f = Frame(title=f"{trace.name} — dataflow", scene="dataflow",
              subtitle="input, the machines that touched it, and what came out")

    phases: list[str] = []
    for e in trace.of_kind("handler_start"):
        m = str(e.get("method", ""))
        if m and m not in phases:
            phases.append(m)
    if not phases:
        return f

    workers: dict[str, list[str]] = {p: [] for p in phases}
    for e in trace.of_kind("handler_start"):
        p = str(e.get("method", ""))
        if p in workers and e["vm"] not in workers[p]:
            workers[p].append(e["vm"])

    ROW = 96.0
    COL = 360.0
    # Beyond this a column is a wall of chips, and the whole picture shrinks to
    # fit it — the columns are what carry the meaning, not the row count.
    MAX_ROWS = 8

    def column_y(n: int) -> list[float]:
        """Rows centred on zero, so columns of different heights line up."""
        return [(i - (n - 1) / 2) * ROW for i in range(n)]

    def caption(x: float, text: str) -> None:
        f.add(Shape("label", x=x, y=-((MAX_ROWS - 1) / 2) * ROW - 60, w=COL - 40, h=30,
                    text=text, color="#C9D1E0"))

    x = 0.0
    pos: dict[tuple[str, str], tuple[float, float]] = {}
    chips: dict[str, list[tuple[float, float]]] = {}

    # ---------------------------------------------------------------- input
    calls = [e for e in trace.of_kind("rpc_call", "send")
             if str(e.get("method", "")) == phases[0] and e.get("to")]
    if calls:
        caption(x, "input")
        ys = column_y(min(len(calls), MAX_ROWS))
        for e, y in zip(calls, ys):
            f.add(Shape("box", x=x, y=y, w=210, h=34, text=_clip(_payload_text(e), 20),
                        color=color_for(e.get("to")), t_in=e["t"],
                        meta={"to": e.get("to"), "bytes": e.get("bytes")}))
            chips.setdefault("input", []).append((x, y))
        if len(calls) > MAX_ROWS:
            f.add(Shape("label", x=x, y=ys[-1] + ROW * 0.7, w=210, h=24,
                        text=f"+{len(calls) - MAX_ROWS} more", color=CONTROL_COLOR))
        x += COL

    # --------------------------------------------------- a column per phase
    for pi, phase in enumerate(phases):
        caption(x, phase.split(".")[-1].replace("Service", ""))
        ys = column_y(len(workers[phase]))
        for vm, y in zip(workers[phase], ys):
            pos[(phase, vm)] = (x, y)
            f.add(Shape("ellipse", x=x, y=y, w=150, h=68, text=vm, color=color_for(vm),
                        meta={"vm": vm, "phase": phase}))
        x += COL

        # What that phase produced, one chip per worker, in its own column — but
        # only where there is something to show. A handler that returns nothing
        # would otherwise get a row of empty boxes standing in for its answer.
        produced = {}
        for e in trace.of_kind("handler_end"):
            if (str(e.get("method", "")) == phase and e["vm"] in workers[phase]
                    and _payload_text(e) and e["vm"] not in produced):
                produced[e["vm"]] = e
        if produced:
            last = pi == len(phases) - 1
            caption(x, "each machine's answer" if last else "intermediate")
            for vm, e in produced.items():
                _, y = pos[(phase, vm)]
                f.add(Shape("chip", x=x, y=y, w=230, h=30, text=_clip(_payload_text(e), 22),
                            color=STATE_COLOR, t_in=e["t"],
                            meta={"vm": vm, "phase": phase, "bytes": e.get("bytes")}))
                chips.setdefault(phase, []).append((x, y))
            x += COL

    # ---------------------------------------------------------------- edges
    first = phases[0]
    for (cx, cy), e in zip(chips.get("input", []), calls):
        wx, wy = pos.get((first, e["to"]), (cx + COL, cy))
        f.add(Shape("arrow", x=cx + 105, y=cy, x2=wx - 78, y2=wy, color=color_for(e["to"]),
                    t_in=e["t"], meta={"to": e["to"]}))

    for phase in phases:
        for vm, (wx, wy) in ((vm, pos[(phase, vm)]) for vm in workers[phase]):
            if any(abs(cy - wy) < 1 for _, cy in chips.get(phase, [])):
                f.add(Shape("arrow", x=wx + 78, y=wy, x2=wx + COL - 118, y2=wy,
                            color=color_for(vm), meta={"vm": vm}))

    for a, b in zip(phases, phases[1:]):
        for cx, cy in chips.get(a, []):
            for vm in workers[b]:
                wx, wy = pos[(b, vm)]
                # The shuffle, drawn as texture: it is many-to-many by nature,
                # and at full strength it hides everything it crosses.
                f.add(Shape("arrow", x=cx + 118, y=cy, x2=wx - 78, y2=wy,
                            color=CONTROL_COLOR, style="control",
                            meta={"shuffle": True, "to": vm}))

    done = list(trace.of_kind("done"))
    if done and _payload_text(done[-1]):
        caption(x, "the result")
        e = done[-1]
        f.add(Shape("box", x=x, y=0, w=250, h=40, text=_clip(_payload_text(e), 24),
                    color=STATUS_COLORS["ok"], t_in=e["t"], meta={"vm": e.get("vm")}))
        # From wherever the last column actually ended, which is the chips when
        # the final phase produced any and the machines themselves when it did not.
        for vm in workers[phases[-1]]:
            wx, wy = pos[(phases[-1], vm)]
            # Leaves the edge of whatever it leaves: a chip is 118 wide either
            # side of its centre, a machine 78 — an arrow that starts in mid-air
            # is attached to nothing as far as the eye is concerned.
            chip_x = [cx + 118 for cx, cy in chips.get(phases[-1], []) if abs(cy - wy) < 1]
            src = (chip_x or [wx + 78])[0]
            f.add(Shape("arrow", x=src, y=wy, x2=x - 130, y2=0,
                        color=STATUS_COLORS["ok"], style="control", t_in=e["t"], meta={}))
    return f


# Order matters: this is the order the tabs appear in and the order a viewer
# meets them. Dataflow leads because it is the picture of the work itself.
SCENES = {
    "dataflow": dataflow,
    "spacetime": spacetime,
    "topology": topology,
    "gantt": gantt,
}


def build(trace: Trace, scene: str) -> Frame:
    if scene not in SCENES:
        raise ValueError(f"unknown scene '{scene}'; known scenes: {', '.join(sorted(SCENES))}")
    f = SCENES[scene](trace)
    # Every scene plays over the run's own clock, whether or not its builder
    # laid time out along an axis: a shape that carries a time must be able to
    # arrive at it, or scrubbing and the video would both show one flat instant.
    latest = max([s.t_in for s in f] + [0.0])
    f.duration_ms = max(f.duration_ms, latest, 1.0)
    return f
