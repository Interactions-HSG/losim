# losim — a framework for simulating decentralized systems

Students write ordinary Java. Each machine is a **VM**: a virtual thread under a
discrete-event kernel with a virtual clock. The whole fleet runs in one process,
deterministically, so the same seed gives the same run byte for byte — and a
breakpoint cannot cause a timeout, because virtual time only advances when the
kernel advances it.

```
Java: kernel · VMs · network · faults · costs   ──trace.json──▶   Python: shapes → player · video · bill
```

## Try it

```bash
./build.sh          # the framework -> build/losim.jar
./run-all.sh        # build every lab, run it, draw it
./serve.sh          # the studio: watch your runs on :8000
./test.sh           # the framework's own tests
```

## Watching a system run

`./serve.sh` opens **the studio**: every run it can find, every scene playable,
the story of what happened, the machines, the checks and the bill — and a button
that renders the scene to video. Run a lab in another terminal and the page
notices; it is watching the traces, not driving them.

Video is rendered by a **sidecar**, so manim is never a dependency of the
framework. See [docs/VIDEO.md](docs/VIDEO.md).

## What a lab looks like

A program is a plain Java class. No threads, no sockets, no serialization, no
`main` unless it takes initiative.

```java
public final class Mapper implements Program, MapperService {
    @Override @Cost(ms = 2)
    public Pairs map(Ctx ctx, Chunk request) {
        var out = new ArrayList<Pair>();
        for (String w : request.text().split("\\s+")) out.add(new Pair(w, 1));
        return out.isEmpty() ? new Pairs(List.of()) : new Pairs(out);
    }
}
```

The fleet, the failures and the money are a YAML scenario — the instructor's
dial-turning surface, kept declarative on purpose:

```yaml
vms:
  workers:
    prefix: w
    programs: [Mapper, Reducer]     # a colocated Reducer IS the combiner
    instance: m5.large
    count: 6
    market: spot
    availability_zone: [eu-central-1a, eu-central-1b]
    overrides:
      w3: {instance: t3.micro}      # the deliberate straggler

faults:
  - {at: 120ms, kill: w0}           # just gone; the master must notice
  - {at: 200ms, spot_reclaim: w1, notice: 80ms}
```

## What the framework gives every lab, unasked

| | |
|---|---|
| **Determinism** | same seed, same trace — verified across 100 runs and across processes |
| **A debugger that works** | ordinary Java breakpoints; virtual time freezes while you are stopped |
| **Failure** | kill, freeze, spot reclaim, degrade, partition, loss — and no way to ask whether a VM is alive |
| **Real resources** | AWS-shaped instance types; memory, disk and speed derived from what you provisioned |
| **Huge workloads** | a terabyte is *described*, not materialised — and still OOMs a machine too small for it |
| **A bill** | five buckets, every line carrying the quantity it came from |
| **A picture** | a browser player and a manim video, from the same shapes |

## Documentation

- [docs/WRITING-A-LAB.md](docs/WRITING-A-LAB.md) — the student-facing API, end to end
- [docs/SCENARIOS.md](docs/SCENARIOS.md) — every scenario key
- [docs/DESIGN.md](docs/DESIGN.md) — why it is built this way

## Layout

```
losim/src/losim/api/     what students import — and all they can reach
losim/src/losim/kernel/  the discrete-event loop and the handoff
losim/src/losim/runtime/ VMs, RPC, faults
losim/src/losim/verify/  rejects code that would not be reproducible
view/losim_view/         trace -> shapes -> player | video | bill
labs/                    the reference labs
```

Labs compile against `build/losim.jar` alone, never against these sources.
