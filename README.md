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

java -cp build/losim.jar losim.cli.Main run losim/test/scenarios/wordcount.yaml \
     --cp build/test-classes --out build/wordcount.json
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

## What a scenario looks like

The fleet, its weather and its bad afternoon are data. Nothing here is computed;
anything that needs code points at a class, so two designs can be compared by
comparing two of these.

```yaml
seed: 7
kTime: 2
job: WordCountJob
expectedRun: 2 refSeconds

machines:
  master: { instance: m5.large, zone: eu-central-1a }
  workers:
    count: 6
    prefix: w
    instance: m5.large
    zone: [eu-central-1a, eu-central-1b]
    serves: [Counter]
    overrides:
      w2: { memoryMb: 4 }      # cannot hold its bucket, and will say so

faults:
  - { at: 400 refMs, kill: w5 }
```

Every duration is reference-machine time and has to say so. A bare `900` is
refused, and so is `900ms`: those are ambiguous between the simulated world and
your afternoon, and the two differ by `k_time`, which whoever writes the scenario
never sees.

Everything else that can be wrong is refused the same way, with the line it was
written on — an unknown instance type, a fault aimed at a machine that is not in
the fleet, a key that is a typo for a real one. **Including a retry policy the
schema does not support:**

```
wordcount.yaml:14: retrying losim.t.Volley.Hit is refused — its .proto declares no
idempotency_level, so running it twice is not known to be safe. Declare 'option
idempotency_level = IDEMPOTENT;' on the rpc if it is, or write 'unsafe: true' here
if you mean to retry it anyway.
```

## Scaled mode

The scenario above runs what it declares. This one declares a size no laptop can
hold, and losim shrinks the workload and the machines by the same factor:

```yaml
mode: scaled
workload:
  records: 40000000
  probe: [1000, 2000, 4000, 8000]
```

```
wordcount-scaled.yaml  seed 5  scaled 8,000 -> 40,000,000 records (x5,000), k_time 40

  memoryMb   = 0.0597 + 0.00187 * revealed.distinctKeys^1.013  (R2 1.0000, wobble 0.000)
  wireMb     = 0.0    + 0.000151 * records^0.952               (R2 1.0000, wobble 0.004)
  diskMb       REFUSED: the ladder bends — over the lower half it grows as records^0.59
               and over the upper half as records^0.87 ...
  makespan     REFUSED: its exponent moves by 0.66 between independent seed sets of the
               same workload, which over a factor of 5000 is an error bar of x268 ...

                     observed              projected
  memoryMb              6.823                34.07 k  +-x1.10
  wireMb                0.460                 2.63 k  +-x1.04
```

Three things there are the whole point.

**Memory was attributed to distinct keys, not to records.** Peak reducer memory is
not really a function of how many records there were; it is a function of
vocabulary, which is itself a sublinear function of records. Fitting it against
the right variable gives R² 1.0000 and a projection within 3% of ground truth.
Fitting it against records gives an exponent that will not survive a change of
corpus — and a projection 34% out, which is what multiplying the small run by the
size ratio gets you.

**Two resources were refused rather than guessed at.** A projection carries its
confidence or it is absent; a field filled with a plausible number is
indistinguishable from a good one until the cluster bill arrives. R² cannot make
that call — a bent ladder still scores 0.90, which a merely noisy linear workload
reaches just as easily — so the engine splits the ladder and compares the halves,
and separately refits each law on independent seed sets to see whether its
exponent would land in the same place again.

**The timeline is replayed, not multiplied.** Four calls into eight cores take one
wave; thirty-two take four. Multiplying the first run by eight says eight waves.
Replaying the observed call graph with projected durations and each machine's real
concurrency lands within 2% where multiplication is 199% out.

The plan travels in the trace, so `projected = f(observed)` is recomputable by
whoever reads it, and is cached against the scenario and the code it profiles — a
grid of ~30 small runs, paid for once.

## What it does today

Phases 1, 2 and 3 are in: the fleet, direct mode, and the scaler engine. One
in-process server per machine, one executor per machine sized to its vCPU count,
losim wrapped around every call as gRPC's own interceptors, a scenario driving all
of it, and an engine that decides how to shrink the world and how to project the
results back.

| | |
|---|---|
| **Real concurrency** | each machine's pool is its vCPU model, so four calls into a two-vCPU machine really do queue |
| **A compressed clock** | every declared duration divided by `k_time`, calibrated per host, with sub-floor costs owed rather than lost |
| **A network** | latency by zone, jitter, loss, partitions — and a dead machine, a cut link and a lost packet are one event from the caller |
| **Three-channel telemetry** | events, spans that carry a parent across the RPC boundary, and dense series — with every call's real argument and real result |
| **Memory, measured twice** | allocation per machine, exactly; and a retained-heap walk, because only one of those decides an out-of-memory |
| **A bad afternoon** | kill, freeze, degrade, spot reclaim with notice, partition, restart — at an instant, or as a standing rate whose draws come from the seed |
| **Retries you have to mean** | refused unless the `.proto` declares the method idempotent, or the scenario says `unsafe: true` in as many words |
| **Two scales, per measurement** | what happened, and what it is a model of — with an error bar, or with a reason it is absent |
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
losim/src/losim/scale/     the probe grid, the laws, the solve — and the refusals
losim/src/losim/scenario/  a fleet and its weather, as data
losim/src/losim/cli/       losim run <scenario.yaml>
losim/test/                every phase's acceptance criteria, run by ./check.sh
vendor/                    grpc 1.83.1, protobuf 4.36.0, protoc for two platforms
```

Lab code compiles against `build/losim.jar` and the vendored jars alone, never
against these sources.

A handler is debugged on its own, in plain JUnit, with nothing simulating
anything — see [losim/test/junit/HandlerTest.java](losim/test/junit/HandlerTest.java).
That is what the twelve lines of adapter buy.

## Documentation

- [docs/SPIKES.md](docs/SPIKES.md) — what Phase 0 measured, and where each result now lives
