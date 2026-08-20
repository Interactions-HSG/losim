"""The sidecar: a webpage where a student watches their own system run.

It is one process, started next to a lab rather than inside it. It watches the
traces a lab writes, serves the same picture the video will show, and drives the
manim renderer out-of-process so a machine without manim still gets a video.

Read-only by design: it observes runs, it does not start them. A lab is run from
the terminal exactly as before, and this page notices.

Only the standard library, so `./serve.sh` needs nothing installed.
"""
from __future__ import annotations

import itertools
import json
import threading
import time
import traceback
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from . import bill as bill_mod
from . import manim_runtime, studio
from .shapes import SCENES, build
from .trace import Trace, load

# What a student wants to read as a story of the run, in the order it happened.
STORY_KINDS = ("boot", "log", "done", "kill", "restart", "freeze", "degrade",
               "spot_notice", "spot_reclaim", "rpc_timeout", "rpc_dropped", "oom", "nospace")


@dataclass
class Job:
    id: str
    kind: str                                    # render | install
    what: str
    state: str = "running"                       # running | done | failed
    log: list[str] = field(default_factory=list)
    video: str | None = None
    error: str | None = None
    started: float = field(default_factory=time.time)

    def as_json(self) -> dict:
        return {"id": self.id, "kind": self.kind, "what": self.what, "state": self.state,
                "log": self.log[-200:], "video": self.video, "error": self.error,
                "seconds": round(time.time() - self.started, 1)}


class Studio:
    """The state behind the page: which traces exist, and what is rendering."""

    def __init__(self, root: Path, watch: list[Path], media: Path | None = None):
        self.root = Path(root).resolve()
        self.watch = [Path(w).resolve() for w in watch]
        # Videos land beside the runs they came from, not inside the framework:
        # a lab lives in the student's repository and .losim/ is disposable.
        self.media = (Path(media).resolve() if media
                      else (self.watch[0] if self.watch else self.root / "build") / "media")
        self.jobs: dict[str, Job] = {}
        self._ids = itertools.count(1)
        self._lock = threading.Lock()
        self._cache: dict[Path, tuple[float, Trace]] = {}

    # ------------------------------------------------------------- traces

    def traces(self) -> list[dict]:
        """Every losim trace under the watched directories, newest first.

        A file that is not a trace is skipped in silence — students keep all
        sorts of JSON around, and the page is not the place to complain.
        """
        seen: dict[str, dict] = {}
        for i, d in enumerate(self.watch):
            if not d.exists():
                continue
            for p in sorted(d.rglob("*.json")):
                if self.media in p.parents or p.name == "frame.json":
                    continue
                try:
                    st = p.stat()
                    tr = self._load(p, st.st_mtime)
                except Exception:                        # noqa: BLE001
                    continue
                # An id is "<which watched directory>/<path inside it>", never a
                # path from this machine: it goes in a URL, and it is the only
                # thing the server will resolve.
                key = f"{i}/{p.relative_to(d)}"
                seen[key] = {
                    "id": key, "name": tr.name, "path": key,
                    "mtime": st.st_mtime, "seed": tr.meta.get("seed"),
                    "endedMs": tr.ended_ms, "events": len(tr.events),
                    "finished": tr.meta.get("finished", True),
                    "checks": tr.meta.get("checks", []),
                    "cost": tr.bill.get("cost"), "currency": tr.bill.get("currency", "CHF"),
                }
        return sorted(seen.values(), key=lambda r: -r["mtime"])

    def _load(self, p: Path, mtime: float) -> Trace:
        hit = self._cache.get(p)
        if hit and hit[0] == mtime:
            return hit[1]
        tr = load(p)
        self._cache[p] = (mtime, tr)
        return tr

    def _resolve(self, run_id: str) -> Path:
        which, _, rel = run_id.partition("/")
        if not which.isdigit() or int(which) >= len(self.watch) or not rel:
            raise FileNotFoundError(run_id)
        base = self.watch[int(which)]
        p = (base / rel).resolve()
        if not p.is_relative_to(base) or not p.is_file():
            raise FileNotFoundError(run_id)
        return p

    def run_detail(self, run_id: str) -> dict:
        p = self._resolve(run_id)
        tr = self._load(p, p.stat().st_mtime)
        scenes, warnings = {}, {}
        for name in SCENES:
            f = build(tr, name).fit()
            scenes[name] = f.to_json()
            warnings[name] = f.warnings()
        story = [{"t": e["t"], "kind": e["kind"], "vm": e.get("vm", ""),
                  "detail": e.get("detail", {})}
                 for e in tr.events if e["kind"] in STORY_KINDS]
        return {
            "id": run_id, "name": tr.name, "meta": tr.meta, "mtime": p.stat().st_mtime,
            "scenes": scenes, "warnings": warnings, "story": story,
            "vms": tr.vms, "metrics": tr.metrics,
            "bill": {"pnl": tr.bill, "why": bill_mod.WHY, "buckets": bill_mod.BUCKETS},
        }

    # --------------------------------------------------------------- jobs

    def _spawn(self, kind: str, what: str, work) -> Job:
        job = Job(id=f"j{next(self._ids)}", kind=kind, what=what)
        with self._lock:
            self.jobs[job.id] = job

        def run():
            try:
                work(job)
                job.state = "done"
            except Exception as e:                       # noqa: BLE001
                job.state = "failed"
                job.error = str(e)
                job.log.append(traceback.format_exc().strip().splitlines()[-1])
        threading.Thread(target=run, name=job.id, daemon=True).start()
        return job

    def render(self, run_id: str, scene: str, quality: str = "l") -> Job:
        if scene not in SCENES:
            raise ValueError(f"unknown scene '{scene}'")
        rt = manim_runtime.best(self.root)
        if rt is None:
            raise RuntimeError("no manim sidecar is installed yet")
        p = self._resolve(run_id)
        tr = self._load(p, p.stat().st_mtime)
        name = f"{tr.name}_{scene}"
        out = self.media / name
        out.mkdir(parents=True, exist_ok=True)
        frame_path = out / "frame.json"
        frame_path.write_text(json.dumps(build(tr, scene).fit().to_json()))

        def work(job):
            log = job.log.append
            log(f"rendering {scene} of {tr.name} via the {rt.kind} sidecar")
            video = rt.render(frame_path, scene, out, quality=quality, name=name, log=log)
            job.video = "/media/" + str(video.resolve().relative_to(self.media))
            log(f"done — {video.stat().st_size // 1024} kB")

        return self._spawn("render", f"{tr.name} · {scene}", work)

    def install(self, kind: str | None) -> Job:
        def work(job):
            manim_runtime.provision(self.root, kind, job.log.append)
        return self._spawn("install", f"install manim ({kind or 'best available'})", work)

    def state(self) -> dict:
        return {
            "runs": self.traces(),
            "manim": manim_runtime.status(self.root),
            "jobs": [j.as_json() for j in list(self.jobs.values())[-6:]],
            "scenes": sorted(SCENES),
            "root": str(self.root),
        }


# --------------------------------------------------------------------- http

def _handler(app: Studio):
    class Handler(BaseHTTPRequestHandler):
        server_version = "losim-sidecar"

        def log_message(self, fmt, *args):                # quiet: the page is the output
            pass

        # ---------------------------------------------------------- replies

        def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, obj, code: int = 200):
            self._send(code, json.dumps(obj).encode(), "application/json")

        def _fail(self, e: Exception, code: int = 400):
            self._json({"error": str(e) or e.__class__.__name__}, code)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(n) or b"{}")

        # ----------------------------------------------------------- routes

        def do_GET(self):                                 # noqa: N802
            path = urlparse(self.path).path
            try:
                if path == "/":
                    return self._send(200, studio.page().encode(), "text/html; charset=utf-8")
                if path == "/api/state":
                    return self._json(app.state())
                if path.startswith("/api/run/"):
                    return self._json(app.run_detail(path[len("/api/run/"):]))
                if path.startswith("/api/job/"):
                    job = app.jobs.get(path[len("/api/job/"):])
                    return self._json(job.as_json()) if job else self._json({"error": "no such job"}, 404)
                if path.startswith("/media/"):
                    return self._media(path[len("/media/"):])
                return self._json({"error": "not found"}, 404)
            except FileNotFoundError as e:
                return self._fail(e, 404)
            except Exception as e:                        # noqa: BLE001
                return self._fail(e, 500)

        do_HEAD = do_GET

        def do_POST(self):                                # noqa: N802
            path = urlparse(self.path).path
            try:
                body = self._body()
                if path == "/api/render":
                    job = app.render(body["run"], body.get("scene", "spacetime"),
                                     body.get("quality", "l"))
                    return self._json(job.as_json())
                if path == "/api/install":
                    return self._json(app.install(body.get("kind")).as_json())
                return self._json({"error": "not found"}, 404)
            except Exception as e:                        # noqa: BLE001
                return self._fail(e, 400)

        def _media(self, rel: str):
            p = (app.media / rel).resolve()
            if not p.is_file() or not p.is_relative_to(app.media):
                return self._json({"error": "not found"}, 404)
            ctype = {".mp4": "video/mp4", ".png": "image/png",
                     ".svg": "image/svg+xml"}.get(p.suffix, "application/octet-stream")
            self._send(200, p.read_bytes(), ctype, {"Accept-Ranges": "none"})

    return Handler


def serve(root: Path, watch: list[Path], host: str = "127.0.0.1", port: int = 8000,
          media: Path | None = None, block: bool = True) -> ThreadingHTTPServer:
    app = Studio(root, watch, media)
    httpd = ThreadingHTTPServer((host, port), _handler(app))
    httpd.studio = app                                    # tests reach in here
    where = f"http://{'localhost' if host == '127.0.0.1' else host}:{httpd.server_port}"
    m = app.state()["manim"]
    print(f"losim studio on {where}")
    print(f"  watching  {', '.join(str(w) for w in app.watch)}")
    print(f"  video     {m['detail'] if m['ready'] else 'no manim yet — the page can install it'}")
    if not block:
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        return httpd
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
    finally:
        httpd.server_close()
    return httpd
