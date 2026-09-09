# Reference suite

The suite covers gRPC systems and scaler-engine cases. Each case runs through the
student-facing command line and is asserted against its trace.

```bash
bin/losim dev suite            # all of them, about five minutes
bin/losim dev suite t5 t8      # one or two
bin/losim dev suite t10        # the engine against ground truth
```

The system cases take under a minute. The engine cases take longer because each
scaled run fits a plan from about thirty small runs.

`losim dev suite` is separate from `losim dev test`. The latter tests losim's own
classes; the suite tests the student-facing workflow:

- the systems compile against `build/losim.jar` and the vendored gRPC only, never
  against `losim/src`, matching the lab classpath;
- every case runs through `losim run <scenario.yaml> --cp ...`, so the scenario
  grammar, class loading, and exit codes are exercised;
- every assertion reads the **trace JSON off disk**. The trace is the interchange
  format (D9), so unreadable output fails the assertion;
- each case uses its own JVM, which prevents state from one case affecting another.

| | the system | asserts | catches |
|---|---|---|---|
| **t1** handler-alone | a gRPC handler called straight from a test, **no simulation running** | returns the right `Counts`; `reveal` and `sleep` are silent; `peers()` and `channelTo()` **throw** | losim leaking into a signature — the case stops compiling — and an absent context inventing state that makes a green test meaningless |
| **t2** one-call | one client, one server, one unary call, and one message with an enum and a `oneof` | dotted `method`; bytes = `getSerializedSize()` + framing; map entries rendered sorted; enum by name | codegen and marshaling wiring; the `Worker/Map` trap; a renderer that drifts, so two traces of one run stop diffing |
| **t3** deadline | 500 refMs of work, 200 refMs of patience | `DEADLINE_EXCEEDED`; an `rpc_timeout`; the wait was ~200 refMs of *simulated* time; the server was cut off mid-work | a declared duration never applied, or applied after the response; a deadline not divided by `k_time`, which makes every timing lesson depend on the laptop |
| **t4** pingpong | two machines volleying an `Empty`-returning async call | both directions in the trace; the caller dispatched ten 200 refMs calls in ~1 ms | that fire-and-forget really is gRPC, with no second messaging path to exempt it from costs, faults and byte counts |
| **t5** contention | 8 calls at 100 refMs into a **2-vCPU** machine | ~4 waves, not 1 and not 8; `queue_wait` in the trace | `directExecutor()` creeping in, or an executor not sized to vCPUs — the whole machine model |
| **t6** pipeline | split -> 4 mappers -> shuffle -> 2 reducers | no word lost, **exactly**; every mapper worked; handlers overlap; bytes out = bytes in | fan-out collapsing to sequential, which answers correctly and teaches nothing; byte-accounting drift |
| **t7** abuse | the same pipeline, with a kill mid-run, a restart, standing chaos, and two retry policies | the exact answer anyway; the fault landed where it was written; the non-idempotent retry refused at **`file:line`** before anything ran | fault scheduling, the idempotency gate, and a master that only works when nothing goes wrong |
| **t8** oom | an accumulating reducer, run twice: once on a machine too small for its bucket, once on one with room | an `oom` naming machine, resource, cap and **measured** demand; the roomy run completes | the retained-heap walk regressing. Allocation cannot tell an accumulating reducer from a streaming one; only retention can |
| **t9** causality | the pipeline again, across two zones | every server span opened under the call that reached it, on another machine; nothing served before it was called; concurrency reported concurrent | trace ordering, and the metadata-header propagation — take the parent from the ambient context instead and every span hangs off the root, silently |

## Engine cases

These need a workload whose resources genuinely scale differently, or they prove
nothing at all. The corpus is drawn from a Zipf distribution so that vocabulary
follows Heaps' law: memory tracks *distinct keys*, which grow sublinearly, while disk
and wire track *volume*, which grows linearly. Uniformly random words would give an
exponent of 1 for everything and every case below would pass vacuously.

| | the runs | asserts | catches |
|---|---|---|---|
| **t10** groundtruth | one scaled run projecting to 48,000 records from a ladder topping out at 8,000, and one direct run **at** 48,000 | every projected resource within 25% of what actually happened, and never worse than multiplying the small run by the size ratio; memory attributed to distinct keys; the makespan **absent with a reason**; the plan recomputable from the trace | the engine silently degrading. This is the core projection test; the other cases check projection shape, which can be correct even when the result is wrong |
| **t11** scale-wordcount | five cells: 2, 4 and 8 workers clean, plus one kill and standing chaos at four | the attribution never moves with the cluster, and the memory exponent moves by <0.05 across the row — while disk *per machine* halves when the cluster doubles; four times the cluster shortens the phase that fans out and not the phase that merges; the weathered cells carry a fault amplification the clean one does not | the engine folding the cluster dimension into the data dimension — the failure mode that makes every projection plausible and wrong, because nothing looks broken |
| **t12** refusal | a reducer that spills above a key count, and a cluster whose fixed 64 MB index dwarfs what the probe scale varies | the split-ladder test catches the bend and **R² over the whole ladder is still 0.88**; no projection is emitted for that resource while the others still are; the second run names its resource and does not happen at all | extrapolating past a discontinuity, and anyone later "simplifying" the check back to R². The second half is the reason that number is quoted in the refusal itself |
| **t13** transparent | the same ladder four times: telemetry off, no payloads, everything rendered, and a thousand `reveal` calls per handler | the **fitted laws**, not the numbers: the allocation exponent moves by 0.0001 across all four, while losim charges itself 0.31 -> 4.45 -> 54.19 MB and meters 2,132 regions against 162,132 | the observer effect creeping back in. It regresses silently: every number stays plausible and only the projection is wrong. **The extreme case is mandatory** — at one reveal per handler a leak that halves an exponent is undetectable |

The plan cache is keyed on telemetry level so t13 does not reuse a plan fitted with a
different level.

## Bugs found

The phase suites missed these bugs because they require mid-call termination, values
that no other test reads, events written by both sides of a call, or an unused
telemetry level.

- Dangling spans. A run that ended while a handler was still in flight left its
  span open forever. "No span dangles" is meant to mean the recorder lost track; it
  had come to mean "the job was tidy about finishing".
- An out-of-memory event arrived too late to count. The cap was checked only on
  the sampler's cadence, so a reducer handed its bucket in the closing moments was
  reported comfortably inside a cap it had already exceeded.
- Every call in every trace claimed to have lasted -1 ms. `close(span, "ms",
  span.grossMs())` evaluates its argument before `close` sets the span's end.
- One call had two types. The client wrote `call` as a number and the server wrote
  it as a string, so nothing downstream could join the two halves of a call.
- A job could not run at `NO_PAYLOAD`. `cluster.compute(...)` recorded its
  result as `null` when payloads were off, and span details are a concurrent map,
  which rejects a null value outright. The recording code therefore killed the work
  it was recording; no job had run at that level.
- The probe ladder used the wrong cluster. It used the largest shape the file declared
  rather than the cluster the run would actually use, so a scenario declaring two
  workers got laws fitted on four. The error went undetected when the shapes agreed.
- The plan cache ignored the telemetry level, so a plan fitted with every payload
  recorded would be handed to a run with payloads off. The fitted plan then used the
  wrong instrumentation level.
- Every run smaller than a kilobyte reported zero bytes. The trace wrote
  megabytes to three decimal places, and three decimal places of a megabyte is a
  kilobyte. Correct totals, a unit that threw them away.

The trace also lacked per-machine totals. Ground-truth comparisons and the bill could
not read what a machine consumed. Per-machine totals are now a top-level channel
beside `events`, `spans`, and `series`.

## Gallery

`tests/gallery/` is a separate project for viewer examples. The suite does not assert
its results. `GalleryTest` walks its simulations with the other test directories.

It contains these directories:

| | |
|---|---|
| `gallery/proto/thumbs.proto` | one schema: a service that turns frames into thumbnails, and a blob store to keep them in |
| `gallery/systems/` | four classes — the job, the renderer, the store, and the generator that gives them data whose catalogue grows more slowly than its volume |
| `gallery/simulations/` | seven files, differing from each other in as few lines as possible |

| | what it is for |
|---|---|
| **direct** | the plain one. Four renderers, nothing going wrong, no `scale:` — every number in the trace was measured while it happened |
| **scaled** | the same design declared six times bigger than anything executed. Four laws fitted, memory attributed to a revealed count rather than to the workload, and the makespan refused rather than extrapolated across a bend |
| **kill-and-restart** | one renderer dies and comes back, and a survivor absorbs its catalogue |
| **rate-of-failures** | `per:` at two levels: a pool that degrades on its own draw, and one node whose rpc returns UNAVAILABLE at a call rate, with a retry policy that the schema — not the file — allows |
| **partitioned** | the network splits and heals. From the master's side, unreachable and dead are the same thing |
| **cross-zone** | three tiers in two zones, so every split crosses once and the trace says at which hop |
| **out-of-memory** | one renderer too small for the catalogue it ends up with. Nothing declares that it will fail |

Between them they carry twenty-one event kinds, including the two the reference
suite never produces: `heal`, which needs a run that partitions something, and
`rpc_failure`, which needs a service that is wrong rather than a node that is
down. `viewer/checks/stops.ts` says out loud that it cannot hold the scrubber to
those without the gallery.

Regenerating the traces is not part of the suite because it takes several minutes
and produces build output. Run the same commands manually: compile the schema,
compile the Java against the jar and vendored gRPC, then run one simulation per file:

```bash
PLATFORM=osx-aarch_64          # or linux-x86_64, or linux-aarch_64
OUT=build/gallery
mkdir -p $OUT/gen $OUT/classes $OUT/traces

vendor/bin/protoc-$PLATFORM \
  --plugin=protoc-gen-grpc-java=vendor/bin/protoc-gen-grpc-java-$PLATFORM \
  --java_out=$OUT/gen --grpc-java_out=$OUT/gen \
  -I tests/gallery/proto tests/gallery/proto/thumbs.proto

javac --release 21 -d $OUT/classes \
  -cp "$(ls vendor/jars/*.jar | tr '\n' ':')build/losim.jar" \
  $(find $OUT/gen tests/gallery/systems -name '*.java')

for s in tests/gallery/simulations/*.yaml; do
  n=$(basename "$s" .yaml)
  bin/losim simulate --no-view "$s" --cp $OUT/classes --out $OUT/traces/$n.json
done
```

`losim dev viewer traces --gallery` copies the traces into the picker and prices each
one beside it. Most finish in seconds; **scaled** takes about twelve because it fits
its plan from twenty-eight probe results first.
