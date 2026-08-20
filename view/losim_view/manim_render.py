"""Frame -> manim video.

Small on purpose: the shapes layer has already decided what to draw and when,
so this only maps primitives to mobjects. manim is imported lazily, so the rest
of the viewer works without it installed.
"""
from __future__ import annotations

from .shapes import Frame


def scene_for(frame: Frame, name: str = "LosimScene", module: str | None = None):
    """Build a manim Scene subclass bound to this frame.

    manim only discovers scenes whose __module__ is the file it was given, so
    the generated class is stamped with the caller's module.
    """
    from manim import (Scene, Text, Rectangle, Ellipse, Line, VGroup,   # noqa: F401
                       FadeIn, FadeOut, Write, ReplacementTransform, LaggedStart,
                       UP, WHITE)

    SCALE = 1.0 / 110.0
    PLAY_SECONDS = 14.0        # how long the simulated run takes on screen
    DEAD = "#E05252"

    class LosimScene(Scene):
        def construct(self):
            f = frame                     # already fitted by whoever built it

            if f.title:
                t = Text(f.title, font_size=40, weight="BOLD")
                self.play(Write(t)); self.wait(0.5); self.play(FadeOut(t))

            built = {id(s): self._build(s) for s in f}
            showing: dict = {}

            def appear(s):
                m = built[id(s)]
                if m is None:
                    return None
                key = None
                if s.kind == "state":
                    key = f"{s.meta.get('vm')}|{s.meta.get('key')}"
                # State is one badge that keeps being rewritten, not a pile of
                # readings — so the number visibly moves.
                if key is not None and key in showing:
                    old = showing[key]
                    showing[key] = m
                    return ReplacementTransform(old, m)
                if key is not None:
                    showing[key] = m
                return FadeIn(m, shift=UP * 0.12)

            static = [s for s in f if s.t_in <= 0]
            timed = [s for s in f if s.t_in > 0]

            base = [a for a in (appear(s) for s in static) if a is not None]
            if base:
                self.play(LaggedStart(*base, lag_ratio=0.06))

            # A machine that dies must stop looking alive — losing the work IS
            # the failure, so it may not be drawn the same as finishing it.
            deaths = {}
            for s in f:
                if s.kind == "ellipse" and s.meta.get("diedAt", -1) >= 0 and built[id(s)] is not None:
                    deaths.setdefault(float(s.meta["diedAt"]), []).append(built[id(s)])

            # The clock on screen is the simulated clock: a gap in virtual time
            # is a pause of proportional length, which is what makes an expensive
            # phase look expensive rather than merely come later.
            span = max(1.0, f.duration_ms)
            moments = sorted({s.t_in for s in timed} | set(deaths))
            prev = 0.0
            for t_in in moments:
                gap = (t_in - prev) / span * PLAY_SECONDS
                if gap > 0.05:
                    self.wait(min(gap, 2.5))
                prev = t_in
                group = [a for a in (appear(s) for s in timed if s.t_in == t_in) if a is not None]
                # Simultaneous things animate together: concurrency should look concurrent.
                for m in deaths.get(t_in, []):
                    group.append(m.animate.set_color(DEAD))
                if group:
                    self.play(LaggedStart(*group, lag_ratio=0.1), run_time=0.6)
            self.wait(1.2)

        def _build(self, s):
            from manim import Text, Rectangle, Ellipse, Line, VGroup
            x, y = (s.x - 800) * SCALE, (450 - s.y) * SCALE
            if s.kind == "arrow":
                x2, y2 = (s.x2 - 800) * SCALE, (450 - s.y2) * SCALE
                line = Line([x, y, 0], [x2, y2, 0], stroke_width=2, color=s.color)
                if s.text:
                    lbl = Text(s.text, font_size=14)
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
                lbl = Text(s.text, font_size=18, weight="BOLD")
                lbl.move_to([x, y, 0])
                return lbl
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
