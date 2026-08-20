"""The observatory: one page for watching a system you wrote.

It draws with the very same code the saved player uses, so a scene here, a
scene in view.html and a frame in the video are the same picture. Everything it
knows comes from the sidecar's JSON API — this module is markup, not logic.
"""
from __future__ import annotations

import hashlib

from .player import DRAW_JS


def version() -> str:
    """A stamp for the page as it stands right now.

    An open tab keeps running the JavaScript it was served, so after the
    framework is updated underneath it a student sits looking at the old page
    wondering where the new button is. The page compares this against what the
    sidecar reports and reloads itself when they differ.
    """
    return hashlib.sha1((_PAGE + DRAW_JS).encode()).hexdigest()[:12]


def page() -> str:
    return (_PAGE.replace("__DRAW__", DRAW_JS)
                 .replace("__VERSION__", version()))


_PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>losim studio</title>
<style>
 :root{--bg:#12151C;--panel:#171B24;--fg:#C9D1E0;--dim:#8A93A6;--line:#2A2F3A;
       --accent:#4C9BE8;--ok:#63C77A;--warn:#E8B44C;--bad:#E05252}
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--fg);
      font:14px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
 a{color:var(--accent)}
 header{display:flex;align-items:baseline;gap:14px;flex-wrap:wrap;
        padding:14px 22px;border-bottom:1px solid var(--line)}
 h1{font-size:17px;margin:0;font-weight:700;letter-spacing:.2px}
 .dim{color:var(--dim)}
 .live{font-size:12px;color:var(--ok);display:flex;align-items:center;gap:6px}
 .dot{width:8px;height:8px;border-radius:50%;background:var(--ok);
      animation:pulse 2s infinite}
 @keyframes pulse{0%,100%{opacity:1}50%{opacity:.25}}
 main{display:grid;grid-template-columns:230px 1fr;gap:0;min-height:calc(100vh - 52px)}
 aside{border-right:1px solid var(--line);padding:14px 12px;overflow:auto}
 aside h2,section h2{font-size:11px;text-transform:uppercase;letter-spacing:.9px;
                     color:var(--dim);margin:0 0 8px}
 .run{padding:8px 10px;border-radius:8px;cursor:pointer;border:1px solid transparent}
 .run:hover{background:#1B202A}
 .run[aria-selected=true]{background:#1B202A;border-color:var(--accent)}
 .run b{font-weight:600;display:block}
 .task{display:flex;align-items:center;gap:6px;padding:5px 6px;border-radius:8px}
 .task:hover{background:#1B202A}
 .task .name{flex:1;font-weight:600}
 .task select{background:#12151C;color:var(--dim);border:1px solid var(--line);
              border-radius:6px;font:inherit;font-size:11px;max-width:96px}
 .go{padding:2px 9px;line-height:1.3}
 #runlog{margin:8px 0 0;padding:8px;background:#12151C;border:1px solid var(--line);
         border-radius:8px;max-height:150px;font-size:11px}
 .run span{font-size:12px;color:var(--dim)}
 .stage{padding:14px 22px;overflow:auto}
 nav{display:flex;gap:8px;flex-wrap:wrap}
 .bar{display:flex;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}
 #videobar{display:flex;align-items:center;gap:8px}
 button[aria-pressed=false][data-empty=true]{opacity:.4}
 .note{margin:10px 0 0;padding:12px 14px;border:1px dashed var(--line);
       border-radius:10px;color:var(--dim);font-size:13px}
 button{background:#1B202A;color:var(--fg);border:1px solid var(--line);
        border-radius:8px;padding:6px 13px;cursor:pointer;font:inherit}
 button:hover:not(:disabled){border-color:var(--accent)}
 button:disabled{opacity:.45;cursor:default}
 button[aria-pressed=true]{background:var(--accent);border-color:var(--accent);
        color:#0A0C11;font-weight:600}
 #svg{display:block;width:100%;background:#0E1117;border:1px solid var(--line);
      border-radius:12px}
 .controls{display:flex;align-items:center;gap:12px;margin:10px 0 4px}
 input[type=range]{flex:1;accent-color:var(--accent)}
 .t{font-variant-numeric:tabular-nums;color:var(--dim);min-width:104px}
 .panels{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));
         gap:14px;margin-top:16px}
 section{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px}
 table{width:100%;border-collapse:collapse;font-size:13px}
 th{text-align:left;color:var(--dim);font-weight:500;padding:3px 6px 3px 0;font-size:12px}
 td{padding:3px 6px 3px 0;font-variant-numeric:tabular-nums}
 .story{max-height:260px;overflow:auto;font-size:13px}
 .ev{display:flex;gap:9px;padding:3px 6px;border-radius:6px;cursor:pointer}
 .ev:hover{background:#1B202A}
 .ev .when{color:var(--dim);min-width:62px;text-align:right;font-variant-numeric:tabular-nums}
 .ev .who{color:var(--accent);min-width:56px}
 .k-kill .what,.k-oom .what,.k-nospace .what{color:var(--bad)}
 .k-rpc_timeout .what,.k-degrade .what,.k-spot_notice .what{color:var(--warn)}
 .k-done .what{color:var(--ok)}
 pre{margin:0;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;
     white-space:pre-wrap;color:var(--dim);max-height:220px;overflow:auto}
 video{width:100%;border-radius:12px;border:1px solid var(--line);background:#000;display:block}
 #player[hidden]{display:none}
 .pill{display:inline-block;font-size:11px;padding:1px 8px;border-radius:999px;
       border:1px solid var(--line);color:var(--dim)}
 .pill.ok{color:var(--ok);border-color:#2C4A38}
 .pill.bad{color:var(--bad);border-color:#4A2C2C}
 .empty{color:var(--dim);padding:40px 0;text-align:center}
 .warnrow{color:var(--warn);font-size:12px;margin:6px 0 0}
</style></head><body>
<header>
  <h1>losim studio</h1>
  <span class="dim" id="what">no run selected</span>
  <span style="flex:1"></span>
  <span class="live"><span class="dot"></span><span id="livetext">watching for runs</span></span>
</header>
<main>
  <aside>
    <h2>tasks</h2>
    <div id="tasks"></div>
    <pre id="runlog" hidden></pre>
    <h2 style="margin-top:18px">runs</h2>
    <div id="runs"></div>
  </aside>
  <div class="stage">
    <div id="body"><div class="empty">Nothing has run yet.<br>
      Press ▶ next to a task on the left.</div></div>
  </div>
</main>
<script>
__DRAW__

// ------------------------------------------------------------------ state
let STATE = null, RUN = null, scene = null, t = Infinity;
let playing = false, speed = 1, last = 0, jobId = null, seenMtime = 0;
let runJob = null, openWhenDone = false;
// The first tab is the film of the dataflow — that scene exists to be rendered,
// so it is shown as what it is rather than as a drawing you could scrub.
const VIDEO_TAB = "video", VIDEO_SCENE = "film";
const sceneOf = tab => tab === VIDEO_TAB ? VIDEO_SCENE : tab;
// The film stands in for the topology while it renders, and is judged empty
// when there is nothing in the machines-and-messages picture to film.
const frameOf = tab => tab === VIDEO_TAB ? "topology" : tab;
// A scene is rendered once and then shown from disk; asked for again only if a
// person asks, since a render costs minutes and always gives the same answer.
const asked = new Set();
let preferVideo = true;
const PAGE_VERSION = "__VERSION__";

const $ = id => document.getElementById(id);
const api = (p, body) => fetch(p, body ? {method:"POST", headers:{"Content-Type":"application/json"},
                                          body:JSON.stringify(body)} : undefined)
                          .then(r => r.json());
const fmtMs = ms => ms >= 1000 ? (ms/1000).toFixed(2)+" s" : Math.round(ms)+" ms";
// A ring sends 45 bytes per machine. Printed as kB to one decimal that reads
// "0.0 kB", which says a machine sent nothing at all.
const fmtBytes = b => {
  b = Number(b) || 0;
  if (b < 1024) return b + " B";
  if (b < 1048576) return (b/1024).toFixed(b < 10240 ? 1 : 0) + " kB";
  if (b < 1073741824) return (b/1048576).toFixed(1) + " MB";
  return (b/1073741824).toFixed(2) + " GB";
};
const ago = s => { const d = Date.now()/1000 - s;
  return d < 60 ? Math.max(0,Math.round(d))+"s ago" : Math.round(d/60)+"m ago"; };

// ----------------------------------------------------------------- tasks
// Running is the page's job too: the terminal is a choice, not a requirement.
function drawTasks(){
  const box = $("tasks");
  if (!STATE.canRun){
    box.innerHTML = '<p class="dim" style="font-size:12px">start runs from the terminal — ' +
                    'this page has no runner script beside it</p>';
    return;
  }
  const job = STATE.jobs.find(j => j.id === runJob);
  const busy = job && job.state === "running";
  box.innerHTML = "";
  for (const t of STATE.tasks){
    // Built as elements rather than as markup: the row keeps a handle on its
    // own select and button, so nothing has to be found again afterwards.
    const row = document.createElement("div");
    row.className = "task";
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = t.task;
    row.appendChild(name);

    let pick = null;
    if (t.scenarios.length > 1){
      pick = document.createElement("select");
      for (const sc of t.scenarios){
        const o = document.createElement("option");
        o.textContent = sc;
        o.value = sc;
        if (sc === t.default) o.selected = true;
        pick.appendChild(o);
      }
      pick.value = t.default;
      row.appendChild(pick);
    }

    const go = document.createElement("button");
    go.className = "go";
    go.textContent = "▶";
    go.title = "run " + t.task;
    if (busy) go.disabled = true;
    go.onclick = () => run(t.task, pick ? pick.value : t.default);
    row.appendChild(go);
    box.appendChild(row);
  }
  const log = $("runlog");
  log.hidden = !job;
  if (job){
    log.textContent = job.log.join("\n") + (job.error ? "\n\n" + job.error : "");
    log.scrollTop = log.scrollHeight;
  }
}

async function run(task, scenario){
  const j = await api("/api/run", {task, scenario});
  if (j.error){ alert(j.error); return; }
  runJob = j.id;
  openWhenDone = true;
  drawTasks();
  tick();
}

// ------------------------------------------------------------------ runs
function drawRuns(){
  const box = $("runs"); box.innerHTML = "";
  if (!STATE.runs.length) box.innerHTML = '<p class="dim" style="font-size:12px">none found yet</p>';
  for (const r of STATE.runs){
    const d = document.createElement("div");
    d.className = "run"; d.setAttribute("aria-selected", RUN && RUN.id === r.id);
    const bad = r.checks && r.checks.some(c => !c.ok);
    d.innerHTML = `<b>${r.name}</b><span>${fmtMs(r.endedMs)} · seed ${r.seed}` +
                  (bad ? ' · <span style="color:#E05252">checks failed</span>' : '') +
                  `<br>${ago(r.mtime)}</span>`;
    d.onclick = () => select(r.id);
    box.appendChild(d);
  }
}

// Each segment, not the whole id: encodeURIComponent would turn the separator
// into %2F and the server would be asked for a run whose name contains a slash.
const encodeId = id => id.split("/").map(encodeURIComponent).join("/");

async function select(id){
  const got = await api("/api/run/" + encodeId(id));
  if (!got || got.error || !got.scenes){
    // Leaving RUN unset matters: the next tick tries again rather than sitting
    // on a page that will never fill in.
    $("body").innerHTML = `<div class="empty">could not open that run — ${
        esc((got && got.error) || "no answer from the sidecar")}</div>`;
    return;
  }
  RUN = got;
  seenMtime = RUN.mtime;
  // Land on a scene with something in it: dataflow leads, but a token ring has
  // no dataflow, and opening on an empty picture teaches nothing.
  const order = tabsOf(RUN);
  const worthShowing = order.filter(s => !(RUN.empty || []).includes(frameOf(s)));
  if (!scene || !order.includes(scene) || (RUN.empty || []).includes(frameOf(scene)))
    scene = worthShowing[0] || order[0];
  t = Infinity; playing = false;
  drawRuns(); drawBody();
}

// ------------------------------------------------------------------ page
function drawBody(){
  const m = RUN.meta, mx = RUN.metrics || {};
  $("what").textContent = `${RUN.name} · seed ${m.seed} · ${m.codec} codec · ` +
                          `${fmtMs(m.endedAtMs)} simulated · ${m.slices} slices`;
  $("body").innerHTML = `
    <div class="bar"><nav id="tabs"></nav><span style="flex:1"></span>
      <div id="videobar"></div></div>
    <svg id="svg" viewBox="0 0 1600 900"></svg>
    <video id="player" controls autoplay muted loop playsinline hidden></video>
    <div class="note" id="note"></div>
    <div class="controls" id="controls">
      <button id="play">▶ Play</button>
      <input type="range" id="scrub" min="0" max="1000" value="1000">
      <span class="t" id="clock">0 ms</span>
      <button id="speed">1×</button>
    </div>
    <div class="warnrow" id="warn"></div>
    <div class="panels">
      <section><h2>what happened</h2><div class="story" id="story"></div></section>
      <section><h2>machines</h2><div id="vms"></div></section>
      <section><h2>checks &amp; measurements</h2><div id="checks"></div></section>
      <section><h2>the bill</h2><div id="bill"></div></section>
      <section style="grid-column:1/-1"><h2>video</h2><div id="video"></div></section>
    </div>`;

  const tabs = $("tabs");
  for (const name of tabsOf(RUN)){
    const b = document.createElement("button");
    b.textContent = name; b.dataset.scene = name;
    if ((RUN.empty || []).includes(frameOf(name))){
      b.dataset.empty = "true";
      b.title = "this run has nothing to show here";
    }
    b.onclick = () => { scene = name; t = Infinity; playing = false;
                        sync(); paint(); drawVideo(); maybeRender(); };
    tabs.appendChild(b);
  }
  $("scrub").oninput = e => {
    t = (e.target.value/1000) * RUN.scenes[scene].durationMs;
    playing = false; $("play").textContent = "▶ Play"; paint();
  };
  $("play").onclick = () => {
    const f = RUN.scenes[scene];
    playing = !playing;
    if (playing && (t === Infinity || t >= f.durationMs)) t = 0;
    $("play").textContent = playing ? "❚❚ Pause" : "▶ Play";
    last = performance.now();
    if (playing) requestAnimationFrame(step);
  };
  $("speed").onclick = e => {
    speed = speed === 1 ? 4 : speed === 4 ? 0.25 : 1; e.target.textContent = speed + "×";
  };

  drawStory(); drawVms(); drawChecks(); drawBill(); drawVideo();
  sync(); paint(); maybeRender();
}

function step(now){
  if (!playing) return;
  const f = RUN.scenes[scene];
  const dt = (now - last) * speed; last = now;
  t = Math.min(f.durationMs, (t === Infinity ? 0 : t) + dt * (f.durationMs/6000));
  $("scrub").value = (t/f.durationMs)*1000;
  paint();
  if (t < f.durationMs) requestAnimationFrame(step);
  else { playing = false; $("play").textContent = "▶ Play"; }
}

function sync(){
  for (const b of $("tabs").children) b.setAttribute("aria-pressed", b.dataset.scene === scene);
  const w = (RUN.warnings || {})[scene] || [];
  $("warn").textContent = w.join(" · ");
}

const NOTHING_TO_SHOW = {
  topology: "There is no film of this run: a video shows work moving through stages, " +
            "which is what a MapReduce does and a token ring does not.",
  gantt: "Nobody was busy for long enough to draw — occupancy shows time spent " +
         "working, and this run spent its time waiting on messages.",
  spacetime: "No messages crossed between machines in this run.",
  topology: "This run has no machines talking to each other.",
};

function tabsOf(run){
  // The film covers the topology and the dataflow, so neither is a tab: what
  // is left are the views you read rather than watch.
  return [VIDEO_TAB].concat(Object.keys(run.scenes).filter(n => n !== "dataflow"));
}

function videoUrl(){
  const key = RUN && RUN.videoKeys && RUN.videoKeys[sceneOf(scene)];
  return (key && STATE.videos && STATE.videos[key]) || null;
}

// Rendering happens because you are looking at the scene, not because you asked
// for it. Nothing here waits: the picture is live while the video is made.
function maybeRender(){
  if (!RUN || !STATE || (RUN.empty || []).includes(frameOf(scene))) return;
  if (scene !== VIDEO_TAB) return;        // the other tabs are drawings, on purpose
  const key = RUN.videoKeys && RUN.videoKeys[sceneOf(scene)];
  if (!key || videoUrl() || asked.has(key)) return;
  if (!(STATE.manim.ready || STATE.manim.installable.length)) return;
  if (STATE.jobs.some(j => j.kind === "render" && j.state === "running")) return;
  asked.add(key);
  api("/api/render", {run: RUN.id, scene: sceneOf(scene), quality: "l"}).then(j => {
    if (!j.error) jobId = j.id;
    tick();
  });
}

function paint(){
  const f = RUN.scenes[frameOf(scene)];
  const url = videoUrl();
  const player = $("player"), svgEl = $("svg");
  // The video is the picture when there is one; the scrubbable drawing is what
  // you get while it renders, and whenever you ask for it back.
  const showVideo = url && (preferVideo || scene === VIDEO_TAB);
  player.hidden = !showVideo;
  svgEl.style.display = showVideo ? "none" : "block";
  if (showVideo && player.src !== url && !String(player.src).endsWith(url)) player.src = url;
  drawFrame(svgEl, f, t);
  $("clock").textContent = (t === Infinity ? Math.round(f.durationMs) : Math.round(t)) + " ms";
  const controls = $("controls");
  if (controls) controls.style.display = showVideo ? "none" : "flex";
  const blank = (RUN.empty || []).includes(frameOf(scene));
  const rendering = !blank && scene === VIDEO_TAB && !url;
  const note = $("note");
  note.hidden = !(blank || rendering);
  if (blank){
    const others = tabsOf(RUN).filter(s => !(RUN.empty || []).includes(frameOf(s)));
    note.textContent = (NOTHING_TO_SHOW[frameOf(scene)] || "Nothing to draw here.") +
        (others.length ? "  Try " + others.join(" or ") + "." : "");
  } else if (rendering){
    note.textContent = STATE && STATE.manim && !STATE.manim.ready
      ? "Setting the renderer up — this happens once and takes a few minutes. " +
        "The drawing below is live in the meantime."
      : "Rendering this run as a film. The drawing below is live in the meantime.";
  }
}

// ------------------------------------------------------------- the panels
const SAYS = {
  boot: d => `booted on ${d.instance} in ${d.zone}${d.market === "spot" ? " (spot)" : ""}`,
  send: d => `sent ${d.type || "a message"} to ${d.to} — ${fmtBytes(d.bytes)}` +
             (d.locality === "CROSS_ZONE" ? ", across zones" : ""),
  rpc_call: d => `called ${String(d.method || "").split(".").pop()} on ${d.to} — ` +
                 `${fmtBytes(d.bytes)}${d.locality === "CROSS_ZONE" ? ", across zones" : ""}`,
  drop: d => `its message to ${d.to} was lost`,
  log: d => d.message,
  done: d => "finished: " + JSON.stringify(d.value).slice(0, 120),
  kill: d => "killed — " + (d.reason || "gone"),
  restart: () => "restarted, memory empty",
  freeze: d => `frozen for ${d.forMs} ms — indistinguishable from dead`,
  degrade: d => `cpu × ${d.cpu} — this is your straggler`,
  spot_notice: d => `spot reclaim in ${d.noticeMs} ms`,
  spot_reclaim: () => "reclaimed",
  rpc_timeout: d => `${d.method} to ${d.to} timed out`,
  rpc_dropped: d => `message to ${d.to} lost`,
  oom: d => `out of memory (${d.wantedMb} MB over)`,
  nospace: d => "out of disk",
};

function drawStory(){
  const box = $("story");
  box.innerHTML = RUN.story.length ? "" : '<p class="dim">a quiet run</p>';
  for (const e of RUN.story){
    const d = document.createElement("div");
    d.className = "ev k-" + e.kind;
    const say = (SAYS[e.kind] || (x => JSON.stringify(x)))(e.detail || {});
    d.innerHTML = `<span class="when">${e.t} ms</span><span class="who">${e.vm}</span>` +
                  `<span class="what">${esc(say)}</span>`;
    // Clicking an event moves the picture to the moment it happened.
    d.onclick = () => { t = e.t; playing = false;
      $("scrub").value = (t / RUN.scenes[scene].durationMs) * 1000; paint(); };
    box.appendChild(d);
  }
}

function drawVms(){
  const rows = RUN.vms.map(v => `<tr>
      <td>${v.state === "DEAD" ? "✝ " : ""}${v.name}</td><td class="dim">${v.instance}</td>
      <td class="dim">${v.zone.replace(/^.*-/, "…")}</td>
      <td>${(v.busyMs||0)} ms</td><td>${fmtBytes(v.bytesOut)}</td>
      <td>${v.crossZoneBytes ? fmtBytes(v.crossZoneBytes) : "—"}</td>
      <td>${v.memPeak ? fmtBytes(v.memPeak) : "—"}</td></tr>`).join("");
  $("vms").innerHTML = `<table><tr><th>vm</th><th>instance</th><th>zone</th><th>busy</th>
      <th>sent</th><th>cross-zone</th><th>peak mem</th></tr>${rows}</table>`;
}

function drawChecks(){
  const c = RUN.meta.checks || [];
  const checks = c.length
    ? c.map(x => `<div><span class="pill ${x.ok ? "ok" : "bad"}">${x.ok ? "holds" : "violated"}</span>
                  ${esc(x.name)}${x.why ? ' <span class="dim">— ' + esc(x.why) + "</span>" : ""}</div>`).join("")
    : '<p class="dim">this scenario declares no invariants</p>';
  const m = RUN.metrics || {};
  const cells = ["messages","bytes","crossZoneBytes","rpcCalls","rpcTimeouts","rpcDropped",
                 "duplicateWork","kills"]
      .filter(k => k in m)
      .map(k => `<tr><td class="dim">${k}</td><td>${m[k]}</td></tr>`).join("");
  $("checks").innerHTML = checks + `<table style="margin-top:10px">${cells}</table>`;
}

function drawBill(){
  const p = RUN.bill.pnl || {};
  if (!p.lines) { $("bill").innerHTML = '<p class="dim">no bill in this trace</p>'; return; }
  const cur = p.currency || "CHF";
  let html = "";
  for (const b of RUN.bill.buckets){
    const lines = p.lines.filter(l => l.bucket === b);
    if (!lines.length) continue;
    html += `<div style="margin-bottom:8px"><b>${b}</b> <span class="dim">${cur} ${
      (p.buckets[b]||0).toFixed(4)}</span><table>` +
      lines.map(l => `<tr><td class="dim">${esc(l.what)}</td>
        <td>${Number(l.quantity).toPrecision(4)} ${esc(l.unit)}</td>
        <td>× ${Number(l.unitPrice).toFixed(4)}</td>
        <td>= ${Number(l.amount).toFixed(4)}</td></tr>`).join("") + "</table></div>";
  }
  // Money is the aggregator, never the replacement — every line keeps its quantity.
  html += `<div style="border-top:1px solid var(--line);padding-top:8px">
      <b>total cost</b> ${cur} ${(p.cost||0).toFixed(4)} ·
      <span class="dim">profit ${cur} ${(p.profit||0).toFixed(4)}</span></div>`;
  $("bill").innerHTML = html;
}

// -------------------------------------------------------------- the video
function drawVideo(){
  const m = STATE.manim, job = STATE.jobs.find(j => j.id === jobId);
  const busy = job && job.state === "running";
  const blank = RUN && (RUN.empty || []).includes(scene);

  // The button sits with the picture it renders, not in a panel below the fold.
  const bar = $("videobar");
  const canRender = m.ready || m.installable.length;
  const url = RUN ? videoUrl() : null;
  const onVideoTab = scene === VIDEO_TAB;
  bar.innerHTML = !canRender
    ? `<span class="pill bad">no video here</span>`
    : (url
        ? `${onVideoTab ? "" : `<button id="toggle">${
             preferVideo ? "⇆ scrub it yourself" : "⇆ back to the video"}</button>`}
           <select id="q" title="quality"><option value="l">480p</option>
             <option value="m">720p</option><option value="h">1080p</option></select>
           <button id="render" ${busy ? "disabled" : ""}>${busy ? "rendering…" : "↻ re-render"}</button>`
        : `<span class="dim" style="font-size:12px">${
             busy ? (m.ready ? "rendering this scene…" : "setting the renderer up, once…")
                  : (blank ? "" : "video on the way")}</span>
           <select id="q" title="quality" hidden><option value="l">480p</option></select>`);

  const box = $("video");
  const head = m.ready
    ? `<span class="pill ok">ready</span> <span class="dim">${esc(m.detail)}</span>`
    : (m.installable.length
        ? `<span class="pill">setting itself up</span> <span class="dim">the renderer
             installs on first use — a few minutes, once</span>`
        : `<span class="pill bad">unavailable</span> <span class="dim">this machine has
             neither python3-venv nor docker</span>`);
  box.innerHTML = head +
    (job ? `<pre id="joblog">${esc(job.log.join("\n"))}${
        job.error ? "\n\n" + esc(job.error) : ""}</pre>` : "") +
    (job && job.video ? `<video controls autoplay muted loop src="${job.video}"></video>
        <p class="dim" style="font-size:12px">${esc(job.what)} · <a href="${job.video}"
        download>download the mp4</a></p>` : "");

  const tg = $("toggle");
  if (tg) tg.onclick = () => { preferVideo = !preferVideo; drawVideo(); paint(); };
  const r = $("render");
  if (r) r.onclick = async () => {
    const j = await api("/api/render", {run: RUN.id, scene, quality: $("q").value});
    if (j.error) { alert(j.error); return; }
    jobId = j.id;
    if ($("video").scrollIntoView) $("video").scrollIntoView({behavior: "smooth", block: "center"});
    tick();
  };
}

// ------------------------------------------------------------------- poll
async function tick(){
  const before = STATE ? JSON.stringify(STATE.manim) + JSON.stringify(STATE.jobs) +
                         JSON.stringify(STATE.videos) : "";
  STATE = await api("/api/state");
  if (STATE.pageVersion && STATE.pageVersion !== PAGE_VERSION){
    // The framework was updated under this tab. Reloading is the whole fix,
    // and doing it here means nobody has to know that.
    if (typeof location !== "undefined" && location.reload) return location.reload();
  }
  drawRuns();
  drawTasks();
  const job = STATE.jobs.find(j => j.id === runJob);
  if (openWhenDone && job && job.state === "done" && job.runId){
    // Show what was just produced, without waiting for the mtime poll.
    openWhenDone = false;
    seenMtime = 0;
    await select(job.runId);
    return;
  }
  if (openWhenDone && job && job.state === "failed") openWhenDone = false;
  if (!RUN && STATE.runs.length) return select(STATE.runs[0].id);
  if (!RUN) return;
  // A trace that changed on disk means the student just ran the lab again.
  const mine = STATE.runs.find(r => r.id === RUN.id);
  if (mine && mine.mtime !== seenMtime){
    $("livetext").textContent = "new run — reloading";
    await select(RUN.id);
    $("livetext").textContent = "watching for runs";
    return;
  }
  if (before !== JSON.stringify(STATE.manim) + JSON.stringify(STATE.jobs) +
                 JSON.stringify(STATE.videos)){
    drawVideo();
    if (RUN) paint();                    // a finished render becomes the picture
  }
}

tick();
setInterval(tick, 1500);
</script></body></html>"""
