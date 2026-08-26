# The reference suite

Thirteen cases: nine gRPC systems a course could ship, and four that test the scaler
engine rather than the systems. Each runs through the command line a student types
and is asserted against the trace it wrote.

```bash
tests/run.sh            # all of them, about five minutes
tests/run.sh t5 t8      # one or two
tests/run.sh t10        # the engine against ground truth
```

The nine systems take under a minute between them. The four engine cases take the
rest: each scaled run fits its plan from a grid of about thirty small runs, and there
are thirteen scaled runs across t10 to t13. That is the price of checking the one
thing losim claims that a smaller simulator does not.

**This is not `./check.sh`.** That one is losim's own acceptance criteria, calling
into losim's classes. This one is the product surface, and the difference is the
point:

- the systems compile against `build/losim.jar` and the vendored gRPC **alone**,
  never against `losim/src` — the same rule a lab is under, kept true by being used;
- every case runs through `losim run <scenario.yaml> --cp …`, so the scenario
  grammar, the class loading and the exit codes are exercised rather than bypassed;
- every assertion reads the **trace JSON off disk**. The trace is the interchange
  format (D9), and a build whose trace was unreadable would pass every check in
  `./check.sh`;
- **one JVM per case.** A suite whose cases share a JVM has an order, and an order
  is a thing that breaks when somebody adds a case in the middle.

| | the system | asserts | catches |
|---|---|---|---|
| **t1** handler-alone | a gRPC handler called straight from a test, **no simulation running** | returns the right `Counts`; `reveal` and `sleep` are silent; `peers()` and `channelTo()` **throw** | losim leaking into a signature — the case stops compiling — and an absent context inventing state that makes a green test meaningless |
| **t2** one-call | one client, one server, one unary call, and one message with an enum and a `oneof` | dotted `method`; bytes = `getSerializedSize()` + framing; map entries rendered sorted; enum by name | codegen and marshalling wiring; the `Worker/Map` trap; a renderer that drifts, so two traces of one run stop diffing |
| **t3** deadline | 500 refMs of work, 200 refMs of patience | `DEADLINE_EXCEEDED`; an `rpc_timeout`; the wait was ~200 refMs of *simulated* time; the server was cut off mid-work | a declared duration never applied, or applied after the response; a deadline not divided by `k_time`, which makes every timing lesson depend on the laptop |
| **t4** pingpong | two machines volleying an `Empty`-returning async call | both directions in the trace; the caller dispatched ten 200 refMs calls in ~1 ms | that fire-and-forget really is gRPC, with no second messaging path to exempt it from costs, faults and byte counts |
| **t5** contention | 8 calls at 100 refMs into a **2-vCPU** machine | ~4 waves, not 1 and not 8; `queue_wait` in the trace | `directExecutor()` creeping in, or an executor not sized to vCPUs — the whole machine model |
| **t6** pipeline | split → 4 mappers → shuffle → 2 reducers | no word lost, **exactly**; every mapper worked; handlers overlap; bytes out = bytes in | fan-out collapsing to sequential, which answers correctly and teaches nothing; byte-accounting drift |
| **t7** abuse | the same pipeline, with a kill mid-run, a restart, standing chaos, and two retry policies | the exact answer anyway; the fault landed where it was written; the non-idempotent retry refused at **`file:line`** before anything ran | fault scheduling, the idempotency gate, and a coordinator that only works when nothing goes wrong |
| **t8** oom | an accumulating reducer, run twice: once on a machine too small for its bucket, once on one with room | an `oom` naming machine, resource, cap and **measured** demand; the roomy run completes | the retained-heap walk regressing. Allocation cannot tell an accumulating reducer from a streaming one; only retention can |
| **t9** causality | the pipeline again, across two zones | every server span opened under the call that reached it, on another machine; nothing served before it was called; concurrency reported concurrent | trace ordering, and the metadata-header propagation — take the parent from the ambient context instead and every span hangs off the root, silently |

## Four that test the engine, not the systems

These need a workload whose resources genuinely scale differently, or they prove
nothing at all. The corpus is drawn from a Zipf distribution so that vocabulary
follows Heaps' law: memory tracks *distinct keys*, which grow sublinearly, while disk
and wire track *volume*, which grows linearly. Uniformly random words would give an
exponent of 1 for everything and every case below would pass vacuously.

| | the runs | asserts | catches |
|---|---|---|---|
| **t10** groundtruth | one scaled run projecting to 48,000 records from a ladder topping out at 8,000, and one direct run **at** 48,000 | every projected resource within 25% of what actually happened, and never worse than multiplying the small run by the size ratio; memory attributed to distinct keys; the makespan **absent with a reason**; the plan recomputable from the trace | the engine silently degrading. The core contribution's only real test — every other case checks that a projection is *shaped* right, and a projection can be perfectly well shaped and completely wrong |
| **t11** scale-wordcount | five cells: 2, 4 and 8 workers clean, plus one kill and standing chaos at four | the attribution never moves with the fleet, and the memory exponent moves by <0.05 across the row — while disk *per machine* halves when the fleet doubles; four times the fleet shortens the phase that fans out and not the phase that merges; the weathered cells carry a fault amplification the clean one does not | the engine folding the fleet dimension into the data dimension — the failure mode that makes every projection plausible and wrong, because nothing looks broken |
| **t12** refusal | a reducer that spills above a key count, and a fleet whose fixed 64 MB index dwarfs what the probe scale varies | the split-ladder test catches the bend and **R² over the whole ladder is still 0.88**; no projection is emitted for that resource while the others still are; the second run names its resource and does not happen at all | extrapolating past a discontinuity, and anyone later "simplifying" the check back to R². The second half is the reason that number is quoted in the refusal itself |
| **t13** transparent | the same ladder four times: telemetry off, no payloads, everything rendered, and a thousand `reveal` calls per handler | the **fitted laws**, not the numbers: the allocation exponent moves by 0.0001 across all four, while losim charges itself 0.31 → 4.45 → 54.19 MB and meters 2,132 regions against 162,132 | the observer effect creeping back in. It regresses silently: every number stays plausible and only the projection is wrong. **The extreme case is mandatory** — at one reveal per handler a leak that halves an exponent is undetectable |

The last one is also why the plan cache is keyed on the telemetry level: without that,
three of t13's four runs would silently reuse the first one's plan and the case would
be checking nothing.

## What it has already caught

Seven bugs, none of which the phase suites could see, because each needs something
they never do — end a run mid-call, read a value nobody read, join two events written
by opposite sides of a call, or run a job at a telemetry level the tests never use.

- **Dangling spans.** A run that ended while a handler was still in flight left its
  span open forever. "No span dangles" is meant to mean the recorder lost track; it
  had quietly come to mean "the job was tidy about finishing".
- **An out-of-memory that arrived too late to count.** The cap was checked only on
  the sampler's cadence, so a reducer handed its bucket in the closing moments was
  reported comfortably inside a cap it had already exceeded.
- **Every call in every trace claimed to have lasted −1 ms.** `close(span, "ms",
  span.grossMs())` evaluates its argument before `close` sets the span's end.
- **One call, two types.** The client wrote `call` as a number and the server wrote
  it as a string, so nothing downstream could join the two halves of a call.
- **A job could not run at `NO_PAYLOAD` at all.** `cluster.compute(...)` recorded its
  result as `null` when payloads were off, and span details are a concurrent map,
  which rejects a null value outright — so the recording killed the work it was
  recording. Nothing had ever run a job at that level.
- **The probe ladder was climbed on the wrong fleet.** It used the largest shape in
  `fleets:` rather than the fleet the run would actually use, so a scenario declaring
  two workers got laws fitted on four. Invisible wherever the two happened to agree,
  which was everywhere they had been tried.
- **The plan cache ignored the telemetry level**, so a plan fitted with every payload
  recorded would be handed straight to a run with them off — the same mistake as
  fitting on clean runs and predicting a faulty one, and silent in the same way.

And one gap rather than a bug: the trace carried no per-machine totals, so nothing
downstream — a ground-truth comparison, the bill — could read what a machine actually
consumed. They are now a fourth top-level channel beside `events`, `spans` and
`series`.
