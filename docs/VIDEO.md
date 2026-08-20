# Video, and the studio that renders it

Two things share one machine here: **the studio**, a page for watching a run,
and **the sidecar**, the process that turns a scene into an mp4.

```
losim (Java) ──trace.json──▶ shapes ──Frame──┬──▶ the studio      (a page, stdlib only)
                                             ├──▶ view.html       (a saved file)
                                             └──▶ the sidecar ──▶ scene.mp4   (manim)
```

The `Frame` is the whole contract. It is positioned shapes with times and
colours, and it serialises to JSON — which is why the browser, the saved player
and the video are the same picture rather than three drawings of one run.

## The studio

```bash
./serve.sh                     # watches build/, opens on :8000
./view.sh serve labs build     # watch more than one directory
```

It shows, for whichever run you pick:

| | |
|---|---|
| **the scenes** | space-time, topology, occupancy, dataflow — playable and scrubbable |
| **what happened** | boots, messages lost, machines killed, timeouts, the result — click one to jump the picture to that moment |
| **the machines** | instance type, zone, busy time, bytes sent, cross-zone bytes, peak memory |
| **checks** | every invariant the scenario declared, and whether it held |
| **the bill** | the five buckets, each line still carrying the quantity it came from |
| **video** | render the scene you are looking at, then play it in the page |

It **observes**; it does not run anything. Run a lab from the terminal as usual
and the page picks up the new trace within a couple of seconds. Nothing on the
page can start, stop or change a simulation.

## The sidecar

manim needs a few hundred megabytes and its own toolchain, and a machine that
runs labs should not have to carry it. So manim is never imported into the
process that decides what to draw — it runs beside it, and the only thing that
crosses the boundary is one `Frame` as JSON.

```bash
./view.sh doctor                     # where would a video render, and how to fix it
./view.sh doctor --install           # install a sidecar
./view.sh render build/wordcount.json --scene spacetime --quality h
```

Three sidecars, used in this order:

| | |
|---|---|
| **in-process** | manim is importable in the interpreter already — nothing to isolate |
| **venv** | `build/.manim-venv`, made by `pip install manim`. Since manim 0.19 the video is written through PyAV, so there is no ffmpeg to install |
| **docker** | the official `manimcommunity/manim` image, for machines where building a wheel is the harder problem |

The container sees exactly two directories: the framework, read-only, and the
directory the video is being written to. On Linux it runs as the calling user,
so it cannot leave root-owned files in a student's checkout.

## Quality

`l` is 480p and takes seconds; `h` is 1080p and takes minutes. Lecture videos
want `h`; everything else wants `l`.

## What the scenes are for

- **space-time** — the Lamport diagram. Arrows slant from when a message was
  sent to when it arrived, so latency has a shape. Long quiet stretches are
  folded up and *labelled with what was skipped* rather than silently stretched.
- **topology** — machines in a circle, messages carrying their real payload and
  their real size.
- **occupancy** — one row per machine, on the true clock. This is where a
  straggler is obvious, so this scene is never folded.
- **dataflow** — phase lanes, left to right: the MapReduce execution overview,
  animated.
