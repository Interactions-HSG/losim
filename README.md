# dissaly

dissaly runs a real gRPC system on one host while applying simulated latency,
failures, resource caps, and scale reduction. It uses the reduced run to project
the system at full size.

The repository contains:

- the student's code, running with real gRPC, real allocation, and real threads
- dissaly, which sits around it and models nodes, time, failures, and scaling

An assignment uses **YAML** for the system and **protobuf plus Java** for the
implementation. A gRPC service starts the work.

The node is made small, so a design that would fail at 16 GiB can fail here at
16 MiB for the same reason. The failure belongs to the program under test.

## Run it

```bash
bin/dissaly dev test    # dissaly's own checks: every phase's acceptance criteria
bin/dissaly dev suite   # the reference suite: gRPC systems, run the way a student runs them

bin/dissaly simulate dissaly/test/simulations/wordcount.yaml \
                   --cp build/test-classes --out build/wordcount.json
bin/dissaly bill build/wordcount.json
bin/dissaly compare build/a.json build/b.json
```

The repository includes the toolchain. The commands work on a laptop, in the
devcontainer, and in a Codespace without downloading dependencies during the build.

## Service code

An ordinary gRPC service starts from a `.proto` with no dissaly types in its API. An
adapter converts grpc-java's `void map(Chunk, StreamObserver<Counts>)` into a
value-returning method that a unit test can call directly.

```java
public final class Mapper extends WorkerBase {
    @Override protected Counts map(Chunk request) {
        var counts = count(request.getText());
        Dissaly.current().reveal("emitted", counts.size());   // silent in a bare test
        return Counts.newBuilder().putAllCounts(counts).build();
    }
}
```

The signature contains no dissaly type. Remove the optional `reveal` call and the
service source still compiles without dissaly on its classpath.

What the call costs is declared in the simulation, under the file that runs it:

```yaml
simulatedDuration:
  dissaly/test/src/Mapper.java: { Map: { fixed: 2 refMs } }
```

The unit is reference-node time. The interceptor sleeps
`fixed * nodeFactor / k_time`. The loader checks that a node runs the file and that
the class serves the named RPC.

Some durations only the running program knows, such as a backoff or a poll interval.
For those cases there is `Dissaly.current().sleep(refMs)`. It uses the same unit and
the same `k_time` scaling. Waiting is not work, so it does not make a node busy.
`Thread.sleep` is different, and the verifier flags it.

Nodes communicate through gRPC. Fire-and-forget uses an `Empty`-returning method on
an async stub, so dissaly records its costs, failures, telemetry, and bytes like any
other call.

A handler calls a peer the same way the job does, by what it serves rather than by
hostname, over a channel dissaly made:

```java
var here = Dissaly.current();
Channel to = here.channelTo(here.peersServing("Worker").get(0));
return WorkerGrpc.newBlockingStub(to).map(request);
```

The result is an `io.grpc.Channel`, and the call site remains plain gRPC. dissaly adds
the interceptor that records latency, byte counts, spans, failures, and retries.

## Simulation data

The simulation file contains the nodes, failures, and timing. Code is referenced
by `.java` path, which lets separate designs use separate simulation files.

```yaml
seed: 7

nodes:
  master:
    instance: m5.large
    zone: eu-central-1a
    runs: { dissaly.Job: dissaly/test/src/WordCountJob.java }
  workers:
    count: 6
    prefix: w
    instance: m5.large
    zone: [eu-central-1a, eu-central-1b]
    runs: { Counter: dissaly/test/src/Counter.java }
    overrides:
      w2: { memoryMb: 4 }
      w5:
        failures:
          - { kill: true, at: 400 refMs }

input:
  unit:  line
  count: 8000
```

Exactly one node runs `dissaly.Job`, which starts the work. Failures are nested under
the node or RPC they affect, so the loader can validate their targets.

Every duration is reference-node time and has to say so. A bare `900` is refused,
and so is `900ms`, because those are ambiguous between the simulated world and the
host.

The loader reports other errors with the source line: an unknown instance type, a
missing `runs:` path, a partition naming an absent node, or a misspelled key.

```text
wordcount.yaml:14: retrying dissaly.t.Volley.Hit is refused — its .proto declares no
idempotency_level, so running it twice is not known to be safe. Declare 'option
idempotency_level = IDEMPOTENT;' on the rpc if it is, or write 'unsafe: true' here
if you mean to retry it anyway.
```

## Scale above 1

The simulation above runs its declared input. The following simulation declares a
larger input, so dissaly shrinks the workload and nodes by the same factor:

```yaml
scale: 5000
```

The `scale` value identifies a model run. `Job.Run` receives a `Workload` and no
other scale information, so the workload size is explicit in the request.

```text
wordcount-scaled.yaml  seed 5  scaled 8,000 -> 40,000,000 records (x5,000), k_time 40

  memoryMb   = 0.0597 + 0.00187 * revealed.distinctKeys^1.013  (R2 1.0000, wobble 0.000)
  wireMb     = 0.0    + 0.000151 * records^0.952               (R2 1.0000, wobble 0.004)
  diskMb       REFUSED: over the lower half it grows as records^0.59
               and over the upper half as records^0.87 ...
  makespan     REFUSED: its exponent moves by 0.66 between independent seed sets of the
               same workload, which over a factor of 5000 is an error bar of x268 ...

                     observed              projected
  memoryMb              6.823                34.07 k  +-x1.10
  wireMb                0.460                 2.63 k  +-x1.04
```

Memory was attributed to distinct keys. Peak reducer memory follows vocabulary size,
which grows more slowly than the record count. Fitting the law against that variable
keeps the projection close to the direct run; fitting against records does not.

Two resources were refused because their measurements did not support a projection.
The engine leaves a field empty and records the reason when its confidence is too low.

The engine replays the timeline with the observed call graph, projected durations,
and each node's concurrency. Four calls into eight cores occupy one wave; thirty-two
occupy four.

The plan travels in the trace, so `projected = f(observed)` is recomputable by
whoever reads it, and is cached against the simulation and the code it profiles.

## Trust markers

dissaly's numbers mean something only if the code stays inside the simulated world.
A handler that reads `System.nanoTime` gets the host's time rather than the
compressed clock; one that writes a real file bypasses the disk model; one that
hands its work to the common pool is charged to nobody.

The verifier reads the project's compiled classes before the run. It **flags rather
than refuses** affected measurements, lets the run continue, and records the nodes:

```text
  trust: 4 nodes report figures that are not reliable
    w0, w1, w2
      each reads the real clock, so its timeline cannot be projected
        Peeker.java:19               System.nanoTime() in map
        Peeker.java:22               System.nanoTime() in map
    spiller
      writes to a real disk, which the disk model never sees; its disk figure
      is a lower bound
        Scribbler.java:21            Files.writeString() in map
    The run continues, but these measurements are wrong.
```

The flags go on the nodes in the trace, and above `scale: 1` they sit next to the
projection they undermine.

Generated code is skipped, including protobuf's superclass and grpc-java's own
`@GrpcGenerated`. A constant is not shared state. `static final Map M = new
HashMap<>()` is one map for eight nodes and is flagged, while `static final
String[] WORDS = {"a", "the"}` is a table and is not. What the call sites do not
say, the declarations do: `System::nanoTime` appears in no instruction anywhere,
only in a bootstrap argument, and a class that `extends Thread` starts itself
through a method on itself.

Unseeded `Random` and identity-hash iteration order are allowed because runs are not
reproducible. Raw threads are also allowed. Work outside the node's pool is
unattributed, and the trace records that fact.

## Cost

The bill prints four buckets separately because each describes a different cost.
Replication can increase capacity and build cost while reducing incidents.

```text
model quantity
  build       services carried                 1.000 services       CHF    0.2500
  capacity    the nodes, for the period            -                CHF   refused
      its exponent moves by 0.793 between independent seed sets of the same workload.
      Over a factor of 6, the error bar is x4.1.
  consumption intermediate data on disk    0.0004000 GB-month       CHF    0.0000
```

A run above `scale: 1` produces an observed bill and a bill for the modeled size.
The engine refuses quantities whose measurements cannot support projection. Capacity
depends on the timeline, which is the noisiest quantity dissaly measures.

The result lists byte and storage costs directly. Capacity remains absent when the
timeline is too uncertain to project.

Prices are course data and live in [prices/](prices/), outside the simulator. What a
node costs to rent is deliberately not there: that belongs to the instance
catalogue, beside its vCPUs and its memory.

## Reference suite

The suite contains gRPC systems run through
the command line a student types and asserted against the trace it wrote. The
systems compile against `build/dissaly.jar` and the vendored gRPC alone, and every
assertion reads the trace JSON off disk.

Nine of them are systems. Four test the engine rather than the systems: a projection
checked against a run at full size, a matrix that varies the system size
independently of the data, two workloads the engine has to refuse, and one ladder fitted at four
levels of instrumentation.

The reference suite has found bugs that the phase suites did not cover. One example:
a job could not run at `NO_PAYLOAD` because a span recorded its
result as null when payloads were off and span details are a concurrent map, which
rejects a null value.

CI runs everything twice, on a plain runner and inside the image a Codespace boots,
and then compares the two traces. What has to agree is structure and attribution.
The measurements are allowed to differ.

## Project status

The system includes one in-process server and one executor per node, gRPC
interceptors around every call, simulations, scaling, trust checks, billing, and a
reference suite of gRPC systems in CI.

| | |
|---|---|
| real concurrency | each node's pool is its vCPU model, so four calls into a two-vCPU node really do queue |
| a compressed clock | every declared duration divided by `k_time`, calibrated per host, with sub-floor costs owed rather than lost |
| a network | latency by zone, jitter, loss, partitions, and a dead node, a cut link and a lost packet are one event from the caller |
| three-channel telemetry | events, spans that carry a parent across the RPC boundary, and dense series, with every call's real argument and real result |
| memory, measured twice | allocation per node, exactly; and a retained-heap walk, because only one of those decides an out-of-memory |
| failures | kill, freeze, degrade, spot reclaim with notice, partition, restart, and per-RPC status, slowdown, or dropped request |
| retries | refused unless the `.proto` declares the method idempotent or the simulation sets `unsafe: true` |
| two scales, per measurement | what happened, and what it is a model of, with an error bar or with a reason it is absent |
| **dissaly's own cost, excluded** | everything dissaly does on a node's threads is metered and subtracted, so what is reported is the program's |
| trust markers | real clocks, real files, real sockets, shared statics and unattributed threads, found in the compiled classes at the line they were written on, flagged, never refused |
| a bill, at both scales | five buckets over the quantities the run produced, and at full scale a capacity line absent with a reason, because it depends on the one thing the engine would not project |

The `reveal` calls do affect the raw measurement. A thousand
calls per handler move the *unsubtracted* memory exponent by 0.026, while
the reported one by 0.0004. The law a student's code is projected by does not
depend on how much they instrumented it.

## Repository layout

```text
  dissaly/src/dissaly/api/       what a handler may say to dissaly and all it can reach
dissaly/src/dissaly/runtime/   the nodes, the servers, the two interceptors
dissaly/src/dissaly/trace/     the three-channel recorder and the trace it writes
  dissaly/src/dissaly/time/      the compressed clock and failure timing
dissaly/src/dissaly/res/       instance types, the heap walk, dissaly's own meter
  dissaly/src/dissaly/scale/     the probe grid, fitted laws, solves, and refusals
  dissaly/src/dissaly/sim/       a system and its failure conditions as data
dissaly/proto/dissaly/         dissaly.Job — the one service dissaly ships
dissaly/src/dissaly/verify/    what makes a number stop meaning what it says
  dissaly/src/dissaly/price/     cost buckets and excluded quantities
dissaly/src/dissaly/cli/       dissaly simulate | bill | compare
dissaly/test/                every phase's acceptance criteria, run by `dissaly dev test`
tests/                     the reference suite: gRPC systems, run by `dissaly dev suite`
bin/dissaly                  the CLI: build the simulator, then run it
prices/                    course data — what egress costs, what being late costs
vendor/                    grpc 1.83.1, protobuf 4.36.0, protoc for two platforms
```

An assignment compiles against `build/dissaly.jar` and the vendored jars alone, never
against these sources.

A handler is debugged on its own, in plain JUnit, with nothing simulating anything.
See [dissaly/test/junit/HandlerTest.java](dissaly/test/junit/HandlerTest.java).

## Documentation

The manual is a Mintlify site in [docs/](docs/). It covers the quickstart,
simulation grammar, trace format, scale engine, bill, and viewer.

```bash
bin/dissaly serve docs        # preview at http://localhost:3000
bin/dissaly dev docs check    # the manual's own check
```

`dissaly dev docs check` checks the manual. This repository ships worked
solutions to the coursework, and the manual must not contain them. Every page is
scanned against a rule set in [docs-check/](docs-check/), and the check tests
itself before it scans. Every rule has a sample it must catch and a nearby sample
it must not. It also checks the site's own shape: every page reachable, every
internal link resolving. It runs in CI on every push.

It lives outside [docs/](docs/). A non-page file under a Mintlify docs directory is
served as a static asset, and this check's fixtures contain worked assignment prose
on purpose. `docs/` holds only what is meant to be published.
