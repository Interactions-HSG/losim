"""A self-contained HTML player: the fast way to watch a run.

Same Frame as the video exporter, so the picture is identical. State is drawn
as one badge that keeps being rewritten rather than a pile of readings — the
number visibly moves, which is the whole reason to draw state at all.
"""
from __future__ import annotations

import json

from .shapes import Frame


def render(frames: dict[str, Frame], title: str, meta: dict) -> str:
    payload = {
        "title": title,
        "meta": meta,
        "scenes": {name: f.to_json() for name, f in frames.items()},
    }
    data = json.dumps(payload, separators=(",", ":"))
    return _TEMPLATE.replace("__DATA__", data)


_TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>losim</title>
<style>
 :root{--bg:#12151C;--fg:#C9D1E0;--dim:#8A93A6;--line:#2A2F3A;--accent:#4C9BE8}
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--fg);
      font:14px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
 header{padding:16px 24px;border-bottom:1px solid var(--line);display:flex;
        align-items:baseline;gap:16px;flex-wrap:wrap}
 h1{font-size:18px;margin:0;font-weight:700}
 .sub{color:var(--dim);font-size:13px}
 nav{display:flex;gap:8px;padding:12px 24px;flex-wrap:wrap}
 button{background:#1B202A;color:var(--fg);border:1px solid var(--line);
        border-radius:8px;padding:6px 14px;cursor:pointer;font:inherit}
 button:hover{border-color:var(--accent)}
 button[aria-pressed=true]{background:var(--accent);border-color:var(--accent);color:#0A0C11;font-weight:600}
 #stage{margin:0 24px;border:1px solid var(--line);border-radius:12px;overflow:hidden;background:#0E1117}
 .controls{display:flex;align-items:center;gap:14px;padding:14px 24px}
 input[type=range]{flex:1;accent-color:var(--accent)}
 .t{font-variant-numeric:tabular-nums;color:var(--dim);min-width:110px}
 .warn{color:#E8B44C;padding:0 24px 12px;font-size:13px}
 footer{padding:12px 24px 28px;color:var(--dim);font-size:12px}
</style></head><body>
<header>
  <h1 id="title"></h1>
  <span class="sub" id="subtitle"></span>
</header>
<nav id="tabs"></nav>
<div id="stage"><svg id="svg" viewBox="0 0 1600 900"></svg></div>
<div class="controls">
  <button id="play">▶ Play</button>
  <input type="range" id="scrub" min="0" max="1000" value="1000">
  <span class="t" id="clock">0 ms</span>
  <button id="speed">1×</button>
</div>
<div class="warn" id="warn"></div>
<footer id="foot"></footer>
<script>
const DATA = __DATA__;
const NS = "http://www.w3.org/2000/svg";
const FG = "#C9D1E0";
let scene = Object.keys(DATA.scenes)[0];
let t = Infinity, playing = false, speed = 1, last = 0;

document.getElementById("title").textContent = DATA.title;

const tabs = document.getElementById("tabs");
for (const name of Object.keys(DATA.scenes)) {
  const b = document.createElement("button");
  b.textContent = name;
  b.onclick = () => { scene = name; t = Infinity; playing = false; sync(); draw(); };
  b.dataset.scene = name;
  tabs.appendChild(b);
}

function esc(s){ return String(s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c])); }
function el(name, attrs, text){
  const n = document.createElementNS(NS, name);
  for (const k in attrs) n.setAttribute(k, attrs[k]);
  if (text !== undefined) n.textContent = text;
  return n;
}

function draw(){
  const f = DATA.scenes[scene];
  const svg = document.getElementById("svg");
  svg.innerHTML = "";
  const defs = el("defs");
  const m = el("marker", {id:"a", viewBox:"0 0 10 10", refX:9, refY:5,
                          markerWidth:6, markerHeight:6, orient:"auto"});
  m.appendChild(el("path", {d:"M 0 0 L 10 5 L 0 10 z", fill:FG}));
  defs.appendChild(m); svg.appendChild(defs);
  svg.appendChild(el("rect", {width:"100%", height:"100%", fill:"#0E1117"}));
  svg.appendChild(el("text", {x:30, y:42, fill:FG, "font-size":22, "font-weight":700}, f.title));
  svg.appendChild(el("text", {x:30, y:66, fill:"#8A93A6", "font-size":14}, f.subtitle));

  // State is a badge that gets rewritten, not a pile of readings: only the
  // newest value for a given (vm, key) is on screen, so the number visibly moves.
  const newestState = new Map();
  for (const s of f.shapes){
    if (s.kind !== "state" || s.t_in > t) continue;
    const key = (s.meta.vm || "") + "|" + (s.meta.key || "");
    const prev = newestState.get(key);
    if (!prev || s.t_in >= prev.t_in) newestState.set(key, s);
  }
  const visibleStates = new Set(newestState.values());

  for (const s of f.shapes){
    if (s.t_in > t) continue;
    if (s.kind === "state" && !visibleStates.has(s)) continue;
    // messages fade after they land, so the picture does not silt up
    let op = 1;
    if (s.kind === "arrow" && s.t_in > 0){
      const age = t - s.t_in;
      const life = Math.max(40, f.durationMs * 0.06);
      op = age > life ? 0.18 : 1;
    }
    svg.appendChild(shape(s, op));
  }
  document.getElementById("clock").textContent =
     (t === Infinity ? Math.round(f.durationMs) : Math.round(t)) + " ms";
}

function shape(s, op){
  const g = el("g", {opacity: op});
  const dash = s.style === "control" ? {"stroke-dasharray":"6 5"} : {};
  if (s.kind === "arrow"){
    g.appendChild(el("line", Object.assign({x1:s.x, y1:s.y, x2:s.x2, y2:s.y2,
        stroke:s.color, "stroke-width":2, "marker-end":"url(#a)"}, dash)));
    if (s.text) g.appendChild(el("text", {x:(s.x+s.x2)/2, y:(s.y+s.y2)/2-6, fill:FG,
        "font-size":12, "text-anchor":"middle"}, s.text));
  } else if (s.kind === "ellipse"){
    const dead = s.meta && s.meta.diedAt >= 0 && t >= s.meta.diedAt;
    const col = dead ? "#E05252" : s.color;
    g.appendChild(el("ellipse", {cx:s.x, cy:s.y, rx:s.w/2, ry:s.h/2,
        fill: col+"22", stroke: col, "stroke-width":2}));
    g.appendChild(el("text", {x:s.x, y:s.y+5, fill:FG, "font-size":15,
        "text-anchor":"middle"}, s.text + (dead ? " ✝" : "")));
    if (s.meta && s.meta.instance)
      g.appendChild(el("text", {x:s.x, y:s.y+22, fill:"#8A93A6", "font-size":11,
          "text-anchor":"middle"}, s.meta.instance));
  } else if (s.kind === "lane"){
    g.appendChild(el("line", {x1:s.x-s.w/2, y1:s.y, x2:s.x+s.w/2, y2:s.y,
        stroke:"#2A2F3A", "stroke-width":Math.max(1,s.h)}));
    g.appendChild(el("text", {x:s.x-s.w/2-8, y:s.y+5, fill:FG, "font-size":14,
        "text-anchor":"end"}, s.text));
  } else if (s.kind === "label"){
    g.appendChild(el("text", {x:s.x, y:s.y, fill:FG, "font-size":16,
        "font-weight":600, "text-anchor":"middle"}, s.text));
  } else {
    const rx = s.kind === "box" ? 6 : 12;
    g.appendChild(el("rect", {x:s.x-s.w/2, y:s.y-s.h/2, width:Math.max(2,s.w),
        height:Math.max(2,s.h), rx:rx, fill:s.color+"22", stroke:s.color, "stroke-width":1.5}));
    if (s.text) g.appendChild(el("text", {x:s.x, y:s.y+4, fill:FG, "font-size":12,
        "text-anchor":"middle"}, s.text));
  }
  if (s.meta) { const tip = el("title"); tip.textContent = JSON.stringify(s.meta); g.appendChild(tip); }
  return g;
}

function sync(){
  for (const b of tabs.children) b.setAttribute("aria-pressed", b.dataset.scene === scene);
  const f = DATA.scenes[scene];
  document.getElementById("subtitle").textContent =
      Math.round(f.durationMs) + " ms simulated · " + f.shapes.length + " shapes";
  document.getElementById("foot").textContent = JSON.stringify(DATA.meta);
}

document.getElementById("scrub").oninput = e => {
  const f = DATA.scenes[scene];
  t = (e.target.value / 1000) * f.durationMs;
  playing = false;
  document.getElementById("play").textContent = "▶ Play";
  draw();
};
document.getElementById("play").onclick = () => {
  const f = DATA.scenes[scene];
  playing = !playing;
  if (playing && (t === Infinity || t >= f.durationMs)) t = 0;
  document.getElementById("play").textContent = playing ? "❚❚ Pause" : "▶ Play";
  last = performance.now();
  if (playing) requestAnimationFrame(step);
};
document.getElementById("speed").onclick = e => {
  speed = speed === 1 ? 4 : speed === 4 ? 0.25 : 1;
  e.target.textContent = speed + "×";
};
function step(now){
  if (!playing) return;
  const f = DATA.scenes[scene];
  const dt = (now - last) * speed;
  last = now;
  t = Math.min(f.durationMs, (t === Infinity ? 0 : t) + dt * (f.durationMs / 6000));
  document.getElementById("scrub").value = (t / f.durationMs) * 1000;
  draw();
  if (t < f.durationMs) requestAnimationFrame(step); else {
    playing = false; document.getElementById("play").textContent = "▶ Play";
  }
}
sync(); draw();
</script></body></html>"""
