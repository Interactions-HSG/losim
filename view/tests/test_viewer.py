"""Tests for the viewer. It must draw any lab's trace without knowing the lab."""
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "view"))

from losim_view import load, shapes                      # noqa: E402
from losim_view import bill as bill_mod                  # noqa: E402
from losim_view import manim_runtime, player, sidecar, studio, svg   # noqa: E402
from losim_view.values import default_visual             # noqa: E402

TRACES = sorted((ROOT / "build").glob("*.json"))

failures, passed = [], 0


def test(name):
    def deco(fn):
        global passed
        try:
            fn()
            passed += 1
            print(f"  ok   {name}")
        except Exception as e:                            # noqa: BLE001
            failures.append(f"{name}: {e}")
            print(f"  FAIL {name} — {e}")
    return deco


print("\nviewer")

@test("every scene builds from every lab trace")
def _():
    assert TRACES, "no traces in build/ — run the labs first"
    for t in TRACES:
        tr = load(t)
        for scene in shapes.SCENES:
            f = shapes.build(tr, scene).fit()
            assert len(f) > 0, f"{t.name}/{scene} produced nothing"


@test("colour is deterministic and never uses hash()")
def _():
    a = shapes.color_for("w3")
    b = shapes.color_for("w3")
    assert a == b
    out = subprocess.run(
        [sys.executable, "-c",
         "import sys; sys.path.insert(0,'view'); from losim_view.shapes import color_for; print(color_for('w3'))"],
        cwd=ROOT, capture_output=True, text=True, env={"PYTHONHASHSEED": "1", "PATH": "/usr/bin:/bin"})
    assert out.stdout.strip() == a, "colour differed under a different hash seed"


@test("fit() puts everything inside the frame")
def _():
    tr = load(TRACES[0])
    f = shapes.build(tr, "topology").fit()
    x0, y0, x1, y1 = f.bounds()
    assert x0 >= -1 and y0 >= -1, (x0, y0)
    assert x1 <= shapes.FRAME_W + 1 and y1 <= shapes.FRAME_H + 1, (x1, y1)


@test("warnings are reported rather than quietly rendered")
def _():
    f = shapes.Frame(title="crowded")
    for i in range(5000):
        f.add(shapes.Shape("box", x=i, y=0, w=2, h=2, text="x"))
    w = f.warnings()
    assert any("too busy" in x for x in w), w
    assert any("too small" in x for x in w), w


@test("a schema mismatch fails loudly instead of drawing nonsense")
def _():
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        json.dump({"schema": 99, "meta": {}, "events": []}, fh)
        path = fh.name
    try:
        load(path)
    except ValueError as e:
        assert "schema" in str(e)
    else:
        raise AssertionError("expected a schema error")


@test("values draw themselves without annotation")
def _():
    assert default_visual({"the": 8}).text == "the: 8"
    assert default_visual([1, 2, 3, 4, 5]).text.startswith("[1, 2, 3 +2")
    assert default_visual(None).text == "-"
    assert default_visual("x" * 80).kind == "card"


@test("the player is self-contained: no external requests")
def _():
    tr = load(TRACES[0])
    frames = {n: shapes.build(tr, n).fit() for n in shapes.SCENES}
    html = player.render(frames, tr.name, {})
    # the SVG namespace is a URI, not a request — everything else must be local
    stripped = html.replace("http://www.w3.org/2000/svg", "")
    for bad in ("http://", "https://", "<script src", "cdn.", "fetch(", "XMLHttpRequest"):
        assert bad not in stripped, f"player reaches outside for {bad}"
    assert "__DATA__" not in html, "payload was not substituted"


@test("static SVG escapes text it did not write")
def _():
    f = shapes.Frame(title='<script>alert(1)</script>')
    f.add(shapes.Shape("box", x=10, y=10, w=10, h=10, text='"><g'))
    out = svg.render(f)
    assert "<script>" not in out
    assert "&lt;script&gt;" in out


@test("every lab produces a bill with all five buckets")
def _():
    for t in TRACES:
        b = load(t).bill
        assert b, f"{t.name} has no bill"
        for bucket in bill_mod.BUCKETS:
            assert bucket in b["buckets"], f"{t.name} missing {bucket}"


@test("no franc figure appears without the measure it came from")
def _():
    for t in TRACES:
        for line in load(t).bill.get("lines", []):
            assert line["unit"], line
            assert line["why"], line
            # amounts are rounded to the currency's smallest unit on purpose
            assert abs(line["quantity"] * line["unitPrice"] - line["amount"]) < 5e-5, line


# ------------------------------------------------------- the manim sidecar

@test("a message that was sent is a message that is drawn")
def _():
    # The regression this guards: the trace nests payloads under "detail", so a
    # builder reading e["to"] saw None, drew no arrow at all, and a run with 26
    # messages rendered as a run with none — in the video and the browser alike.
    for t in TRACES:
        tr = load(t)
        sent = [e for e in tr.events if e["kind"] in ("send", "rpc_call")
                and e.get("to") in tr.vm_names]
        if not sent:
            continue
        for scene in ("spacetime", "topology"):
            arrows = [s for s in shapes.build(tr, scene) if s.kind == "arrow"]
            assert len(arrows) >= len(sent), (
                f"{t.name}/{scene}: {len(sent)} messages in the trace, {len(arrows)} drawn")


@test("a value the program produced is the value on screen")
def _():
    tr = load(ROOT / "build/wordcount.json")
    states = [s for s in shapes.build(tr, "spacetime") if s.kind == "state"]
    assert states, "no state was drawn"
    for s in states:
        assert not s.text.startswith("None="), f"state badge lost its key: {s.text}"
        assert s.meta.get("key"), s.meta
    written = {(e["vm"], e["key"]) for e in tr.of_kind("state")}
    drawn = {(s.meta["vm"], s.meta["key"]) for s in states}
    assert written == drawn, written ^ drawn


@test("a Frame survives the trip to the sidecar and back")
def _():
    tr = load(TRACES[0])
    before = shapes.build(tr, "spacetime").fit()
    after = shapes.Frame.from_json(json.loads(json.dumps(before.to_json())))
    assert after.to_json() == before.to_json()
    assert len(after) == len(before) and after.scene == before.scene
    # the meta a shape carries is what the video needs to draw death correctly
    assert [s.meta for s in after] == [s.meta for s in before]


@test("every scene plays over the run's own clock")
def _():
    for t in TRACES:
        tr = load(t)
        for scene in shapes.SCENES:
            f = shapes.build(tr, scene)
            latest = max([s.t_in for s in f] + [0])
            assert f.duration_ms >= latest, (
                f"{t.name}/{scene}: shapes arrive at {latest} ms but the scene "
                f"is only {f.duration_ms} ms long, so they could never appear")


@test("the docker sidecar sees the framework and the output, and nothing else")
def _():
    rt = manim_runtime.Runtime("docker", "test", ROOT)
    out = ROOT / "build/media/x"
    mounts = rt.mounts(out)
    assert rt._to_container(out / "frame.json", mounts) == "/out/frame.json"
    assert rt._to_container(ROOT / "view/losim_view/shapes.py", mounts) == "/work/view/losim_view/shapes.py"
    assert rt._from_container("/out/videos/a.mp4", mounts) == out / "videos/a.mp4"
    try:
        rt._to_container(Path("/etc/passwd"), mounts)
    except RuntimeError as e:
        assert "not inside" in str(e)
    else:
        raise AssertionError("a path outside the mounts should not be reachable")
    # the framework's own code goes in read-only: a render must not edit it
    argv, _ = rt._command(out / "frame.json", "gantt", out, "l", "n")
    assert f"{ROOT}:/work:ro" in argv, argv


@test("the render command is the same job whichever sidecar runs it")
def _():
    frame, out = ROOT / "build/f.json", ROOT / "build/m"
    argv_d, _ = manim_runtime.Runtime("docker", "d", ROOT)._command(frame, "gantt", out, "l", "n")
    argv_v, env = manim_runtime.Runtime("venv", "v", ROOT)._command(frame, "gantt", out, "l", "n")
    job = ["-m", "losim_view.render_job"]
    assert argv_d[:2] == ["docker", "run"] and argv_d[argv_d.index(job[0]):][:2] == job
    assert argv_v[1:3] == job
    assert str(ROOT / "view") in env["PYTHONPATH"], "the sidecar must be able to import losim_view"
    # an unknown quality would reach manim as a config key, so it is checked here
    assert "--quality" in argv_d and "l" in argv_d


@test("manim is only imported where it renders, never to decide what to draw")
def _():
    # Not "nobody imports manim" — somebody must — but that every import of it
    # is inside a function, so importing the viewer never needs it installed.
    import re
    top_level = re.compile(r"(?m)^(?:from manim\b|import manim\b)")
    offenders = [p.name for p in (ROOT / "view/losim_view").glob("*.py")
                 if top_level.search(p.read_text())]
    assert not offenders, f"{offenders} would make manim a hard dependency of the viewer"


# ------------------------------------------------------------- the studio

def _studio():
    srv = sidecar.serve(ROOT, [ROOT / "build"], port=0, block=False)
    return srv, f"http://127.0.0.1:{srv.server_port}"


def _get(base, path):
    with urllib.request.urlopen(base + path) as r:
        return json.loads(r.read())


@test("the studio serves a page and finds every run")
def _():
    srv, base = _studio()
    try:
        with urllib.request.urlopen(base + "/") as r:
            html = r.read().decode()
        assert "drawFrame" in html and "__DRAW__" not in html
        state = _get(base, "/api/state")
        names = {r["name"] for r in state["runs"]}
        assert len(names) >= len(TRACES) - 1, (names, [t.name for t in TRACES])
        assert set(state["scenes"]) == set(shapes.SCENES)
        run = _get(base, "/api/run/" + state["runs"][0]["id"])
        assert set(run["scenes"]) == set(shapes.SCENES)
        assert run["vms"] and "story" in run
    finally:
        srv.shutdown()


@test("the studio refuses to read outside the directories it watches")
def _():
    srv, base = _studio()
    try:
        for bad in ("/api/run/0/../../../etc/passwd", "/media/../../etc/passwd",
                    "/api/run/../../../etc/passwd", "/api/run/9/x.json",
                    "/api/run/losim/src/losim/cli/Main.java"):
            try:
                urllib.request.urlopen(base + bad)
            except urllib.error.HTTPError as e:
                assert e.code in (400, 404), (bad, e.code)
            else:
                raise AssertionError(f"{bad} was served")
    finally:
        srv.shutdown()


@test("the studio names the scene it cannot draw")
def _():
    srv, base = _studio()
    try:
        run = _get(base, "/api/state")["runs"][0]["id"]
        req = urllib.request.Request(base + "/api/render",
                                     json.dumps({"run": run, "scene": "nope"}).encode(),
                                     {"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            assert "nope" in json.loads(e.read())["error"]
        else:
            raise AssertionError("an unknown scene should be refused")
    finally:
        srv.shutdown()


@test("the drawing code brings everything it needs")
def _():
    # The studio page and the saved player both paste DRAW_JS in; a constant
    # left behind in one template is a page that throws on its first frame.
    from losim_view.player import DRAW_JS
    for const in ("const NS", "const FG"):
        assert const in DRAW_JS, f"{const} is not in the shared drawing code"
    tr = load(TRACES[0])
    saved = player.render({n: shapes.build(tr, n).fit() for n in shapes.SCENES}, tr.name, {})
    for html, who in ((saved, "the player"), (studio.page(), "the studio")):
        for const in ("const NS", "const FG", "function drawFrame"):
            assert html.count(const) == 1, f"{who} has {html.count(const)} of {const}"


@test("every scene draws at every instant")
def _():
    if not shutil.which("node"):
        print("       (node absent — the browser check needs it)", end="")
        return
    frames = {}
    for t in TRACES:
        tr = load(t)
        for name in shapes.SCENES:
            frames[f"{tr.name}/{name}"] = shapes.build(tr, name).fit().to_json()
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "draw.js").write_text(player.DRAW_JS)
        (d / "frames.json").write_text(json.dumps(frames))
        # A stub DOM that refuses undefined attributes: the failure this catches
        # is a shape whose coordinates are NaN, which a browser draws as nothing.
        (d / "check.js").write_text("""
const fs = require("fs");
const mk = (n) => ({ tag:n, attrs:{}, children:[], textContent:"",
  setAttribute(k,v){ if(v===undefined||v===null||Number.isNaN(v))
      throw new Error(`<${n} ${k}="${v}">`); this.attrs[k]=v; },
  appendChild(c){ this.children.push(c); return c; },
  set innerHTML(v){ this.children=[]; } });
global.document = { createElementNS: () => mk("g"), getElementById: () => mk("div") };
eval(fs.readFileSync(process.argv[2] + "/draw.js", "utf8"));
const frames = JSON.parse(fs.readFileSync(process.argv[2] + "/frames.json", "utf8"));
for (const [name, f] of Object.entries(frames))
  for (const t of [0, f.durationMs / 2, f.durationMs, Infinity])
    try { drawFrame(mk("svg"), f, t); }
    catch (e) { console.error(`${name} at ${t}: ${e.message}`); process.exit(1); }
""")
        r = subprocess.run(["node", str(d / "check.js"), str(d)], capture_output=True, text=True)
        assert r.returncode == 0, r.stderr.strip() or r.stdout.strip()


@test("every panel of the studio draws for every lab")
def _():
    if not shutil.which("node"):
        print("       (node absent — the browser check needs it)", end="")
        return
    srv, base = _studio()
    try:
        runs = _get(base, "/api/state")["runs"]
        js = re.search(r"<script>(.*)</script>", studio.page(), re.S).group(1)
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "studio.js").write_text(js)
            # The page talks to the real sidecar over real HTTP. An in-memory
            # stub hid a bug once already: it decoded the URL itself, so a page
            # that encoded an id wrongly still "worked" in the test and 404ed in
            # a browser. Nothing between the two ends is simulated now.
            (d / "check.js").write_text("""
const fs = require("fs"), base = process.argv[3];
const mk = (tag) => ({ tag, children: [], dataset: {}, style: {}, attrs: {},
    textContent: "", value: "1000", onclick: null, oninput: null,
    setAttribute(k, v){ if (v === undefined || Number.isNaN(v))
        throw new Error(`<${tag} ${k}="${v}">`); this.attrs[k] = v; },
    appendChild(c){ this.children.push(c); return c; },
    set innerHTML(v){ const bad = String(v).match(/.{0,50}(undefined|NaN).{0,25}/);
        if (bad) throw new Error(`${tag}: ${bad[0]}`); this._html = v; },
    get innerHTML(){ return this._html || ""; } });
const nodes = {};
global.document = { createElementNS: () => mk("g"), createElement: mk,
                    getElementById: (id) => nodes[id] || (nodes[id] = mk(id)) };
// performance stays node's own: undici's fetch reaches into it, and a stub
// breaks every request the page makes.
global.requestAnimationFrame = () => {};
global.setInterval = () => {};
global.alert = (m) => { throw new Error("alert: " + m); };
const realFetch = global.fetch;
global.fetch = (p, opts) => realFetch(base + p, opts);
(async () => {
  eval(fs.readFileSync(process.argv[2] + "/studio.js", "utf8"));
  await new Promise(r => setTimeout(r, 400));
  const runs = (await (await realFetch(base + "/api/state")).json()).runs;
  if (!runs.length) throw new Error("no runs to open");
  // The ▶ button, clicked the way a person clicks it.
  if (nodes.tasks.children.length) {
    const row = nodes.tasks.children[0];
    const go = row.children[row.children.length - 1];
    if (go.textContent !== "\u25B6") throw new Error("no run button on the first task");
    await go.onclick();
    await new Promise(r => setTimeout(r, 300));
    const jobs = (await (await realFetch(base + "/api/state")).json()).jobs;
    if (!jobs.some(j => j.kind === "run")) throw new Error("clicking run started nothing");
  }
  for (const r of runs) {
    // Only what a person can reach: select the run, then click each scene tab.
    // The page's own variables live inside the eval and are none of our business.
    await select(r.id);
    const header = nodes.what.textContent || "";
    if (!header.includes(r.name))
      throw new Error(`opening ${r.id} left the page on: ${nodes.body.innerHTML || header}`);
    const tabs = nodes.tabs.children;
    if (!tabs.length) throw new Error(`${r.id}: no scene tabs`);
    for (const tab of tabs) {
      tab.onclick();
      if (!nodes.svg.children.length)
        throw new Error(`${r.id}/${tab.textContent}: drew nothing`);
    }
  }
})().catch(e => { console.error(e.message); process.exit(1); });
""")
            r = subprocess.run(["node", str(d / "check.js"), str(d), base],
                               capture_output=True, text=True)
            assert r.returncode == 0, r.stderr.strip() or r.stdout.strip()
            assert runs, "the studio found no runs to draw"
    finally:
        srv.shutdown()


@test("a run can be started from the page, and only a run that exists")
def _():
    srv, base = _studio()
    try:
        state = _get(base, "/api/state")
        assert state["canRun"], "this checkout has a runner script, so the page should offer it"
        names = [t["task"] for t in state["tasks"]]
        assert "hello_ring" in names, names
        # Only names from that list are accepted: the page cannot compose a
        # command of its own, which is what keeps a button from being a shell.
        for bad in ({"task": "../../etc", "scenario": "x"},
                    {"task": "hello_ring", "scenario": "/etc/passwd"},
                    {"task": "hello_ring", "scenario": "../../../main.yaml"}):
            req = urllib.request.Request(base + "/api/run", json.dumps(bad).encode(),
                                         {"Content-Type": "application/json"})
            try:
                urllib.request.urlopen(req)
            except urllib.error.HTTPError as e:
                assert e.code == 400, (bad, e.code)
            else:
                raise AssertionError(f"{bad} was accepted")

        req = urllib.request.Request(base + "/api/run",
                                     json.dumps({"task": "hello_ring",
                                                 "scenario": "ring.yaml"}).encode(),
                                     {"Content-Type": "application/json"})
        job = json.loads(urllib.request.urlopen(req).read())
        for _ in range(180):
            time.sleep(1)
            job = _get(base, "/api/job/" + job["id"])
            if job["state"] != "running":
                break
        assert job["state"] == "done", job
        assert job["runId"], "a finished run must say what it produced"
        assert _get(base, "/api/run/" + job["runId"])["scenes"], "the trace it wrote does not draw"
    finally:
        srv.shutdown()


@test("an id survives the trip through a URL")
def _():
    # encodeURIComponent on a whole id turns its separator into %2F, and the
    # run then cannot be found. Both spellings have to resolve, and neither may
    # become a way out of the watched directory.
    srv, base = _studio()
    try:
        rid = _get(base, "/api/state")["runs"][0]["id"]
        for spelling in (rid, urllib.parse.quote(rid, safe=""),
                         "/".join(urllib.parse.quote(x) for x in rid.split("/"))):
            got = _get(base, "/api/run/" + spelling)
            assert got.get("scenes"), f"{spelling} did not resolve: {got}"
        for escape in ("0/../../etc/passwd", urllib.parse.quote("0/../../etc/passwd", safe="")):
            try:
                urllib.request.urlopen(base + "/api/run/" + escape)
            except urllib.error.HTTPError as e:
                assert e.code in (400, 404), (escape, e.code)
            else:
                raise AssertionError(f"{escape} was served")
    finally:
        srv.shutdown()


@test("the page reaches nowhere but its own sidecar")
def _():
    html = studio.page()
    stripped = html.replace("http://www.w3.org/2000/svg", "")
    for bad in ("http://", "https://", "<script src", "cdn."):
        assert bad not in stripped, f"the studio reaches outside for {bad}"


print(f"\n{passed} passed, {len(failures)} failed")
for f in failures:
    print("  FAIL", f)
raise SystemExit(1 if failures else 0)
