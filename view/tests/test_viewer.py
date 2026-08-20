"""Tests for the viewer. It must draw any lab's trace without knowing the lab."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "view"))

from losim_view import load, shapes                      # noqa: E402
from losim_view import bill as bill_mod                  # noqa: E402
from losim_view import player, svg                       # noqa: E402
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


print(f"\n{passed} passed, {len(failures)} failed")
for f in failures:
    print("  FAIL", f)
raise SystemExit(1 if failures else 0)
