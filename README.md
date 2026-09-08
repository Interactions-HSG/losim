# losim

losim runs a real gRPC system on one machine and changes the conditions around it.
It slows calls, injects failures, caps resources, and can shrink both the data and
the machines by the same factor so a large system can be exercised on a laptop. It
then projects the observed run back to full scale.

The system has two parts:

- the student's code, running with real gRPC, real allocation, and real threads
- losim, which sits around it and models machines, time, failures, and scaling

The machine is made small, so a design that would fail at 16 GiB can fail here at
16 MiB for the same reason. The failure belongs to the program under test.

## Try it

```bash
bin/losim dev test    # losim's own checks: every phase's acceptance criteria
bin/losim dev suite   # the reference suite: gRPC systems, run the way a student runs them

bin/losim run losim/test/scenarios/wordcount.yaml \
              --cp build/test-classes --out build/wordcount.json
bin/losim bill build/wordcount.json
bin/losim diff build/a.json build/b.json
```

Nothing is downloaded and nothing is generated at build time. The toolchain is
vendored, so the same commands behave the same way on a laptop, in the devcontainer,
and in a Codespace.

## A service

An ordinary gRPC service starts from an ordinary `.proto`, with no losim types in
the API. A small adapter turns grpc-java's `void map(Chunk, StreamObserver<Counts>)`
into a value-returning method, which makes the handler easy to call from a plain
unit test.

```java
public final class Mapper extends WorkerBase {
    @Override protected Counts map(Chunk request) {
        var counts = count(request.getText());
        Losim.current().reveal("emitted", counts.size());   // silent in a bare test
        return Counts.newBuilder().putAllCounts(counts).build();
    }
}
```

No losim type appears in the signature, and the call above is optional. Delete it
and this file still compiles with losim off the classpath.

What the call costs is declared in the scenario, under the class that runs it:

```yaml
takes:
  Mapper: { Map: { refMs: 2 } }
```

The unit is reference-machine time, so it composes with scaling: the interceptor
sleeps `refMs * machineFactor / k_time`. A key naming a class that does not exist,
or an rpc that class does not serve, is refused before anything runs.

Some durations only the running program knows, such as a backoff or a poll interval.
For those cases there is `Losim.current().sleep(refMs)`. It uses the same unit and
the same `k_time` scaling. Waiting is not work, so it does not make a machine busy.
`Thread.sleep` is different, and the verifier flags it.

gRPC is the only way machines talk. Even fire-and-forget is an `Empty`-returning
method on an async stub, so costs, faults, telemetry, and byte counts apply to it
the same way they apply to any other call.

A handler calls a peer the same way the job does, by what it serves rather than by
hostname, over a channel losim made:

```java
var here = Losim.current();
Channel to = here.channelTo(here.peersServing("Worker").get(0));
return WorkerGrpc.newBlockingStub(to).map(request);
```

What comes back is an `io.grpc.Channel`, and the call site is plain gRPC. losim
adds the interceptor, which is where latency, byte counts, spans, faults, and the
retry policy live.

## A scenario

The cluster, its weather, and its bad afternoon are data. Anything that needs code
points at a class, so two designs can be compared by comparing two scenarios.

```yaml
seed: 7
job: WordCountJob

machines:
  master: { instance: m5.large, zone: eu-central-1a }
  workers:
    count: 6
    prefix: w
    instance: m5.large
    zone: [eu-central-1a, eu-central-1b]
    serves: [Counter]
    overrides:
      w2: { memoryMb: 4 }

faults:
  - { at: 400 refMs, kill: w5 }
```

Every duration is reference-machine time and has to say so. A bare `900` is refused,
and so is `900ms`, because those are ambiguous between the simulated world and the
host.

Everything else that can be wrong is refused the same way, with the line it was
written on: an unknown instance type, a fault aimed at a machine that is not in the
cluster, or a key that is a typo for a real one.

```text
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
scale: 5000
```

```text
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

Memory was attributed to distinct keys, not to records. Peak reducer memory is a
function of vocabulary, which grows more slowly than records. Fitting it against
the right variable gives a projection that stays close to ground truth. Fitting it
against records does not.

Two resources were refused rather than guessed at. A projection carries its
confidence or it is absent. A plausible number without that context is only a guess,
so the engine leaves the field empty when it cannot support the result.

The timeline is replayed, not multiplied. Four calls into eight cores take one wave;
thirty-two take four. Replaying the observed call graph with projected durations
and each machine's real concurrency keeps the result close to the trace.

The plan travels in the trace, so `projected = f(observed)` is recomputable by
whoever reads it, and is cached against the scenario and the code it profiles.

## Trust markers

losim's numbers mean something only if the code stays inside the simulated world.
A handler that reads `System.nanoTime` gets the host's time rather than the
compressed clock; one that writes a real file bypasses the disk model; one that
hands its work to the common pool is charged to nobody.

So the verifier reads the lab's compiled classes before anything runs, and **flags
rather than refuses. The run still happens, and what carries a caveat says so beside
itself:

```text
  trust: 4 machines report figures that do not mean what they say
    w0, w1, w2
      each reads the real clock, so its timeline is not projectable
        Peeker.java:19               System.nanoTime() in map
        Peeker.java:22               System.nanoTime() in map
    spiller
      writes to a real disk, which the disk model never sees, so its disk figure
      is a lower bound
        Scribbler.java:21            Files.writeString() in map
    Nothing was stopped: each of these is a wrong number, not a broken run.
```

The flags go on the machines in the trace, and in scaled mode they sit next to the
projection they undermine.

Generated code is skipped without a special case: protoc's output trips these rules
freely, and it is recognised by protobuf's superclass and grpc-java's own
`@GrpcGenerated`. A constant is not shared state: `static final Map M = new
HashMap<>()` is one map for eight machines and is flagged, while `static final
String[] WORDS = {"a", "the"}` is a table and is not. What the call sites do not
say, the declarations do: `System::nanoTime` appears in no instruction anywhere,
only in a bootstrap argument, and a class that `extends Thread` starts itself
through a method on itself.

Unseeded `Random` and identity-hash iteration order are fine, because runs are not
reproducible anyway. Raw threads are not banned either. Work outside the machine's
own pool is attributed to nobody, and that is what gets said.

## The cost

Four buckets are printed apart rather than summed because they answer different
questions. Replication triples capacity and adds to build in order to empty
incidents.

```text
what it is a model of
  build       services carried                 1.000 services       CHF    0.2500
  capacity    the cluster, for the period            -                CHF   refused
      its exponent moves by 0.793 between independent seed sets of the same workload,
      which over a factor of 6 is an error bar of x4.1 — wider than anything it would
      be asked to distinguish
  consumption intermediate data on disk    0.0004000 GB-month       CHF    0.0000
```

A scaled run is billed twice, for what happened and for the job it models. The engine
does not project every quantity. Capacity depends on the timeline, and the timeline
is the noisiest thing losim measures.

That leaves an honest account: the bytes cost this much, the storage costs this much,
and the machine cost can stay absent when the timeline is too uncertain.

Prices are course data and live in [prices/](prices/), outside the simulator. What a
machine costs to rent is deliberately not there: that belongs to the instance
catalogue, beside its vCPUs and its memory.

## The reference suite

Thirteen cases in [tests/](tests/), plus the bill, each a gRPC system run through
the command line a student types and asserted against the trace it wrote. The
systems compile against `build/losim.jar` and the vendored gRPC alone, and every
assertion reads the trace JSON off disk.

Nine of them are systems. Four test the engine rather than the systems: a projection
checked against a run at full size, a matrix that varies the cluster independently
of the data, two workloads the engine has to refuse, and one ladder fitted at four
levels of instrumentation.

It has found seven bugs so far, none of which the phase suites could see. The
sharpest: a job could not run at `NO_PAYLOAD` at all, because `compute` recorded its
result as null when payloads were off and span details are a concurrent map, which
rejects a null value.

CI runs everything twice, on a plain runner and inside the image a Codespace boots,
and then compares the two traces. What has to agree is structure and attribution.
The measurements are allowed to differ.

## Status

Phases 1 through 5 are in: the cluster, direct mode, the scaler engine, the trust
markers, and the reference suite. One in-process server per machine, one executor
per machine sized to its vCPU count, losim wrapped around every call as gRPC's own
interceptors, a scenario driving it, an engine that shrinks the world and projects
the result back, a verifier that says which answers still mean what they say, and
thirteen gRPC systems in CI that check whether it still works.

| | |
|---|---|
| real concurrency | each machine's pool is its vCPU model, so four calls into a two-vCPU machine really do queue |
| a compressed clock | every declared duration divided by `k_time`, calibrated per host, with sub-floor costs owed rather than lost |
| a network | latency by zone, jitter, loss, partitions, and a dead machine, a cut link and a lost packet are one event from the caller |
| three-channel telemetry | events, spans that carry a parent across the RPC boundary, and dense series, with every call's real argument and real result |
| memory, measured twice | allocation per machine, exactly; and a retained-heap walk, because only one of those decides an out-of-memory |
| a bad afternoon | kill, freeze, degrade, spot reclaim with notice, partition, restart, at an instant or as a standing rate whose draws come from the seed |
| retries you have to mean | refused unless the `.proto` declares the method idempotent, or the scenario says `unsafe: true` in as many words |
| two scales, per measurement | what happened, and what it is a model of, with an error bar or with a reason it is absent |
| **losim's own cost, excluded** | everything losim does on a machine's threads is metered and subtracted, so what is reported is the program's |
| trust markers | real clocks, real files, real sockets, shared statics and unattributed threads, found in the compiled classes at the line they were written on, flagged, never refused |
| a bill, at both scales | five buckets over the quantities the run produced, and at full scale a capacity line absent with a reason, because it depends on the one thing the engine would not project |

That last row is the one that is easy to get wrong and hard to spot. A thousand
`reveal` calls per handler move the *unsubtracted* memory exponent by 0.026, and
the reported one by 0.0004. The law a student's code is projected by does not
depend on how much they instrumented it.

## Layout

```text
losim/src/losim/api/       what a handler may say to losim — and all it can reach
losim/src/losim/runtime/   the cluster, the machines, the two interceptors
losim/src/losim/trace/     the three-channel recorder and the trace it writes
losim/src/losim/time/      the compressed clock, and fault placement
losim/src/losim/res/       instance types, the heap walk, losim's own meter
losim/src/losim/scale/     the probe grid, the laws, the solve — and the refusals
losim/src/losim/scenario/  a cluster and its weather, as data
losim/src/losim/verify/    what makes a number stop meaning what it says
losim/src/losim/price/     five buckets, and what cannot be put in them
losim/src/losim/cli/       losim run | bill | diff
losim/test/                every phase's acceptance criteria, run by `losim dev test`
tests/                     the reference suite: gRPC systems, run by `losim dev suite`
bin/losim                  the CLI: build the simulator, then run it
prices/                    course data — what egress costs, what being late costs
vendor/                    grpc 1.83.1, protobuf 4.36.0, protoc for two platforms
```

Lab code compiles against `build/losim.jar` and the vendored jars alone, never
against these sources.

A handler is debugged on its own, in plain JUnit, with nothing simulating anything.
See [losim/test/junit/HandlerTest.java](losim/test/junit/HandlerTest.java).

## Documentation

The manual is a Mintlify site in [docs/](docs/), from the quickstart to the scenario
grammar, trace format, scale engine, bill, and viewer.

```bash
bin/losim serve docs        # preview at http://localhost:3000
bin/losim dev docs check    # the manual's own check
```

`losim dev docs check` is the one worth knowing about. This repository ships worked
solutions to the coursework, and the manual must not contain them. Every page is
scanned against a rule set in [docs-check/](docs-check/), and the check tests
itself before it scans. Every rule has a sample it must catch and a nearby sample
it must not. It also checks the site's own shape: every page reachable, every
internal link resolving. It runs in CI on every push.

It lives outside [docs/](docs/). A non-page file under a Mintlify docs directory is
served as a static asset, and this check's fixtures contain worked assignment prose
on purpose. `docs/` holds only what is meant to be published.
