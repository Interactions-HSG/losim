"""Frame -> manim film.

The shapes layer has already decided what to draw and when; this decides how it
moves. A message is not a line that appears — it is a packet that leaves one
machine, crosses the gap for as long as the crossing really took, and arrives.
That is the whole difference between a diagram and a film of a system running.

manim is imported lazily, so the rest of the viewer works without it installed.
"""
from __future__ import annotations

from .shapes import Frame

SCALE = 1.0 / 110.0            # frame units (1600x900) -> manim units
PLAY_SECONDS = 16.0            # how long a run takes on screen, whatever its clock
DEAD = "#E05252"


def _xy(x: float, y: float) -> list[float]:
    return [(x - 800) * SCALE, (450 - y) * SCALE, 0]


def scene_for(frames, name: str = "LosimScene", module: str | None = None):
    """Build a manim Scene playing one Frame, or several in sequence.

    manim only discovers scenes whose __module__ is the file it was given, so
    the generated class is stamped with the caller's module.
    """
    parts = [frames] if isinstance(frames, Frame) else list(frames)

    from manim import (Scene, Text, Rectangle, Ellipse, Line, Dot, VGroup,   # noqa: F401
                       FadeIn, FadeOut, Write, ReplacementTransform, LaggedStart,
                       UP, WHITE)

    class LosimScene(Scene):
        def construct(self):
            for i, f in enumerate(parts):
                if i:
                    self.play(*[FadeOut(m) for m in self.mobjects], run_time=0.4)
                self.part(f)
            self.wait(1.0)

        # ------------------------------------------------------------ a part

        def part(self, f: Frame):
            from manim import Text, FadeIn, FadeOut, LaggedStart, ReplacementTransform

            if f.title:
                # Placed in the frame's own coordinates, in the band fit() kept
                # clear — the browser puts its heading in exactly that band, and
                # a title floated to the edge lands on top of the first machine.
                head = Text(f.title, font_size=32, weight="BOLD").move_to(_xy(800, 42))
                sub = None
                if f.subtitle:
                    sub = Text(f.subtitle, font_size=17, color="#8A93A6")
                    if sub.width > 12.0:
                        sub.scale(12.0 / sub.width)
                    sub.move_to(_xy(800, 82))
                self.play(FadeIn(head, shift=UP * 0.1), run_time=0.5)
                if sub:
                    self.play(FadeIn(sub), run_time=0.3)

            messages = [s for s in f if s.kind == "arrow" and s.meta.get("arrivedAtMs") is not None]
            scenery = [s for s in f if s not in messages]

            built = {id(s): self._build(s) for s in scenery}
            showing: dict = {}

            def appear(s):
                m = built[id(s)]
                if m is None:
                    return None
                key = f"{s.meta.get('vm')}|{s.meta.get('key')}" if s.kind == "state" else None
                # State is one badge that keeps being rewritten, not a pile of
                # readings — so the number visibly moves.
                if key is not None and key in showing:
                    old = showing[key]
                    showing[key] = m
                    return ReplacementTransform(old, m)
                if key is not None:
                    showing[key] = m
                return FadeIn(m, shift=UP * 0.12)

            base = [a for a in (appear(s) for s in scenery if s.t_in <= 0) if a is not None]
            if base:
                self.play(LaggedStart(*base, lag_ratio=0.05), run_time=1.2)

            # Everything that has a time, in time order: messages in flight and
            # the marks a run leaves behind, on one clock.
            span = max(1.0, f.duration_ms)
            deaths = {float(s.meta["diedAt"]): built[id(s)]
                      for s in scenery
                      if s.kind == "ellipse" and s.meta.get("diedAt", -1) >= 0
                      and built[id(s)] is not None}
            timed = sorted([s for s in scenery if s.t_in > 0], key=lambda s: s.t_in)
            moments = sorted({s.t_in for s in timed} | {m.t_in for m in messages} | set(deaths))

            prev = 0.0
            for when in moments:
                gap = (when - prev) / span * PLAY_SECONDS
                if gap > 0.05:
                    self.wait(min(gap, 2.0))
                prev = when

                flights = [self._flight(s, span) for s in messages if s.t_in == when]
                marks = [a for a in (appear(s) for s in timed if s.t_in == when) if a is not None]
                if when in deaths:
                    # Losing the work IS the failure; it may not look like finishing.
                    marks.append(deaths[when].animate.set_color(DEAD))
                if flights:
                    self.play(*[anim for anim, _, _ in flights], *marks,
                              run_time=max(rt for _, rt, _ in flights))
                    for _, _, cleanup in flights:
                        cleanup(self)
                elif marks:
                    self.play(LaggedStart(*marks, lag_ratio=0.1), run_time=0.5)

        # ------------------------------------------------------- one message

        def _flight(self, s, span: float):
            """A packet leaving, crossing, arriving — and the trail it leaves."""
            from manim import Dot, Line, Text, VGroup, FadeOut

            start, end = _xy(s.x, s.y), _xy(s.x2, s.y2)
            trail = Line(start, end, stroke_width=1.5, color=s.color, stroke_opacity=0.28)
            if s.style == "control":
                trail = trail.set_stroke(opacity=0.18)
            packet = Dot(start, radius=0.075, color=s.color)
            label = None
            if s.text:
                label = Text(s.text, font_size=13, color="#C9D1E0")
                label.next_to(packet, direction=UP, buff=0.08)
                group = VGroup(packet, label)
            else:
                group = packet
            self.add(trail, group)

            flight_ms = max(1.0, float(s.meta.get("arrivedAtMs", s.t_in)) - float(s.t_in))
            run_time = min(2.0, max(0.35, flight_ms / span * PLAY_SECONDS))
            anim = group.animate.shift([end[0] - start[0], end[1] - start[1], 0])

            def cleanup(scene):
                scene.remove(group)

            return anim, run_time, cleanup

        # -------------------------------------------------------- primitives

        def _build(self, s):
            from manim import Text, Rectangle, Ellipse, Line, VGroup
            x, y, _ = _xy(s.x, s.y)
            if s.kind == "arrow":
                x2, y2, _ = _xy(s.x2, s.y2)
                line = Line([x, y, 0], [x2, y2, 0], stroke_width=2, color=s.color)
                if s.style == "control":
                    line = line.set_stroke(opacity=0.35)
                if s.text:
                    lbl = Text(s.text, font_size=13)
                    lbl.move_to([(x + x2) / 2, (y + y2) / 2 + 0.12, 0])
                    return VGroup(line, lbl)
                return line
            if s.kind == "ellipse":
                e = Ellipse(width=max(0.2, s.w * SCALE), height=max(0.2, s.h * SCALE), color=s.color)
                e.move_to([x, y, 0])
                if not s.text:
                    return e
                lbl = Text(s.text, font_size=16)
                room = max(s.w * SCALE - 0.15, 0.1)
                if lbl.width > room:
                    lbl.scale(room / lbl.width)
                lbl.move_to(e)
                return VGroup(e, lbl)
            if s.kind == "lane":
                line = Line([x - s.w * SCALE / 2, y, 0], [x + s.w * SCALE / 2, y, 0],
                            stroke_width=1, color="#2A2F3A")
                if not s.text:
                    return line
                # A lane without its machine's name is an anonymous line: the
                # browser labels it, so the video must too or they disagree.
                lbl = Text(s.text, font_size=15, color=s.color)
                lbl.next_to(line, direction=[-1, 0, 0], buff=0.12)
                return VGroup(line, lbl)
            if s.kind == "label":
                return Text(s.text, font_size=18, weight="BOLD").move_to([x, y, 0])
            box = Rectangle(width=max(0.15, s.w * SCALE), height=max(0.15, s.h * SCALE),
                            stroke_color=s.color, fill_color=s.color, fill_opacity=0.12,
                            stroke_width=2)
            box.move_to([x, y, 0])
            if not s.text:
                return box
            lbl = Text(s.text, font_size=13)
            room = max(s.w * SCALE - 0.1, 0.08)
            if lbl.width > room:
                lbl.scale(room / lbl.width)
            lbl.move_to(box)
            return VGroup(box, lbl)

    return type(name, (LosimScene,), {"__module__": module or __name__})
