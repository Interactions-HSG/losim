"""losim-view — draws what the simulator ran.

The simulator writes trace.json; nothing here knows anything about Java. The
pipeline is one-directional and each stage is testable on its own:

    trace.json  ->  Frame (shapes)  ->  SVG / HTML player / manim video
                                    ->  the bill
"""
from .trace import Trace, load
from .shapes import Frame, Shape, spacetime, topology, gantt, dataflow, SCENES
from .values import Visual, default_visual

__all__ = [
    "Trace", "load", "Frame", "Shape",
    "spacetime", "topology", "gantt", "dataflow", "SCENES",
    "Visual", "default_visual",
]
