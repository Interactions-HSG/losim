# What Phase 0 measured

Every design decision in losim rests on something below rather than on an
argument. Numbers are from an Apple M-series laptop on JDK 25; the *shapes* are
what matter, and anything a run depends on is re-measured at startup rather than
trusted from here.

The prototype code these were run against has been folded into the simulator and
deleted, so nothing is now maintained twice:

| what it settled | where it lives |
|---|---|
| per-machine allocation, the machine model | `losim/runtime/Machine.java` |
| park calibration, the sleep debt | `losim/time/Clock.java` |
| absolute-deadline fault placement | `losim/time/Dispatcher.java` |
| the three-channel recorder and its encoding | `losim/trace/Telemetry.java` |
| the retained-heap walk | `losim/res/Retained.java` |
| power-law fits, the fixed term, split-ladder refusal | `losim/scale/Fit.java` |
| bracket-and-subtract, and its self-cost calibration | `losim/res/Meter.java` |

The claims are checked continuously rather than historically: `./check.sh` re-runs
the ones that would regress silently — the transparency law (S7), the questions
telemetry must answer (S4) and the trace's size budget — against the real tree.

---

## S1 — Is per-machine memory measurable?  **PASS**

One in-process gRPC server per machine, one `ThreadPoolExecutor` per machine
sized to its vCPU count, and `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`
summed over that machine's pool threads.

```
machine a allocated 64.00 MB (deliberate: 64 MB, error 0.00%)
machine b allocated 1103.99 MB of concurrent noise
```

**0.00% error** attributing a 64 MB allocation to the right machine while a
neighbouring machine allocated 1.1 GB concurrently. This is the number the whole
scale model rests on, and it is exact.

- `getThreadAllocatedBytes` is on `com.sun.management.ThreadMXBean`, **not** the
  `java.lang.management` interface — it needs a cast.
- It returns −1 for virtual threads (JDK-8303251). Platform threads are therefore
  not a fallback but a requirement, which is one more reason the machine's pool is
  the vCPU model.

## S2 — Does the real clock behave well enough?  **PASS**, and the plan was wrong three times

### The floor is 20× lower than assumed, because the error is a scale error

`LockSupport.parkNanos` overshoots by a **stable ratio**, not a fixed offset:

```
target        mean     ratio
 0.020ms   0.028ms    1.416x
 0.100ms   0.129ms    1.295x
 1.000ms   1.256ms    1.256x
10.000ms  12.253ms    1.225x     <- stable across 500x of scale
```

A constant error is a calibration problem, not a resolution problem. Divide every
requested sleep by a host-measured constant (≈1.28 here, fitted over the range it
is applied to) and the mean error across the whole range drops from **27% to 3.4%**,
stable run to run. The usable floor falls from ~1 ms to **0.05 ms**:

```
target        mean       err   p99 err
 0.050ms   0.053ms      5.1%     22.7%   usable
 1.000ms   0.980ms      2.0%      0.8%   usable
```

### A sleep debt removes the floor as a cap on `k_time` entirely

Durations too small to express are **owed, not lost** — accumulated until the debt
clears the floor, then slept once. Crucially the ledger is settled against what was
*actually* slept, not what was asked for: open-loop, the calibration's residual
compounds across thousands of tiny sleeps (**6.8% median error, 16% worst**);
crediting the overshoot back so later sleeps absorb it gives **0.3% median, 2.0%
worst** over 30 samples.

```
per-call      calls  total want      got     err
 0.0100ms      2000      20.0ms   20.0ms    0.2%
 0.0010ms     20000      20.0ms   20.1ms    0.3%    <- 50x below the floor
```

Costs 50× below the floor total correctly to a fraction of a percent. **So the plan's
"time compression capped at ~100×" is false.** What a sub-floor cost loses is
per-call *observability*, not aggregate time: ordering is kept, placement inside
the debt window is not.

### Fault placement needs a dispatcher, not a ScheduledExecutorService

`ScheduledExecutorService` is biased late by an amount that itself moves with load
— +3.6 ms one run, +10.7 ms the next — so no fixed correction fits it. One
dispatcher thread targeting an **absolute** `nanoTime` deadline, parking coarsely
(with the calibration applied) and spinning the last 2 ms, is four thousand times
better:

```
                            bias        p50       p99
ScheduledExecutorService  +10.707ms  10.791ms  12.362ms
absolute dispatcher        +0.293ms   0.003ms   7.5ms      (median of 3 runs)
```

The p50 improves by **3,000×**. The p99 does not, and that matters: it is a single
sample in a hundred, and it is the OS rather than the dispatcher — with every core
saturated the dispatcher thread is simply descheduled, which no userspace scheduling
fixes. It is the standing cost of real threads on a real clock (D1).

At `k_time = 80`, a fault written at `90 refMs` therefore lands within **±0.3 refMs
typically** and **±600 refMs at worst**. A scenario whose lesson depends on a tight
timing margin is exposed to that tail and has to declare that it is.

> The calibration constant is host-specific and must be measured at startup. This
> is a second, independent reason for D6-C's host calibration.

## S3 — Is `io.grpc.Context` a usable ambient `Losim`?  **PASS**

All four behaviours D2 depends on:

| | |
|---|---|
| resolves inside a handler, naming the right machine | ✓ |
| absent on an unwrapped worker thread | ✓ |
| present when the executor is `Context.currentContextExecutor`-wrapped | ✓ |
| absent in a bare `main()`, with no fabricated machine | ✓ |

Wiring is `Contexts.interceptCall(Context.current().withValue(KEY, machine), …)`
in a `ServerInterceptor`. The D2 adapter shape — a `final` override of the
generated `StreamObserver` method delegating to an abstract value-returning
`Pairs map(Chunk)` — compiles and runs.

## S4 — Can telemetry replace a debugger?  **was 1.5 of 5 → now 15 of 15**

### What failed

Run against `build/wordcount.json`, a trace the pre-rewrite tree produced:

| question | verdict |
|---|---|
| What was `w3` holding at t=4.1 s? | **half** — recoverable only because no revealed value was ever overwritten |
| Which call was in flight? | **yes**, but only by set-subtracting `handler_end` from `rpc_call` across the whole prefix |
| Why did it stall 2.0–3.5 s? | **no** — zero events in the window |
| Whose memory came closest to its cap? | **no** — `memPeak` is a scalar in `meta`; `master`'s is 0 and `busyMs` is 0 for every machine |
| What made the reducer OOM? | **no** — no such event kind exists |

```
run ended at 10,338 ms
silent: 280 -> 5,135  and  5,135 -> 10,135
total:  9,855 ms — 95% of the run
```

**95% of that run produced no events at all**, including the entire five seconds the
system spent blocked on a dead machine's deadline. A change log is silent exactly
when a system is stuck, which is exactly when you want to look at it.

### The fix: three channels, not one

| | | answers |
|---|---|---|
| **events** | sparse, rich, one moment each | *what happened* |
| **spans** | intervals carrying a **parent** | *why it happened, and what was waiting* |
| **series** | dense numbers on a fixed cadence | *what everything held meanwhile* |

The cadence is chosen from the run's expected duration, so **trace size follows
duration, not busyness** — which is the whole point. Causality crosses the RPC
boundary in a `losim-parent-span` header, so a handler's span names the caller's
span on another machine: a distributed call stack.

Two things a real system would never do, and losim does deliberately:

- **Payloads are recorded.** Every call keeps its argument and its result, and a
  call that failed keeps the reason instead. A film of machines exchanging opaque
  byte counts teaches nothing.
- **Local computation is telemetrized too.** `Node.compute(label, body)` opens a
  span and marks the machine busy, so a master merging locally is not indistinguishable
  from a master doing nothing — which was precisely S4's unanswerable question.

### Re-run: 15 of 15

The five original questions, and five harder ones:

```
Q2  which call was in flight at t=4100ms?
    #15 rpc master -> w0   open since 1289ms  (unreachable)

Q3  why did it stall?  widest gap in events: 5258 -> 9206 (3948ms)
    open at 7248ms: #16 compute master  local merge for w0
    samples covering the gap: 324

Q4  whose memory came closest to its cap, and when?
    w2 reached 133.5% of its cap at t=9331ms

Q5  what made a reducer run out of memory?
    t=9379ms  w2  {resource=memory, capMb=4.0, demandMb=5.341,
                   cause=reduce accumulated 4 distinct keys}

Q6  how did this call come to happen?
    #14 phase    master  reduce
      #26 rpc      master  Worker.Reduce
        #27 handler  w5    Worker.Reduce

Q7  blocked on a core, or waiting on someone else?
    master at 6000ms: inflight=1 queued=0 busy=50%

Q10 what did each machine actually compute?
    w3     Worker.Map     {text=the cat and the dog} -> {counts={and=1, cat=1, dog=1, the=2}}
    master local merge                               -> {cat=1, mat=1, on=1, sat=1, the=2}
    w2     Worker.Reduce  {counts={a=2, and=1, ...}} -> FAILED: out of memory: 5.341MB of a 4.0MB machine
    11 of 11 handled calls carry an argument and either a result or a reason
```

### And it is smaller than what it replaced

Dense sampling looks expensive and is not, because most channels barely move — a
machine is alive for the whole run, idle for most of it, and its cap never changes
at all. Quantise each sample to the precision anyone will read, then store constant
channels once and flat stretches as runs:

```
events          83 items      8.2 KB
spans           27 items      5.5 KB
series      99,762 points   682.0 KB  raw
series                        2.8 KB  encoded   {constant: 46, runs: 45, raw: 0}
------------------------------------------------
TOTAL                        16.0 KB  (99.62% smaller)
```

**16 KB against the old trace's 22 KB** — richer, densely sampled throughout, and
still smaller. At 40 machines × 900 ticks the series cost 12.3 KB encoded, against
3.1 MB raw.

---

## S5 — Does the scaler engine beat a uniform factor?  **PASS — 7.4% against 109.5%**

The engine never sees the target run. It fits a ladder of small probes, projects,
and is then marked against ground truth. A uniform factor — what anyone would do
without an engine — is marked on the same run.

### The workload has to have the property under test

The engine's central claim is that resources scale with *different* exponents.
Testing that needs a workload where it is true, so the corpus is drawn from a Zipf
distribution, which produces Heaps' law. Uniformly random words would give β = 1
for everything and prove nothing:

| Zipf *s* | 1k | 4k | 16k | 64k | β |
|---|---|---|---|---|---|
| 1.0 | 3,899 | 12,137 | 34,464 | 85,407 | 0.743 |
| 1.5 | 543 | 1,425 | 3,412 | 8,280 | **0.653** |
| 2.0 | 126 | 242 | 513 | 1,017 | 0.506 |

`s = 1.5` is used, deliberately **not** the `s = 2.0` that lands on the β ≈ 0.5 this
plan assumed — so the engine has to fit the exponent rather than be handed it.

### It finds what each resource actually depends on

```
wireBytes    ~ records  ^ 0.912   (R2=1.000)
keys         ~ records  ^ 0.657   (R2=1.000)
peakMemMb    ~ records  ^ 0.633   (R2=0.999)
peakMemMb    ~ keys     ^ 0.963   (R2=0.998)   <- the structural claim (D6-A)
```

Memory is not really a function of records at all. It is a function of *distinct
keys*, which is itself a sublinear function of records — and finding that
intermediate variable is what makes the projection hold.

### Marked against a run that actually happened

Ladder 1k–8k, projected to 64k, then 64k was run:

| | actual | engine | err | uniform ×8 | err |
|---|---|---|---|---|---|
| wireBytes | 1,950,163 | 1,805,997 | **7.4%** | 2,177,200 | 11.6% |
| keys | 8,168 | 8,374 | **2.5%** | 16,992 | 108.0% |
| peakMemMb | 1.24 | 1.20 | **3.3%** | 2.59 | 109.5% |

**Worst error: engine 7.4%, uniform 109.5%.** The uniform factor more than doubles
the memory prediction — which is exactly how you provision the wrong machine.

### It refuses a workload that changes shape

A reducer that spills to disk above 250 keys, so memory stops growing mid-ladder:

```
records   peakMb
  1000     0.088
  2000     0.134
  4000     0.147     <- the threshold binds
  8000     0.147

whole ladder  beta=0.233 (R2=0.738)
lower half    beta=0.602
upper half    beta=0.000     -> REFUSED
```

R² does fall, but only to an ambiguous 0.738 — a merely noisy workload can score the
same, so no threshold on R² separates *bent* from *noisy*. **Splitting the ladder is
the unambiguous signal**: the two halves disagree about the exponent outright.

### Faults amplify demand, and a clean-only fit misses it

```
peak reducer memory: clean 0.324 MB, one machine lost 0.638 MB  (x1.97)
```

The survivor absorbs the dead machine's bucket. A model fitted only on clean runs
**under-predicts peak memory by half** — in the optimistic direction. The fault
dimension of the probe grid is therefore not optional.

### Time must be reconstructed, not multiplied

Fixed overhead is real at every size and *dominates* at small n, so a pure power law
is the wrong shape. Fitting `t = c + a·n^β` instead:

```
fitted        makespan = 1979ms fixed + records^0.699  (R2=0.833)
actual            8041 ms
reconstructed     9337 ms    16.1%
multiplied x8    33245 ms   313.4%
```

Multiplying a small run's wall clock multiplies its overhead too, which is why it
overshoots by 4×.

> **Makespan is the weakest projection here** — R² 0.833 against 0.998–1.000 for the
> resource fits. On this workload the per-record compute is small next to JVM and
> transport overhead, so wall clock is the noisiest thing measured. Timing
> projections should carry visibly wider error bars than volume or memory ones.

---

## S6 — Can losim measure what a machine *holds*?  **PASS**

S1 measures allocation exactly, but allocation counts garbage as well as survivors,
and **it is the survivors that decide an out-of-memory**. So this walks the object
graph from each machine's own roots — its registered services — and sums what is
reachable.

Two rules make it work without launcher flags, which matters because losim must run
identically in three places (D10):

- **application objects are reflected into** — they are in the unnamed module, so
  their fields are accessible without opening anything;
- **JDK containers are modelled, not reflected into** — `java.util` is not open, and
  `setAccessible` on `HashMap.table` throws. Modelling their layout avoids needing
  `--add-opens` at all. Measured: **0 refused references**.

### It agrees with the collector

| structure | GC says | walker says | error |
|---|---|---|---|
| `HashMap<String,Integer>` × 200k | 24.19 MB | 23.29 MB | 3.7% |
| `ArrayList<String>` × 300k | 20.99 MB | 19.45 MB | 7.4% |
| `byte[]` × 400 of 100 KB | 40.40 MB | 39.07 MB | 3.3% |
| `ConcurrentHashMap<String,long[]>` × 20k | 12.03 MB | 12.00 MB | 0.3% |

### Where it disagrees, and why the walker is right

40 × 1 MB arrays: walker **40.0 MB**, collector **80.2 MB** — exactly 2.00×. G1's
region size here is 2048 KB, so a 1 MB array is *humongous* and is given a whole
region. At 1 KB and 100 KB the same test shows 1.03×.

**That waste is this JVM's allocator, not the simulated system's data.** The scale
model wants object bytes, so the walker's number is the one to use — and a model of
real heap footprint would have to add allocator granularity back deliberately.

### Which is the entire point

Two reducers doing the same work, one keeping its answers:

```
accumulating reducer: allocated 21.9 MB, retains 20.6 MB
streaming reducer:    allocated 15.6 MB, retains  0.0 MB
-> allocation differs by 29%; retention differs by everything
```

Allocation cannot tell them apart. Retention can — and only one of them OOMs.

### Cost, and the boundary

- **~0.06 µs per object**; a 200k-object machine walks in ~13 ms. That is too slow
  for every tick at a 12 ms cadence, so the walk runs **every 8th tick** and carries
  the last figure forward between.
- A **boundary predicate** stops the walk at the machine's edge: its own plumbing is
  not its data, another machine's heap certainly is not, and gRPC's transport is
  shared — but the protobuf messages it holds *are* exactly its data. Verified: a
  neighbour's 16 MB stays out of the figure.

### What changed as a result

`Node` no longer takes the program's word for anything. Nothing declares a size; the
sampler walks the heap and **losim detects the out-of-memory itself**:

```
t=9417ms  w2  {resource=memory, capMb=4.0, demandMb=5.494, objects=13,
               cause=retained heap exceeded the machine}
```

---

## S7 — How much of what losim reports is losim?  **PASS**

losim records **on the machine's own threads**, so its allocation and its wall clock land on
exactly the counters being read. `getThreadAllocatedBytes` cannot tell losim's bytes from the
program's.

The danger is not that the overhead is large. It is that it distorts the *shape*: an overhead the
engine cannot separate from the workload becomes part of what the engine extrapolates.

### It is real, and it is 8.8%

Same workload, three telemetry levels:

| level | losim MB | raw alloc MB | reported alloc MB |
|---|---|---|---|
| OFF | 0.294 | 6.667 | 6.368 |
| NO_PAYLOAD | 0.303 | 6.675 | 6.370 |
| FULL | 0.894 | 7.254 | 6.371 |

Rendering payloads costs **~3× everything else losim does** — as expected, since it is the one
thing a real system would never do.

### Bracketing recovers it exactly

Every region of losim's own work is bracketed with `getThreadAllocatedBytes` and
`System.nanoTime()` and charged to a per-machine ledger, which `allocatedBytes()` subtracts.
Deliberately **not** via a helper taking a lambda: constructing the lambda would allocate against
the machine before the first mark is read.

```
unsubtracted, watching inflates the figure by up to 8.8%
with losim's own share taken off,               by 0.0%
```

### The test that actually matters

`keys`, `wireBytes` and walked heap are deterministic given the seed, so telemetry cannot move them
by construction — they are a **control**, not a result. Allocation is the one that can genuinely
drift, because losim's own bytes land on the same counter:

```
allocation exponent, unsubtracted: 0.897 off vs 0.878 watched   (drift 0.0188)
allocation exponent, as reported:  0.899 off vs 0.899 watched   (drift 0.0005)
```

**Being watched really does bend the fitted exponent, and subtracting removes the bend.** That is
the whole of D13, measured.

### One plan assumption falsified

The plan expected losim's overhead to be **flat per call**, so that it would land in the fixed term
`c` and be harmless. It is not flat — it fits `records^0.583`:

```
records   losim MB   metered regions
   1000      0.289                48
   8000      0.933                48
```

The call count is constant at 48 across the ladder, so this is **payload size, not call count**:
rendering cost follows the size of what it renders, which here follows the distinct key count.

It does not matter. The plan's fear was that an overhead tracking the data would be *inseparable*
from it, forcing probe runs to give up payload capture. Bracketing separates it exactly, whatever
shape it has — so **probe runs keep full payload capture**.

### The other half: calls the workload itself makes

The interceptors wrap a call and can be bracketed from outside it. `Losim.current().reveal(...)`
runs **inside** the handler, on the machine's own thread, between the very marks that measure it —
and it appears in the canonical service shape, so every service has one. Each such call therefore
**meters itself**.

**The exclusion is metered, not modelled, so it adapts to how much losim a program uses:**

| reveals per call | losim MB charged | metered regions | reported program MB |
|---|---|---|---|
| 0 | 0.258 | 48 | 6.370 |
| 1 | 0.260 | 56 | 6.370 |
| 10 | 0.282 | 128 | 6.371 |
| 100 | 0.495 | **848** | 6.382 |

Going from no instrumentation to a hundred calls per handler adds **1,667% more metered regions**
and charges **92% more** to losim, while the machine's reported allocation moves **0.2%**. Nothing
anywhere assumes a call count or a per-call constant.

**A bracket cannot fully see itself.** It reads the clock and the allocation counter twice, and the
first read of each pair happens before there is anything to charge it to. Measured: an empty
bracket costs ~76 ns and meters only ~8 ns of that, leaving **~68 ns per region unseen**. Calibrate
that once per JVM and charge it back per region, or losim's own instrumentation is billed to the
machine — and the heavier the instrumentation, the larger the error.

### The boundary losim cannot cross

In `reveal("keys", map.size())`, losim charges itself for what it does with the value and the
program for `size()`. An expensive argument is the program's cost, correctly — losim subtracts what
*it* does, never what the program did to produce what it was handed.

### The test that matters: does heavy instrumentation bend the *law*?

One size proves little. The engine fits an **exponent across a ladder**, and that is what gets
extrapolated — so the question is whether a heavily instrumented program fits the same law as a
bare one. At **1000 reveals per handler**:

| | exponent at 0 reveals | at 1000 reveals | bend |
|---|---|---|---|
| allocation, **raw** | 0.878 | **0.453** | 0.425 |
| allocation, **as reported** | 0.899 | 0.899 | **0.0000** |
| handler time, **raw** | 0.667 | 0.169 | 0.497 |
| handler time, **as reported** | 0.719 | 0.575 | 0.106 |

**Unmitigated, heavy instrumentation nearly halves the fitted exponent** — every projection built
on it would be wrong by orders of magnitude at scale. Excluded, the memory law is bit-for-bit
identical.

### One leak, found only by pushing to 1000

At 100 reveals the reported exponent bent by 0.041; the arithmetic said the leak was ~0.122 MB, and
8,000 boxed `Integer`s at 16 bytes is 0.122 MB. The cause was **autoboxing at the call site**:
`reveal(String, Object)` boxes the `int` *before* the bracket opens, so the box is charged to the
program even though it exists only because losim's parameter is an `Object`.

Primitive overloads — `reveal(String, int)` and friends — move the boxing inside the bracket. The
bend went from **0.041 to 0.0002**. This is invisible at one reveal per handler and fatal at a
thousand, which is exactly why the extreme case had to be run.

### How accurate is the metering itself?

200,000 reveals inside one handler:

```
actually took     64.52 ms   (322.6 ns each)
charged to losim  64.00 ms   (320.0 ns each)
UNMETERED          0.51 ms   (  2.6 ns each)  = 0.8% of the true cost
```

A bracket cannot fully see itself — it reads two counters twice and the first read of each pair
predates anything to charge it to. An empty bracket costs ~76 ns and meters ~8 ns of that.
Calibrate the difference once per JVM and charge it back per region.

### What is still not accounted for

losim can subtract what it can attribute to itself, not the second-order cost its garbage imposes
on the program. Measured here, that turned out **not** to be GC: 62 MB of extra allocation produced
**zero additional collections**, and direct cost accounted for 99.2% of the difference. So there is
no evidence of an indirect effect at this scale — which is not the same as proving there is none.

**The time law cannot be resolved on this workload, at any instrumentation level.** Refitting the
*same* uninstrumented workload on four independent seed sets gives exponents from **0.532 to
0.778** — a wobble of 0.246. The instrumented bend is 0.106, comfortably inside it. So the time
exclusion is implemented and demonstrably moves gross towards net (0.573 → 0.106), but whether
anything survives it cannot be answered here.

> Memory has no such problem: exact counters, R² 0.9990, bend 0.0000. **Timing exponents on short
> handlers are inherently imprecise**, which is a sharper version of D7's rule that timing
> projections carry wider error bars than volume or memory ones. The engine should refuse a time
> law whose ladder cannot beat the host's own jitter, rather than report it alongside a memory law
> as though the two were equally trustworthy.
