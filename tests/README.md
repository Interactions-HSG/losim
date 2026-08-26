# The reference suite

Nine gRPC systems a course could ship, each run through the command line a student
types, each asserted against the trace it wrote.

```bash
tests/run.sh            # all of them
tests/run.sh t5 t8      # one or two
```

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

## What it has already caught

Four bugs, none of which the phase suites could see, because each needs something
they never do — end a run mid-call, read a value nobody read, or join two events
written by opposite sides of a call.

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
