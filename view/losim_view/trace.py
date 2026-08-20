"""Reading what the simulator wrote."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

SCHEMA_VERSION = 1


@dataclass
class Trace:
    meta: dict
    events: list[dict]

    @property
    def name(self) -> str:
        return self.meta.get("name", "run")

    @property
    def ended_ms(self) -> int:
        return int(self.meta.get("endedAtMs", self.span()[1]))

    @property
    def vms(self) -> list[dict]:
        return list(self.meta.get("vms", []))

    @property
    def vm_names(self) -> list[str]:
        return [v["name"] for v in self.vms]

    @property
    def bill(self) -> dict:
        return self.meta.get("pnl", {})

    @property
    def metrics(self) -> dict:
        return self.meta.get("metrics", {})

    def of_kind(self, *kinds: str) -> Iterator[dict]:
        wanted = set(kinds)
        for e in self.events:
            if e["kind"] in wanted:
                yield e

    def span(self) -> tuple[int, int]:
        if not self.events:
            return 0, 1
        return self.events[0]["t"], max(e["t"] for e in self.events) or 1


def load(path: str | Path) -> Trace:
    raw = json.loads(Path(path).read_text())
    schema = raw.get("schema")
    if schema != SCHEMA_VERSION:
        raise ValueError(
            f"{path}: trace schema {schema} but this viewer speaks {SCHEMA_VERSION}. "
            "Rebuild the simulator or update the viewer — the contract is versioned "
            "so a mismatch fails loudly instead of drawing nonsense."
        )
    return Trace(meta=raw.get("meta", {}), events=raw.get("events", []))
