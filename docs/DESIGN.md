# Why it is built this way

## One thread runs at a time

Each VM is a virtual thread, but the kernel hands control to exactly one of them
and blocks until it hands control back:

```java
vm.permit.release();               // run this VM
kernel.permit.acquireUninterruptibly();   // and wait until it yields
```

There is never more than one runnable thread, so the JVM's scheduler never gets
a choice to make, and the run is deterministic without depending on anything the
platform promises. Two rules learned the hard way:

- **Use a two-way semaphore.** Unparking and then polling `Thread.getState()` is
  a race, and `LockSupport.park` may return spuriously.
- **Do not reach for the internal custom-scheduler constructor.** It works
  reflectively, but it needs `--add-opens`, is unsupported, and can break in any
  release — not acceptable for software students run on their own machines.

## Determinism is defended, not configured

The JVM offers many ways to be irreproducible, and each one would surface as *a
flaky grade for one student on one machine* — the worst failure mode a teaching
tool has. So `losim verify` reads the compiled bytecode and refuses:
`new Thread`, `CompletableFuture`, `parallelStream`, `System.nanoTime`,
`Math.random`, real file and socket access, and **any non-final static field**.

That last rule does double duty. A static is shared by every VM in the run, so
banning it is both the determinism guard and the isolation boundary — which is
why there is no ClassLoader-per-VM, and none of the class-init pinning and
metaspace trouble that would come with it.

## Memory is counted, not measured

`ThreadMXBean` returns `-1` for virtual threads, but that is not the reason.
Sampled allocation varies with JIT and GC, and crossing the cap raises
`OutOfMemory`, which changes control flow — so a *measured* number would make the
whole simulation irreproducible. Logical bytes are the same on every machine and
every run.

## Costs are declared

`@Cost(ms = 5)` rather than a profiler. Portable by construction, and the model
is visible rather than inferred, which is the better lesson anyway. The instance
type scales it: a `t3.micro` out of credits really is slower than an `m5.large`.

## The network does not pass objects around

gRPC's in-process transport hands the receiver the *same object reference* and
never serializes, so it would give no wire realism at all — and its executors
would break the handoff. Instead there is a plain in-memory bus, and the wire
size is the real encoded length. That is also why there are two codecs: the
schema-less one puts field *names* on the wire, protobuf puts field *numbers*,
and the difference is exactly what the schema buys.

## You cannot reconstruct a failure

A dropped message sends a VM down a code path that was never recorded, so there
is nothing to reconstruct from. `losim fork` re-executes instead: same seed, same
prefix byte for byte, different fault schedule — so branches are directly
comparable.

Loom continuations are one-shot and not cloneable, so this is the only way; it
is also the honest one.

## The pipeline is one-directional

`simulation → trace.json → shapes → player | video | bill`. Each stage is
testable on its own, and the Java side never imports the Python side or the
reverse. It is what lets a lecture video and a student's browser draw the same
picture from the same shapes.
