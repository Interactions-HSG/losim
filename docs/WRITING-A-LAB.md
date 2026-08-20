# Writing a lab

Everything a program can reach lives in `losim.api`. That is the whole surface;
there is deliberately nothing else, because a program that could read the wall
clock or start a thread would not be reproducible.

## A program

```java
import losim.api.*;

public final class Hello implements Program {

    private int seen;                       // instance state: this VM's memory

    @Override public void main(Ctx ctx) {   // optional: only if it takes initiative
        if (ctx.isOrigin()) ctx.send(ctx.next(), new Token("hello", 0));
    }

    @OnMessage                              // dispatched by the message type
    public void pass(Ctx ctx, VmRef from, Token msg) {
        seen++;
        ctx.reveal("seen", seen);           // shows up in the trace and the video
        ctx.send(ctx.next(), new Token(msg.text(), msg.hops() + 1));
    }
}
```

**`main` is optional.** A program that only reacts — a pure server — needs none.
Three places code runs, and they mean different things:

| Where | When | What belongs there |
|---|---|---|
| instance fields | — | this VM's state |
| `main` | once, at boot | *initiative* |
| handlers | per event | *reaction* |

**Static fields are rejected at build time.** A static is shared by every VM in
the run, so it is both a determinism hole and a correctness lie. Put state in
instance fields.

## Two message styles

**Style A — no schema.** A message is a record; a handler is picked by its type.

```java
public record Token(String text, int hops) {}
```

**Style B — protobuf first.** Write the contract, then implement it. `losim gen`
turns one `.proto` into three things: records, a *server* interface (takes a
`Ctx`, because the callee needs its own context) and a *peer* interface (does
not, because a caller has no business supplying it).

```proto
service Mapper { rpc Map (Chunk) returns (Pairs); }
```

```java
public final class Mapper implements Program, MapperService {
    @Override @Cost(ms = 2)
    public Pairs map(Ctx ctx, Chunk request) { ... }
}
```

Implementing the generated interface is what makes the cross-machine contract a
**compile error** rather than a runtime surprise. Calling is typed too:

```java
for (MapperPeer w : ctx.peers(MapperPeer.class)) {
    Pairs p = ctx.within(Duration.ofMillis(800), () -> w.map(chunk));
}
```

## Failure

**Nothing tells you a VM is dead.** `ctx.peers(...)` returns the *configured*
fleet, not the live one, and there is no `isAlive`. Liveness is inferred from a
timeout — because that is the real condition, and handing it over free would
make the exercise teach nothing.

A dead VM and a slow VM are indistinguishable from outside. That is why `freeze`
exists next to `kill`: a frozen VM eventually answers, so giving up too early
duplicates work and giving up too late stalls the job. Tuning that is the
exercise.

```java
try {
    Pairs p = ctx.within(Duration.ofMillis(800), () -> w.map(chunk));
    if (work.done(i)) collect(p);        // false => someone already redid it
} catch (Faults.Timeout | Faults.Unreachable e) {
    work.requeue(i);                     // and now you have duplicate execution
}
```

Only *graceful* deaths get a hook. `@OnTerminate` fires for a spot reclaim and
never for a kill, which is exactly why correctness cannot rest on it.

## Huge workloads

`Data` describes a dataset instead of building one, so a lab can run a terabyte
on a laptop. The framework charges time to process it, bytes to move it and
memory to hold it — so a shard larger than the machine raises `OutOfMemory`
exactly as it would in reality.

```java
Data corpus = Data.gigabytes("corpus", 1000, 200);   // 1 TB, never materialised

ctx.hold(batch);        // OutOfMemory if it does not fit
ctx.process(batch, 12); // 12 ns per record, scaled by this machine
ctx.release(batch);
ctx.spill(emitted);     // NoSpace if the local disk is too small
```

Sending a `Data` moves it and is billed byte by byte. Sending `data.ref()` tells
a worker which shard it already holds, and costs nothing — which is what "move
the computation to the data" actually means.

## The annotations, in full

| | |
|---|---|
| `@OnMessage` | Style A inbound handler, dispatched by parameter type |
| `@OnTimer(everyMs = …)` | fires on a virtual-clock schedule |
| `@OnTerminate` | graceful shutdown only — never a kill |
| `@Cost(ms = …)` | declared execution cost, portable across machines |

There is no `@Rpc` (implement the generated interface), no `@Visual` (every
field write is already recorded) and no `@Combiner` (a combiner is a `Reducer`
placed on the mapper's VM).

## Invariants

A predicate is code, so a scenario only names the class.

```java
public final class NoLostWords implements Invariant {
    @Override public void check(RunResult run) {
        if (run.output() == null) throw new Violation("the job never finished");
        ...
    }
}
```

`RunResult` gives you the output, the metrics, every trace event, and the bill.
