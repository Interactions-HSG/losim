# dissaly, under a gRPC system that already works

You are working in a project that has a working gRPC Java system in it, and dissaly
has just been put underneath it by `dissaly adopt`. dissaly is a simulator for
decentralized systems: it runs **your** handlers, unchanged, on many simulated
nodes over a simulated network, on one laptop, and tells you what the design
cost.

`dissaly adopt` moved files and wrote the build. **It did not touch a single
`.java`, on purpose** — guessing what a program means produces a system its author
did not write. That half is yours, and this file is how it gets done.

Read the section headed **This project** at the bottom first. It is what `adopt`
found *here*, with line numbers.

---

## An assignment is two things

**YAML**, which is the distributed system: its nodes, what each one runs, what
goes wrong, and how big the workload is. **Protobuf and the Java implementing
it**, which is all of the code. There is no third thing — no driver object, no
interface to implement, no class named in a key. What starts the work is a gRPC
service like any other, and dissaly ships its schema.

```proto
service Job {
  rpc Load (Input)    returns (Workload);   // off the clock, before anything is measured
  rpc Run  (Workload) returns (Result);     // the simulation. It ends when this returns.
}
```

---

## What carries over, and what does not

**Untouched:** your `.proto`, the code protoc generates from it, and every class
extending a generated `XGrpc.XImplBase`. Your handler bodies are the point of the
exercise and nothing rewrites them.

**Gone:** `main`, `ServerBuilder`, `ManagedChannel`, ports, hostnames, TLS,
shutdown hooks, thread pools, `awaitTermination`. A node has no command line and
no socket. The simulation places files on nodes; dissaly builds every server and
every channel, because that is the only way a call can be timed, priced, delayed,
dropped and drawn.

One sentence to keep: **what carries over is your handlers and your schema.**
Everything that does not is about the transport, the channel or service discovery
— none of which dissaly models, because nodes are named and found by what they
serve.

---

## The hard constraints

Each of these is a rule the trust verifier applies to your compiled code on every
simulation. Breaking one does not stop it: it produces **a wrong number beside a
marker saying it is wrong**, per node, in the trace. That is worse than a crash,
because it is quiet.

| | |
|---|---|
| **Build no channel or server** | `ManagedChannelBuilder`, `Grpc.newChannelBuilder`, `ServerBuilder.forPort`. A peer is found by what it serves: `Dissaly.current().peersServing(name)` and `Dissaly.current().channelTo(peer)`. A channel you built carries no interceptor, so its calls are absent from the wire, the bill and the film. |
| **Start no threads of your own** | a `new Thread`, an executor you created, `directExecutor()`, a virtual thread, `parallelStream`. Work on a thread the node did not create belongs to no node, so its memory and its time land nowhere. Concurrency comes from **calling more nodes** — fan out with an async stub and wait on a latch — and from a node serving several calls at once on its own pool. |
| **Do not sleep in host milliseconds** | `Thread.sleep` is the one duration the compressed clock never touches, so at a compression of forty it is forty times too long. `Dissaly.current().sleep(refMs)` is reference time, like every other duration. |
| **Do not read the real clock** | `System.nanoTime`, `System.currentTimeMillis`, `Instant.now`. The simulated clock is the one every figure is against. |
| **Do not touch a real disk** | `Files.write`, `FileOutputStream`. `Dissaly.current().wroteDisk(bytes)` is the disk model, and it is what fills up and refuses. |
| **Do not reach outside the JVM** | `Runtime.exec`, `InetAddress`, a real socket. Every node would answer with the host's identity, which is a lie about isolation. |
| **Hold no mutable static state** | a static collection shared by every node is one node pretending to be many. Instance fields are per-node and are what a node "remembers"; `Dissaly.current().local()` is the store every service on one node shares, and a restart empties it. |
| **Unary rpcs only** | dissaly prices a call as one request and one response. A stream is refused at load, by name, with a line number. |
| **One class per file, named after it** | a `runs:` value is a path, and the class it loads is the one that file is named after. A nested service has no line anybody could write, so it is refused. dissaly builds a fresh instance when a node restarts, so it needs a public no-argument constructor. |

---

## The edits, in order

### 1. Split each handler out of its bootstrap

The quickstart's handler is a **static nested class inside the server**:

```java
public class HelloWorldServer {                 // ← the bootstrap, all of it dead
  private static final Logger logger = …;
  public static void main(String[] args) { … }  // a node has no command line
  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {   // ← this is yours
    @Override public void sayHello(HelloRequest req, StreamObserver<HelloReply> out) { … }
  }
}
```

Move `GreeterImpl` into `src/…/GreeterImpl.java` as a **top-level, public, final
class with a no-argument constructor**, and delete the file it came out of.

Leaving it nested is not a lesser version of this edit — it is refused. `runs:`
names a file and loads the class that file is named after, so a nested class is
one nothing can place. (Even if it could: the verifier walks a nested class
together with its enclosing class, so every simulation would report the
bootstrap's own server and statics against your service.)

### 2. Your client is the Job, and the Job is a service

The client is the thing that drives calls. That is what `dissaly.Job` is, and its
two rpcs are the split between reading your input and doing the work:

```java
public final class MyJob extends dissaly.pb.JobGrpc.JobImplBase {

    // Off the clock. Read `in.getSource()`, or generate from the seed when it is
    // empty. Nothing spent here is charged to anybody, because reading your input
    // is not part of the design being measured.
    @Override public void load(dissaly.pb.Input in, StreamObserver<dissaly.pb.Workload> out) {
        Frames frames = build(in.getCount(), dissaly.api.Dissaly.current().seed());
        out.onNext(dissaly.pb.Workload.newBuilder()
                .setCount(frames.getItemCount())     // what you actually produced
                .setType(Frames.getDescriptor().getFullName())
                .setPayload(frames.toByteString())
                .build());
        out.onCompleted();
    }

    // The simulation. It starts when this is called and ends when it returns.
    @Override public void run(dissaly.pb.Workload work, StreamObserver<dissaly.pb.Result> out) {
        var here = dissaly.api.Dissaly.current();
        Frames frames = Frames.parseFrom(work.getPayload());

        String peer = here.peersServing("helloworld.Greeter").get(0);   // never an address
        var stub = GreeterGrpc.newBlockingStub(here.channelTo(peer));
        var reply = stub.sayHello(HelloRequest.newBuilder().setName("world").build());

        out.onNext(dissaly.pb.Result.newBuilder()
                .putAnswer("greeting", reply.getMessage())
                .build());
        out.onCompleted();
    }
}
```

Three things go from the old client: the channel builder, `"localhost:50051"`,
and `shutdownNow()`. Name the file in the `dissaly.Job` entry of some node's
`runs:` — exactly one node in the file has one.

**`Run` cannot see `input:`, and that is deliberate.** It is handed a `Workload`
and nothing else: not the source, not the unit, not which size it is doing. A Job
that could tell a probe run from the full one could behave differently at the two,
and then the projection would be a projection of nothing.

### 3. Fill in what each call costs

The simulation `adopt` wrote has a `simulatedDuration:` block with every rpc at
`fixed: 0 refMs`. **Leaving it at zero is the edit most often skipped, and
skipping it makes every number meaningless**: every call is instant, so there is
no queueing, no contention, no deadline pressure and no critical path, and the run
finishes in almost no time and costs almost nothing.

```yaml
simulatedDuration:
  src/GreeterImpl.java:
    SayHello: { fixed: 3 refMs, perUnit: 240000 refNs }
```

Keyed on the **file**, so one string names one piece of code, and two nodes
running two implementations of one service are two prices.

`refMs` is time on a reference machine — two vCPUs, running alone. Nothing
measures this for you and nothing can: at the size a laptop can hold, your handler
is genuinely instant, because the workload is shrunk and the host's CPU is not.
Declare what the work would cost at full size.

If a handler's cost depends on how much it was given, call
`Dissaly.current().units(n)` in it and give the rpc a `perUnit:`.

### 4. The size of the workload lives in the file, not in the Java

Whatever constant said how much work there was comes out of the Java and goes
into `input:`. It is one number, because there is one number the engine varies:

```yaml
input:
  source: data/frames/   # a file, a folder, or left out entirely
  unit:   frame          # what one of them is called, singular
  count:  30000          # at full size. The engine shrinks this and nothing else.
```

`unit:` is the same word `Dissaly.current().units(n)` counts and `perUnit:` prices.
Leave `source:` out and `Load` generates the workload from
`Dissaly.current().seed()` — reproducible from the seed, different across a sweep,
and free, because `Load` is off the clock.

A count that stays in the Java is a design that cannot be run at another size, so
the scale engine has nothing to turn and `scale:` above 1 is refused.

### 5. Declare which rpcs are idempotent

```proto
rpc SayHello (HelloRequest) returns (HelloReply) {
  option idempotency_level = IDEMPOTENT;
}
```

No retry policy can attach to an rpc the schema will not vouch for. A simulation
can override that with `unsafe: true`, which is the point: it makes "we retry a
call that is not safe to run twice" one visible line in a diff.

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

Optional. Nothing in dissaly requires it. Note that the abstract base is not what a
node runs — `runs:` names the concrete file.

---

## How to check the work, and when to stop

```
./dissaly check     the same findings this file was written from, re-run
./dissaly build     generate from the schema and compile
./dissaly simulate simulations/1-one-call.yaml
```

Then **read the trust markers at the end**. They name what is left, by node and by
rule, so this file does not have to be trusted to be complete:

```
trust: 2 nodes report figures that do not mean what they say
  w0  built its own channel or server, which no interceptor is attached to
```

You are done when `./dissaly check` finds nothing, the simulation completes, and no
node is flagged.

---

## What not to invent

dissaly refuses what it cannot account for, and a refusal names a line. Do not add
keys, a shrink factor, a clock compression, an "expected runtime" or a unit count
to make something pass — the engine derives all of those, and a projection that
only appears because a check was loosened is worth less than no projection.

If a projection is refused, that is an answer about the design. Read
`/run/refusals` before trying to make it go away.

---
