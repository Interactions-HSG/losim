"""Every value knows how to be drawn.

The default covers the shapes a trace actually contains, so nothing needs an
annotation. A payload is not just data — it is something a student should be
able to see, and the picture should follow the program: change what a mapper
emits and the chip changes with it.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Visual:
    text: str
    kind: str = "chip"          # chip | card | stack | badge
    color_key: str = ""
    detail: str = ""
    parts: list = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "text": self.text,
            "kind": self.kind,
            "color_key": self.color_key,
            "detail": self.detail,
            "parts": [p.to_json() for p in self.parts],
        }


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def default_visual(value: Any, limit: int = 24) -> Visual:
    """The built-in appearance for any value the trace can carry."""
    if value is None:
        return Visual("-")

    if isinstance(value, bool):
        return Visual("true" if value else "false")

    if isinstance(value, (int, float)):
        return Visual(f"{value:,}" if isinstance(value, int) else f"{value:g}")

    if isinstance(value, str):
        return Visual(_clip(value, limit), kind="card",
                      detail=value if len(value) > limit else "")

    if isinstance(value, dict):
        # a single-entry dict reads better as "key: value"
        if len(value) == 1:
            k, v = next(iter(value.items()))
            inner = default_visual(v, limit).text
            return Visual(_clip(f"{k}: {inner}", limit), color_key=str(k),
                          detail=f"{k} = {v!r}")
        body = ", ".join(f"{k}={default_visual(v, 10).text}" for k, v in list(value.items())[:3])
        more = "" if len(value) <= 3 else f" +{len(value) - 3}"
        return Visual(_clip(body + more, limit), kind="card",
                      detail=f"{len(value)} field(s)",
                      parts=[default_visual(v, 12) for v in list(value.values())[:6]])

    if isinstance(value, (list, tuple)):
        head = ", ".join(default_visual(v, 10).text for v in value[:3])
        more = "" if len(value) <= 3 else f" +{len(value) - 3}"
        return Visual(_clip(f"[{head}{more}]", limit), kind="stack",
                      detail=f"{len(value)} item(s)",
                      parts=[default_visual(v, 12) for v in value[:6]])

    return Visual(_clip(str(value), limit))
