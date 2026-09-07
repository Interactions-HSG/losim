# losim, under a gRPC system that already works

You are working in a project that has a working gRPC Java system in it, and losim
has just been put underneath it by `losim adopt`. losim is a simulator for
decentralized systems: it runs **your** handlers, unchanged, on many simulated
machines over a simulated network, on one laptop, and tells you what the design
cost.

`losim adopt` moved files and wrote the build. **It did not touch a single
`.java`, on purpose** — guessing what a program means produces a system its author
did not write. That half is yours, and this file is how it gets done.

Read the section headed **This project** at the bottom first. It is what `adopt`
found *here*, with line numbers.

---

## What carries over, and what does not

**Untouched:** your `.proto`, the code protoc generates from it, and every class
extending a generated `XGrpc.XImplBase`. Your handler bodies are the point of the
exercise and nothing rewrites them.

**Gone:** `main`, `ServerBuilder`, `ManagedChannel`, ports, hostnames, TLS,
shutdown hooks, thread pools, `awaitTermination`. A machine has no command line
and no socket. The scenario places services on machines; the fleet builds every
server and every channel, because that is the only way a call can be timed,
priced, delayed, dropped and drawn.

One sentence to keep: **what carries over is your handlers and your schema.**
Everything that does not is about the transport, the channel or service discovery
— none of which losim models, because machines are named and found by what they
serve.

---

## The hard constraints

Each of these is a rule the trust verifier applies to your compiled code on every
run. Breaking one does not stop the run: it produces **a wrong number beside a
marker saying it is wrong**, per machine, in the trace. That is worse than a
crash, because it is quiet.

| | |
|---|---|
| **Build no channel or server** | `ManagedChannelBuilder`, `Grpc.newChannelBuilder`, `ServerBuilder.forPort`. A peer is found by what it serves: `cluster.channelTo(name)` and `Losim.current().channelTo(name)`. A channel you built carries no interceptor, so its calls are absent from the wire, the bill and the film. |
| **Start no threads of your own** | a `new Thread`, an executor you created, `directExecutor()`, a virtual thread. Work on a thread the machine did not create belongs to no machine, so its memory and its time land nowhere. Use `Losim.current().submit(…)`. |
| **Do not sleep in host milliseconds** | `Thread.sleep` is the one duration `k_time` never touches, so at a compression of forty it is forty times too long. `Losim.current().sleep(refMs)` is reference time, like every other duration. |
| **Do not read the real clock** | `System.nanoTime`, `System.currentTimeMillis`, `Instant.now`. The simulated clock is the one every figure is against. |
| **Do not touch a real disk** | `Files.write`, `FileOutputStream`. `Losim.current().wroteDisk(bytes)` is the disk model, and it is what fills up and refuses. |
| **Do not reach outside the JVM** | `Runtime.exec`, `InetAddress`, a real socket. Every machine would answer with the host's identity, which is a lie about isolation. |
| **Hold no mutable static state** | a static collection shared by every machine is one machine pretending to be many. Instance fields are per-machine and are what a machine "remembers". |
| **Unary rpcs only** | losim prices a call as one request and one response. A stream is refused at load, by name, with a line number. |
| **A service is a top-level class with a no-argument constructor** | `runs:` names a class; the fleet builds a fresh one when a machine restarts, and a nested class is walked together with the class enclosing it. |

---

## The edits, in order

### 1. Split each handler out of its bootstrap

The quickstart's handler is a **static nested class inside the server**:

```java
public class HelloWorldServer {                 // ← the bootstrap, all of it dead
  private static final Logger logger = …;
  public static void main(String[] args) { … }  // a machine has no command line
  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {   // ← this is yours
    @Override public void sayHello(HelloRequest req, StreamObserver<HelloReply> out) { … }
  }
}
```

Move `GreeterImpl` to `src/…/Greeter.java` as a **top-level, public, final class
with a no-argument constructor**, and delete the file it came out of.

Leaving it nested is not enough: the verifier walks a nested class together with
its enclosing class, so every run would report the bootstrap's own server and
statics against your service and mark the wire untrustworthy.

### 2. Your client is your job in disguise

The client is the thing that drives calls, which is what a `Job` is:

```java
public final class MyJob implements losim.api.Job {
    @Override public void run(Cluster cluster) throws Exception {
        String peer = cluster.serving("Greeter").get(0);      // by service, never by address
        var stub = GreeterGrpc.newBlockingStub(cluster.channelTo(peer));
        var reply = stub.sayHello(HelloRequest.newBuilder().setName("world").build());
        cluster.done(reply.getMessage());
    }
}
```

Three things go: the channel builder, `"localhost:50051"`, and `shutdownNow()`.
Name the job in the scenario's `job:` key.

### 3. Fill in what each call costs

The scenario `adopt` wrote has a `takes:` block with every rpc at `refMs: 0`.
**Leaving it at zero is the edit most often skipped, and skipping it makes every
number meaningless**: every call is instant, so there is no queueing, no
contention, no deadline pressure and no critical path, and the run finishes in
almost no time and costs almost nothing.

```yaml
takes:
  Greeter:
    SayHello: { refMs: 3, refNsPerRecord: 240000 }
```

`refMs` is time on a reference machine — two vCPUs, running alone. Nothing
measures this for you and nothing can: at the size a laptop can hold, your handler
is genuinely instant, because the workload is shrunk and the host's CPU is not.
Declare what the work would cost at full size.

If a handler's cost depends on how much it was given, call
`Losim.current().records(n)` in it and use `refNsPerRecord`.

### 4. Declare which rpcs are idempotent

```proto
rpc SayHello (HelloRequest) returns (HelloReply) {
  option idempotency_level = IDEMPOTENT;
}
```

No retry policy can attach to an rpc the schema will not vouch for. A scenario can
override that with `unsafe: true`, which is the point: it makes "we retry a call
that is not safe to run twice" one visible line in a diff.

---

## The adapter, if you want handlers you can unit-test

grpc-java's generated method is `void sayHello(HelloRequest, StreamObserver<HelloReply>)`.
A twelve-line base turns it into a value-returning method, which is the difference
between a handler you can call from a plain JUnit test and one you cannot:

```java
public abstract class GreeterBase extends GreeterGrpc.GreeterImplBase {
    protected abstract HelloReply sayHello(HelloRequest request);

    @Override public final void sayHello(HelloRequest r, StreamObserver<HelloReply> out) {
        try {
            out.onNext(sayHello(r));
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }
}
```

Optional. Nothing in losim requires it.

---

## How to check the work, and when to stop

```
./losim check     the same findings this file was written from, re-run
./losim build     generate from the schema and compile
./losim run scenarios/1-one-call.yaml
```

Then **read the trust markers at the end of the run**. They name what is left, by
machine and by rule, so this file does not have to be trusted to be complete:

```
trust: 2 machines report figures that do not mean what they say
  w0  built its own channel or server, which no interceptor is attached to
```

You are done when `./losim check` finds nothing, the run completes, and no machine
is flagged.

---

## What not to invent

losim refuses what it cannot account for, and a refusal names a line. Do not add
scenario keys, a shrink factor, a `k_time`, an "expected runtime" or a records
count to make something pass — the engine derives all of those, and a projection
that only appears because a check was loosened is worth less than no projection.

If a projection is refused, that is an answer about the design. Read
`/run/when-it-refuses` before trying to make it go away.

---
