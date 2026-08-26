# losim — a real gRPC system, in a simulated world

A student writes a real gRPC system and runs it, for real, on one machine. losim
is a simulation engine on top of that running system: it slows it, fails it, and
scales it — shrinking the workload and the machines by the same factor so
everything fits on a laptop while behaving as though it were far bigger — then
extrapolates full-scale numbers from what it actually observed.

Two layers, and keeping them apart is the whole design:

- **underneath** — the student's own gRPC system, genuinely running on genuinely
  small data. Real stubs, real transport, real marshalling, real allocation.
- **on top** — losim: interceptors that slow and break things, a machine model
  that caps resources, and a scaler engine that decides how to shrink the world
  and how to project the results back.

That is why "runs out of memory" is not an accounting fiction. Machines are made
proportionally small, so a design that would exhaust a 16 GiB reducer at full
scale exhausts a 16 MiB one here — for the same reason, in the student's own code.

## Try it

```bash
./build.sh          # the simulator -> build/losim.jar
./check.sh          # losim's own checks: every phase's acceptance criteria
```

Nothing is downloaded and nothing is generated at build time. The toolchain is
vendored, so the same commands produce the same result on a laptop, in the
devcontainer and in a Codespace — otherwise a number would depend on where it was
computed, which is the one thing a simulator cannot afford.

## What a service looks like

An ordinary gRPC service, from an ordinary `.proto`, with one losim annotation.
Twelve lines of adapter turn grpc-java's `void map(Chunk, StreamObserver<Counts>)`
into a value-returning method, which is the difference between a handler you can
call from a plain unit test and one you cannot.

```java
public final class Mapper extends WorkerBase {
    @Cost(refMs = 2)
    @Override protected Counts map(Chunk request) {
        var counts = count(request.getText());
        Losim.current().reveal("emitted", counts.size());   // silent in a bare test
        return Counts.newBuilder().putAllCounts(counts).build();
    }
}
```

No losim type appears in any signature. `@Cost` is reference-machine time, so it
composes with scaling: the interceptor sleeps `refMs × machineFactor ÷ k_time`.

**gRPC is the only way machines talk.** There is no second messaging path. Even
fire-and-forget is an `Empty`-returning method on an async stub, so costs, faults,
telemetry and byte counts apply to it exactly as to anything else.

## What it does today

Phase 1 is in: the fleet. One in-process server per machine, one executor per
machine sized to its vCPU count, and losim wrapped around every call as gRPC's own
interceptors.

| | |
|---|---|
| **Real concurrency** | each machine's pool is its vCPU model, so four calls into a two-vCPU machine really do queue |
| **A compressed clock** | every declared duration divided by `k_time`, calibrated per host, with sub-floor costs owed rather than lost |
| **A network** | latency by zone, jitter, loss, partitions — and a dead machine, a cut link and a lost packet are one event from the caller |
| **Three-channel telemetry** | events, spans that carry a parent across the RPC boundary, and dense series — with every call's real argument and real result |
| **Memory, measured twice** | allocation per machine, exactly; and a retained-heap walk, because only one of those decides an out-of-memory |
| **losim's own cost, excluded** | everything losim does on a machine's threads is metered and subtracted, so what is reported is the program's |

That last row is the one that is easy to get wrong and impossible to notice. A
thousand `reveal` calls per handler move the *unsubtracted* memory exponent by
0.026 — well outside its own noise — and the reported one by 0.0004, which is
inside it. The law a student's code is projected by does not depend on how much
they instrumented it.

## Layout

```
losim/src/losim/api/       what a handler may say to losim — and all it can reach
losim/src/losim/runtime/   the fleet, the machines, the two interceptors
losim/src/losim/trace/     the three-channel recorder and the trace it writes
losim/src/losim/time/      the compressed clock, and fault placement
losim/src/losim/res/       instance types, the heap walk, losim's own meter
losim/src/losim/scale/     fitting laws, and refusing to
losim/test/                every phase's acceptance criteria, run by ./check.sh
vendor/                    grpc 1.83.1, protobuf 4.36.0, protoc for two platforms
```

Lab code compiles against `build/losim.jar` and the vendored jars alone, never
against these sources.

## Documentation

- [docs/SPIKES.md](docs/SPIKES.md) — what Phase 0 measured, and where each result now lives
