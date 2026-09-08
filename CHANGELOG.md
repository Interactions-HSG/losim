# Changelog

What changed between releases, for somebody deciding whether to take one.

A version is what an assignment resolves from Gradle, so it is a fact about a jar
rather than about a branch. Every release is cut from a tag whose name and
`./VERSION` are checked against each other before anything is built.

## 3.0.1

**The first 3.x release that can be installed.** 3.0.0's build failed before it
published anything, so there is no 3.0.0 jar, there never was one, and its tag has
been removed rather than left pointing at a version nobody can install. Coming
from 2.x, you go straight here, and this is the release that carries the 3.0
break.

**Everything in the 3.0.0 section of
[CHANGELOG.md](https://github.com/Interactions-HSG/losim/blob/v3.0.1/CHANGELOG.md)
applies to this release.** It is a hard break: `losim.api.Job`, `Scalable`,
`Cluster` and `Input.Shape` are deleted rather than deprecated, `machines:` is
`nodes:`, `faults:` and `chaos:` are one `failures:` list written inside whatever
it happens to, `takes:` is `simulatedDuration:`, and `losim run` and `losim diff`
are refused with the new name printed. Read it before changing `losimVersion`. A
project pinned to 2.x keeps resolving and is untouched.

What 3.0.1 changes on top of that:

### A scaled run named a workload the file never mentioned

Above `scale: 1` the summary line printed the engine's ladder coordinate against the
simulation's own noun:

```
thumbs-scaled.yaml  seed 5  scaled 8,000 -> 40,000,000 units (x5,000)
```

`count:` is the workload in the word the file chose — frames, lines, orders. The
ladder's `units` are what handlers counted with `units(n)`, and the two are equal
only by coincidence; a handler reporting pixels per frame makes them differ by a
thousand. So a file saying `count: 24000000` reported a run of 40,000,000 of
something it never named.

The arithmetic underneath was right, and no projection or bill was affected — only
the line a reader checks their own file against. It now says what was run, in the
file's own word, and names the ladder coordinate separately:

```
thumbs-scaled.yaml  seed 5  scaled 4,800 -> 24,000,000 lines (x5,000)
  measured at 8,000 units — what the handlers counted with units(n), and what the
  laws below are fitted in
```

Every simulation in this repository sets `count = scale x 8000`, which is why
nothing caught it.

### The release notes now carry this file

A release page said only which version to write in `build.gradle.kts`. For a break
this size that is the wrong thing to be silent about, so the section for the version
being cut is published as the release body.

### Internal: the layout check no longer drifts

Nothing here reaches a project that depends on losim; it is recorded because it is
why 3.0.0's own gates were trusted when they should not have been.

`viewer/checks/parity.ts` froze its answer over `build/tests/traces`, which
`dev suite` rewrites — and a run is deliberately not reproducible. Two suite runs of
identical code differed in 75 of 75 sampled series channels and moved
`durationRefMs` by 121; all 22 traces differed. The check was green only until
somebody ran the suite, and its one repair made it green whether or not anything was
wrong. The input is committed now, so both halves are fixed and the comparison can
be exact again.

## 3.0.0

**There is nothing left that is not a service.**

An assignment is now exactly two things: **YAML** for the system, and **protobuf
plus the Java implementing it** for the code. The third surface — a driver object
with its own API, its own lifecycle and its own way of declaring how big the input
was — is gone.

This is a hard break. Nothing is deprecated and nothing is aliased, because two
spellings of one verb is how a vocabulary comes apart again a year later.

### The thing that starts the work is a gRPC service

losim ships `losim/job.proto`, and it is the whole of what losim asks of you:

```proto
service Job {
  rpc Load (Input)    returns (Workload);   // off the clock
  rpc Run  (Workload) returns (Result);     // the simulation
}
```

You implement it the way you implement any service — extend the `ImplBase` protoc
generated — and one node `runs:` it. `Load` reads the source or generates from the
seed with the clock stopped; `Run` is what is measured, and its handler span is the
trace's root.

`Input` and `Workload` are different types on purpose. `Run` receives only the
`Workload`, so it cannot read `input:`, cannot see the `source:`, and cannot tell a
probe run from the full one — the property every projection rests on, now stated in
the signature rather than asked for in the manual.

**Deleted:** `losim.api.Job`, `Scalable`, `Cluster`, `Cluster.Phase`, `Input`,
`Input.Shape`, `Input.Shape.Part`. Nine public types are two: `Losim.current()` and
`Spec`. `losim check` refuses `implements Job` by name and says what to write.

### `runs:` names a file, and the key says what it serves

```yaml
runs: { Thumbnailer: src/Shrinker.java }
```

One verb, and its value is a path rather than a class name. A path either exists or
does not, and saying so on its own line is the difference between a typo and a
build that ran for four minutes first. Five things are refused at load, before
anything is built: a value that is not `.java`, a file that is not there, a file
whose class does not match its name, a class that was not compiled, and a key that
disagrees with what the class actually serves.

`simulatedDuration:` is keyed on the same path, so there is one string per piece of
code and no second vocabulary for naming a class.

### Failures are written inside whatever they happen to

`faults:` and `chaos:` are one `failures:` list, nested in the node — or, for the
three new rpc-level kinds, in a `runs:` entry:

```yaml
w9:
  runs:
    Thumbnailer:
      file: src/Shrinker.java
      failures:
        Thumbnail:
          - { status: UNAVAILABLE, per: 20 calls }
  failures:
    - { kill: true, at: 400 refMs, restartAfter: 300 refMs }
```

A failure can no longer be aimed at a node that is not there, because there is no
name to get wrong — `partition:` is the exception, and says so. Every entry carries
`at:` (an instant) or `per:` (a rate) and never both.

`status:`, `slow:` and `drop:` are new: one bad replica of a service, bad while its
peers are fine, which is the partial failure every design handles worst.

### The words

| the thing | now | was |
|---|---|---|
| the YAML file | simulation | scenario |
| executing it | `losim simulate` | `losim run` |
| comparing two results | `losim compare` | `losim diff` |
| one computer | node | machine |
| where results go | `build/results/` | `build/runs/` |

`losim run` and `losim diff` are refused with the new name printed, not aliased.

### The trace is schema 4

The `machines` channel is `nodes`; `meta.job` is `meta.entry`; `job_failed` is
`failed`; the five span kinds are two, `rpc` and `handler`. `losim compare` reports
a schema difference first and alone, rather than a wall of differences with one
cause.

### Also

- `Losim.current()` gains `seed()` and `local()`, and `machine()` is `node()`.
  `local()` is a map owned by the node, shared by every service on it, emptied by a
  restart — free in time, charged in memory.
- Top-level keys: `seed`, `scale`, `nodes`, `input`, `network`, `retries`,
  `simulatedDuration`. Gone: `job:`, `mode:`, `tightMargin:`, `faults:`, `chaos:`,
  `machines:`, `takes:`.
- `input:` is a `source:`, a `unit:` and a `count:`. Omit the source and `Load`
  generates from the seed.
- A `simulatedDuration:` or `failures:` refusal now prints the file path as it was
  written. It read `src/Shrinker java Thumbnail` — a path with the dots replaced by
  spaces names no file anybody can open.

## 2.0.2

**`./losim run <scenario>` could not find the lab's own classes.**

```
no class called 'thumbs.Shrinker' is on the classpath to run as the job
```

The wrapper `losim adopt` writes runs on the classpath the build resolved, which is
the simulator and gRPC and deliberately **not** the project's own output — the task
that writes that classpath must not depend on compiling, because compiling needs
the generated sources that same task's output is read to produce. `--cp` then
defaulted to the JVM's own classpath, which is that same list. So the class the
scenario named was never anywhere, and the only way through was to type
`--cp build/losim/classes` yourself.

The arrow in the lab always worked, because the console passes `--cp` itself. That
is why this survived a release: every test exercised the path that already said it.

`--cp` now defaults to `build/losim/classes` when that directory is there, so
`./losim build && ./losim run thumbs.yaml` works. A project that has not been built
yet gets the refusal about the class, which is the true one, and it now says to
compile first.

Present since 1.5.0, which is when the wrapper was introduced.

## 2.0.1

**A text repair. No behaviour, no API and no scenario changes.**

2.0.0 shipped a manual whose punctuation had been flattened by a bulk pass over
the Markdown, and one file where the flattening was worse than cosmetic.

### `AGENTS.md` was not valid UTF-8

`losim/agents/AGENTS.md` is bundled in the jar and written into every project
`losim adopt` touches. The pass substituted at the byte level rather than the
character level, replacing bytes inside multi-byte sequences and leaving the rest
behind: `…` became `; ; \xa6`, `←` became `; \x86\x90`. Eleven bytes in that file
were not decodable at all, which is what a reader saw as `\ufffd\ufffd`.

If a lab took an `AGENTS.md` from 2.0.0, re-run `losim adopt . --force`, or take
the file from this release.

### The manual read as `;` where it meant an em-dash

Across `docs/`, `—` had become ` ;  `, `…` became `...`, `→` became `->`, and the
box drawing in the two-layers diagram, the interceptor diagram and the waterfall
example had been flattened to ASCII. The keyboard table told a reader to press
`->` and `<-` for the arrow keys.

Restored line by line against the last commit whose typography was intact: where a
line's letters and digits were unchanged and only its punctuation had moved, the
original line wins; where the words changed too, the rewrite stays. Every prose
edit made in the same pass is kept.

### The CLI now says what encoding it writes in

Separately, and true of every release so far: losim's own messages are UTF-8 in
the jar, but a JVM takes its console encoding from the environment. On a machine
with `LANG` unset — most CI runners, a good many containers — every em-dash
arrived as a question mark:

```
LC_ALL=C, 2.0.0:  has no way of being asked to do more ? its size is a constant
LC_ALL=C, 2.0.1:  has no way of being asked to do more — its size is a constant
```

`Main` installs UTF-8 streams before anything prints. This affects all output, not
only `adopt`.

### And one test that was asking a different question on a slow host

`Debugger`'s "why did it stall?" took the widest gap between events anywhere in
the trace. On a two-core runner the widest gap is the JVM waking up — 272 ms
between the scenario header and the first call — which is wider than the deadline
the question is about, has nothing open across it, and is not a stall. It now
measures silence from the first `rpc_call` onward. Reproduced under a saturated
host before and after: three runs, the real stall found every time.

## 2.0.0

**Every lab breaks, in two places, and both are one line each.** A workload's size
was in three different vocabularies and one of them was in your Java. It is one
vocabulary now, and it is in the scenario.

### The two edits

```bash
# 1. In every scenario: the per-unit cost key was renamed.
grep -rl refNsPerRecord scenarios/ | xargs sed -i '' 's/refNsPerRecord/refNsPerUnit/g'
```

An unknown key under `takes:` is refused at load with the line it is on, so this
one announces itself the first time you press run.

```java
// 2. In the job: cluster.records() and cluster.units() are gone.
public final class FillUp implements losim.api.Scalable {

    @Override public Input.Shape shape() {
        return Input.Shape.counting("items", "item").with("valueBytes");
    }

    @Override public void run(Cluster cluster, Input at) throws Exception {
        byte[] value = new byte[(int) at.value("valueBytes")];
        for (long i = 0; i < at.count("items"); i++) ...
    }
}
```

```yaml
input:
  items:      240      # a count: every count shrinks by one factor
  valueBytes: 65536    # a constant: shape rather than size, held at every rung
```

`losim adopt` and `losim check` find the old call and print this with the line it
is on, and the AGENTS.md they write carries the conversion as a numbered edit. See
[Scalable ->](/ref/api-scalable) and [input: ->](/ref/input).

### Why the job cannot hold the number

A size written into a constant cannot be swept and cannot be varied by an overlay.
Worse, it makes a direct run a **different amount of work** from the scaled run
that is meant to model it. `ITEMS_UNSCALED = 240` beside `scale x 8000` is two
workloads wearing one name.

So the job declares what its input is *made of*, and the scenario says how much.
The parts are named by the job, so a store writes `items`, a join writes `orders`
and `customers`, and nothing anywhere has to pretend a blob has records.

Above `scale: 1` a plain `Job` is now refused. A model of a bigger run is the same
job asked to do more, and a job that cannot be asked was never modelling anything.

### `records` is `units`

Three things were called records and only one of them ever was.

| was | is |
|---|---|
| `takes: { refNsPerRecord }` | `refNsPerUnit` |
| `Losim.current().records(n)` | `.units(n)` |
| `Cluster.records()` / `Cluster.units()` | deleted — the input arrives as a parameter |
| the fitted axis, the span field, `meta.scale.records` | `units` |

A blob store has no records, and neither does a sort. The engine's own axis is
`units`, the sum of an input's counts, and the scenario never needs the word at all, because each part carries the name the job gave it.

<Note>
**Old traces.** Nothing in the viewer reads either field, so they open as before.
`losim bill --diff` against a pre-2.0.0 trace reports the run size as `null`, and
the plan cache under `build/.losim-plans/` is regenerated on the next scaled run.
</Note>

### `fleet` is `cluster`

The API a job holds has been `Cluster` since the beginning and every page around it
said "fleet". No scenario key, trace field or class a lab can reach changes ;
`losim.runtime.Fleet` is internal and is now `Machines` — but the manual, the
console and every refusal message say one word.

### Also

- **The console writes `input:`.** The palette carries each `Scalable` class's
  `shape()`, so the form draws one row per part with the job's own noun beside it,
  and cannot write a part the job does not consume or leave out one it does.
- **`losim check` reads scenarios.** A scenario above `scale: 1` naming a plain
  `Job` is reported before anything is built.
- A test guard written as `if (sink.hashCode() == 42) fail("unreachable")` was
  reachable on about one run in seventy-six. Fixed, and measured rather than
  guessed at.

## 1.5.0

**Every lab with a committed `lib/` breaks, and this is how to fix one.** losim is
a Maven artifact and a CLI now. The jars, the trace viewer and this manual are one
dependency, and nothing is copied into a repository any more.

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://raw.githubusercontent.com/Interactions-HSG/losim/maven-repo/") }
}
dependencies { implementation("io.github.interactions-hsg:losim:1.5.0") }
```

### Convert a lab

```bash
java -jar losim.jar adopt .     # losim.jar from the release below
```

It writes `build.gradle.kts`, `./losim` and `AGENTS.md`, appends three lines to
`.gitignore`, and takes `lib/`, `viewer/` and `docs/` out of the index with
`git rm --cached` — **out of the index, not off the disk**. Every byte is still
there afterwards, so the conversion is one commit to revert.

By hand it is the block above plus the `losimToolchain` task, which is twenty
lines: [./losim and the toolchain file ->](/ref/cli-losim).

Then one edit to your Java, and it is a deletion: **every `@Takes` comes off, and
its numbers go into the scenario** — see below. Nothing else in a `.java` moves,
no `.proto` changes, and no scenario is rewritten apart from gaining that block.
The scenario grammar, the trace format, the Java API and every number a run
produces are what they were.

### Where the viewer and the manual went

Into the jar. `losim serve` and `losim serve docs` serve them from the classpath, so
a lab with nothing in it but a `build.gradle.kts` and a `src/` still opens both. A
`viewer/` on disk still wins if there is one, which is how this repository serves
its own.

That is also why there is no more `losim update`. It existed to replace three
directories a lab carried; a lab carries none of them.

```bash
./losim version --check     # is there a newer one?
```

One `HEAD` on the releases page and no token. Updating is then `losimVersion` in
`build.gradle.kts`, and nothing else moves.

### `@Takes` is gone, and its numbers are in the scenario

The annotation was the last losim symbol in a student's Java. Delete losim from the
classpath now and **nothing** stops compiling — which is the promise to make to
somebody who arrives with a working gRPC system.

```yaml
takes:
  Mapper:  { Map: { refMs: 20 }, Note: { refMs: 1 } }
  Reducer: { Map: { refMs: 8 } }
```

Keyed by the class `runs:` names rather than by the rpc, because two implementations
of one rpc in one cluster must be able to cost different amounts — comparing two
implementations is what the course is for. A key naming a method no placed service
serves is refused with the line it is on, so a renamed rpc stops the scenario
loading instead of silently costing nothing.

Every scenario in this repository carries the block, generated from the annotations
by reflection rather than transcribed, and the reference suite's numbers did not
move.

**In an existing lab this is the one thing you have to do by hand.** `losim.api.Takes`
no longer exists, so a handler that imports it does not compile: delete the import
and every `@Takes`, and put the same numbers under the class that serves the rpc in
each scenario that places it. A cluster that declares no cost anywhere is not refused
— it gets a note saying it is instant, which is what an unannotated handler always
was.

### A scenario that used to load can now be refused

One change here can refuse a scenario that worked before, and it is deliberate.

A **streaming** rpc, and an rpc whose marshaller is not protobuf, are refused when
the cluster starts, naming the method and the line the `runs:` was written on. Both
produce a number that looks right and is wrong: the per-record cost is slept once
per response message rather than once, and `Wire.sizeOf` returns 0 for a
non-`Message`, so every call is free on the wire and the bill silently undercounts
to zero. A wrong number that looks right is the one thing losim exists not to
produce.

Nothing in this repository declared a `stream`, so nothing here changed.

### What else went

| gone | what does it now |
|---|---|
| `losim update` | `./losim version --check`, then one line in the build file |
| `publish.sh`, `dist.sh` | a Maven coordinate |
| `lib/`, and `Lab`'s fallback to it | `build/losim-toolchain.properties`, which the build writes |
| `build.sh` and ten more scripts | `losim` verbs; `losim dev …` for the maintainer ones |
| the release zips | one asset, `losim.jar`, for the `adopt` bootstrap |

`build/classes` moved to `build/losim/classes`. Gradle's java plugin writes
`build/classes/java/main`, and losim wiped that directory before every run — so a
lab that is a Gradle project was deleting its own build output, and its editor's,
on every press of the arrow.

### D10, for a lab

A lab's classpath used to be jars committed to its repository, and now it is a
resolved graph. losim's own build still resolves nothing to compile the simulator,
so the rule survives where it was written — but a lab's classpath does not, and
that is worth saying rather than discovering.

The build `adopt` writes declares `dependencyLocking`. That declaration is inert
on its own: run it once, and commit what it writes.

```bash
gradle --write-locks losimToolchain     # then commit gradle.lockfile
```

The task has to be named. Gradle locks the configurations an invocation actually
resolves, so a bare `--write-locks` succeeds and writes nothing; that one covers
the classpath and both compilers.

A lab also needs the network once, where before it needed it never.

## 1.2.0

Two things a viewer sees, and one rename that follows from the first.

### The film keeps its own clock

The console had one clock for four views, and it was paced. Pacing is what
`lib/pace.ts` exists for — a three-millisecond call held on screen long enough to
see a shape cross a gap — and it is a property of the **film**, which is the only
view that draws one. Overview, usage and cost inherited it for nothing.

The cost was not small. Held at a second a moment, a six-second run becomes a
436-second film — seventy times longer, about fourteen reference milliseconds a
second. Watching a memory line grow on the usage page took seven minutes while the
readout said `1x`. That reads as a broken page rather than a careful one.

So the film owns its clock and the transport it already had, and the console's
transport is the system timeline: linear, one reference second per second, `1x`
meaning what it says. The hold control goes with the pacing, to the film, since it
was a film control everywhere it appeared.

**The two timelines are now independent.** The cost page is no longer pinned to
the same instant as the picture two tabs away. That coupling was worth something;
it was not worth seven minutes to watch a five-second run.

### No revenue, no profit — including the part that said so

The bill has four buckets and has had for some time, but the explanation of what
was removed was still on the Cost page, in the machine panel, in the ledger and
bill prose, and twice in the manual. The README went further and still *printed* a
revenue line in its example, above the words "five buckets".

All of it is gone, with nothing left in its place.

The ledger opens over the film with `ledger=1`.

## 1.1.4

A timeout the caller could not have predicted now says so.

1.1.0 taught `rpc_timeout` to carry the deadline and the declared cost, and to
say when one was below the other. That catches the crude mistake and misses the
one people actually make. The client can only compare a deadline with the
**fixed** part of a `@Takes`: `refNsPerRecord` needs a count, and the count is the
handler's to declare while it runs. So a deadline set comfortably above `refMs`
and hopelessly below the real total — 600 refMs against `2 + 0.02` per record,
about 800 at forty thousand — timed out with every number looking reasonable and
nothing said.

The callee knows both by the time it answers, and gRPC hands it the caller's
deadline, so it records them on its own span: `declaredRefMs`, `deadlineRefMs`,
and `unmeetable` when the first exceeds the second. The run summary reports it
beside the client-side line, once per method and never twice for one mistake:

    2 calls, 2 failed (timed out)
      lab.Worker.Map: the deadline was 590 refMs and the handler declares 802 once
        its records are counted
      a deadline below the declared cost cannot be met on any host

**Sound in one direction only, and that is the useful one.** A declared cost is
slept and never subtracted — `@Takes` can make a run longer and never shorter — so
it is a lower bound on what the handler actually took, and a declared cost above
the deadline is *impossible* rather than unlikely. The converse still says
nothing: a declared cost under the deadline leaves the handler's own work to come
on top of it, so a silent `unmeetable` is not a claim that a deadline is adequate.
It was already true of the 1.1.0 check and is worth repeating, because reading the
silence as an all-clear is the one way this can mislead.

Recorded in `close()` rather than when the answer is sent, because it has to hold
for a call that never answered: over the in-process transport the server is not
told the caller gave up, so it runs to completion and closes, and `close()` is the
one place reached whether the answer arrived in time, late, or not at all.

### The fallback says what it disregarded

1.1.3 made a declared toolchain fall back to `lib/` when it is not this machine's.
It did so silently, which is right for the run and wrong for the person.

The case that costs is the one that is hardest to diagnose anyway: two machines
disagreeing about the same lab, where the difference is a file one of them is
carrying. Silence makes the working machine and the machine that would have
failed print the same thing, so there is nothing to compare — where a failure at
least announced itself. So the fallback now names what it ignored and what it
used instead, and says nothing at all when it honoured the declaration.

### A generated package may be called `docs`

The same bug 1.1.2 fixed, in the place nobody would have looked. `gen/` is
excluded from the walk over the lab root so javac is not handed it twice, and
then walked separately and compiled — which is why generated stubs always
compiled despite `gen` being on the list, and why "in NOT_CODE" was never the
same as "not compiled".

That second walk was still filtering `gen`'s own children by the furniture names.
Its children are packages `protoc` named from a `java_package`, so a schema
declaring itself in `docs` or `input` would have had its stubs skipped in the same
silent way — and been harder to diagnose, because nobody suspects the generated
tree. This is a generalisation of the rule rather than a case that was reproduced
against a real schema.

### Checked

Checked in both directions by `t14`, which makes the same call twice against a
deadline it can meet and one it cannot. Identical declared cost, 802 refMs; one
refused at 598 allowed and one left alone at 1987. A check that fired on both
would be saying nothing.

## 1.1.3

A declared toolchain is a claim, and it has to check out.

`build/losim-toolchain.properties` is generated and never committed, and that is
not enough to stop it travelling: copy a working directory that has run Gradle —
which is exactly what marking a submission is — and it arrives holding absolute
paths from somebody else's laptop. A grading container honoured them and reported

    No protobuf compiler for linux-aarch_64 at /Users/…/protoc-osx-aarch_64

which reads as the submission failing to build, while the right binary sat unused
in `lib/bin`.

So a declaration is now checked before it is used. A declared tool that cannot be
executed here falls back to the vendored one for this platform. A declared
classpath none of whose entries exist here is not this machine's, and `lib/` is
used instead — one surviving entry is enough to accept it, because a build
part-way through is still the build's business and second-guessing a classpath
that mostly resolves would be worse than useless.

**This is robustness, not a permission check.** A grader that mounts a submission
should still delete the file rather than rely on this: losim cannot tell a stale
declaration from a deliberate one, and a classpath naming paths that do exist in
the container will be honoured. The file is a statement by the thing under
examination about what it should be measured with — reasonable for a lab, wrong
for a grader.

## 1.1.2

A Java package may be called `input`.

`NOT_CODE` names the furniture at a lab root — `build/`, `gen/`, `scenarios/`, a
data directory called `input/`. It was applied at **every depth** of the source
walk, which quietly made it a list of forbidden *package* names as well. A lab
that hands students a corpus generator at `src/input/Corpus.java` had that
package skipped without a word: 23 files compiled instead of 24, and the failure
arrived as

    Coordinator.java:201: error: package input does not exist

on the line that used it — which reads as the author importing something
imaginary rather than as the compiler being told not to look. Renaming to
`corpus` fails identically, because that is on the list too.

The names apply at the root now and nowhere below it, which is where the things
they name actually are: `build/` and `gen/` are both resolved against the root,
so the duplicate-classes problem the list was written for is untouched.
Dot-directories stay excluded at every depth — `.git` can be nested, and no
package can be called `.anything`.

**A reserved directory that holds Java now says so.** The root-only rule leaves a
narrower version of the same trap: a lab keeping its sources at the root rather
than under `src/` still has `input` reserved there. That skip is correct and it
was still invisible, so it is stated on the compile line instead of being
inferred from an error somewhere else. losim's own output — `gen/`, `build/` —
says nothing, because Java under `gen/` is Java losim put there, and a warning
that fires every run is a warning nobody reads.

### A price list is found inside the jar

`losim bill` looked for `lib/prices/<region>.yaml` and then `prices/<region>.yaml`
on disk. A lab that resolves losim from Maven has neither, because it has no
`lib/` — so it billed at the built-in defaults and said so in one line on stderr.
That is correct for Frankfurt, which is what the defaults are, and silently wrong
for anybody who asked for another region.

Every list in `prices/` is also a resource inside the jar, so it was never
actually missing. A named list is now read from there when no file of that name
is on disk. A file still wins: a list somebody wrote and put on disk is theirs,
and a built-in of the same name must not take precedence over it.

## 1.1.1

A shared viewer can be updated where it lives.

1.1.0 taught `losim update` to refuse a `viewer/` that is a symlink into a
directory several labs share, and to print the command to run instead. That
command did not work. It pointed at the folder the shared viewer lives in, and
such a folder is generally *not a lab* — it holds the labs, and has no `lib/` and
no scenarios of its own — so `losim update` met it with "this is not a lab" and
stopped. The redirect was a dead end of this command's own making, found by
running the instruction rather than reading it.

Being a lab was never the right test. It is required to replace `lib/`, and has
nothing to do with replacing a viewer or a manual. So a root with no `lib/` is
now accepted when it holds a `viewer/` or `docs/` that losim published, and only
those are touched: outside a lab, a directory is replaced only if it carries the
stamp, or failing that has the shape of the thing it claims to be — `_next/` for
the export, `index.mdx` for the manual. A folder that merely has a directory
called `viewer/` in it is refused and left exactly as it was.

Such a root also no longer reports "this lab has an older lib/", which is how a
person starts looking for something that was never there.

`lib/` may be a symlink too, and was the one of the three that never checked. A
course that builds one `lib/` and points every lab at it — which is what a Gradle
assignment does — would have had that link replaced by a real directory on the
first update, silently ending the sharing, and the host compiler written through
the link into a directory every other lab reads. Both are now refused with the
same redirect the viewer gets.

**The redirect assumes the shared directory is named `lib/`, `viewer/` or
`docs/`.** A course that calls it `build/losim-lib/` or `losim-docs/` gets a
correct refusal and a `--root` that will not find anything, because `--root`
names the folder a directory sits in and not the directory. There is no
invocation for that layout yet; refresh it the way your build already does.

## 1.1.0

Everything a lab carries from losim can now be replaced by `losim update`, and a
lab no longer has to keep a `lib/` directory it does not use.

### An update reaches the viewer and the manual

Until now `losim update` fetched one archive, `losim-lib.zip`, and replaced
`lib/`. The viewer and the manual were copied into a template by `publish.sh`
and after that were unreachable — a fix to either arrived only if a maintainer
re-ran `publish.sh` and committed, which is the manual step the update path
exists to remove. A scrubber that had learned to stop on a `heal` was a fix
nobody would ever see.

- `dist.sh` now cuts `losim-viewer.zip` (320 KB) and `losim-docs.zip` (210 KB)
  beside `losim-lib.zip`, from the same `publish.sh` that writes those
  directories into an assignment. One definition, so a lab that was published
  cannot differ from a lab that was updated.
- Three archives rather than one because a lab does not necessarily own all
  three. Several labs can share one viewer and one manual by symlink, and taking
  a new simulator must not rewrite what a neighbour is reading. A shared
  directory is refused and the command that would update it is printed.
- A viewer or manual that fails to download is reported and skipped once `lib/`
  is in place. A stale manual is a better outcome than a failed update.

### Each directory carries its own version

`lib/`, `viewer/` and `docs/` are stamped separately and asked separately.

They do not move together, and this release is the proof: a lab taking 1.1.0
with 1.0.0's updater gets a current jar beside a viewer from before viewers
could be updated. Asking only the jar would answer "up to date" to that lab
forever. Asking each in turn gets the viewer on the next run, without
re-downloading the 22 MB that is already correct.

### A protobuf compiler for the host

`lib/` ships Linux binaries only, deliberately: a fork is opened in a
devcontainer or a Codespace, and 36 MB of binaries nothing in the container can
execute would sit in every student's repository forever. The cost was that a Mac
outside a container had no compiler at all and `losim update` had nothing to
offer it.

The compilers are now published rather than shipped — one small archive per
platform, fetched only by a host that needs one and unpacked into the `lib/bin`
it already has. It is fetched again after any update that replaces `lib/`, and
it is explicitly *not* offered for committing: it belongs to one machine, and
putting it in a fork is the 36 MB the template exists to avoid.

### A lab can declare its toolchain instead of holding one

A lab that resolves losim with Gradle has no `lib/` and cannot sensibly be given
one: its jars are in a package cache, under names and versions the build chose.
Such a lab used to need an otherwise pointless `lib/` beside it purely so that
`Lab.isLab()` and `Lab.cp()` had something to look at — a directory that existed
to be found, holding a second copy of what the build had already resolved, and
free to disagree with it.

A build may now write `build/losim-toolchain.properties` instead:

    classpath=/…/losim-1.1.0.jar:/…/grpc-api-1.83.1.jar:…
    protoc=/…/protoc-osx-aarch_64.exe
    protoc-gen-grpc-java=/…/protoc-gen-grpc-java-osx-aarch_64.exe

Any key left out falls back to `lib/`, so a Gradle lab in a container can declare
its classpath and still use the vendored compilers. It is generated rather than
committed — it names absolute paths on one machine — and `build/` is already both
gitignored and excluded from a lab's own code.

### A timeout says what it was up against

`rpc_timeout` now carries `deadlineRefMs`, `declaredRefMs` and `unmeetable`, and
a run whose calls failed says so beside whether the job returned:

    t3.yaml  seed 1  completed in 496 refMs
      2 calls, 2 failed (timed out)
        lab.Worker.Map: the deadline was 200 refMs and the handler declares at least 500
        a deadline below the declared cost cannot be met on any host

Only the fixed part of the declared cost is claimed, because `refNsPerRecord` is
not knowable before the handler declares its count — so it is a lower bound, and
a deadline under even that is already impossible on any machine.

### Publishing no longer needs npm

The viewer export is committed precisely so that nothing downstream of a
developer needs npm, and `publish.sh` could still run a build. Three places
staged the committed export by hand; they now all call `viewer/stage.sh`.

### Fixes

- `t7` scripted its kill at 300 refMs, inside the 266–379 window the mappers
  dispatch in, so ~60 refMs of startup jitter decided whether a machine died
  holding work. Moved clear of it.
- `t11` asserted a speedup and was really measuring the host: eight workers sleep
  their declared costs concurrently anywhere, but the protobuf and gRPC around
  those sleeps need cores a two-core runner has not got, and the figure fell from
  3.10 to 1.29 as CI machines varied. It now asserts how the work was *divided* —
  80 chunks on the busiest machine at two workers against 20 at eight — which no
  host can move.
- Three assertions passed over empty collections and so proved nothing. Fixed and
  checked by pointing each at data that should fail it.

### Known — since measured, and settled

`t13-transparent` bounds the observer effect by requiring the fitted allocation
exponent to move less than 0.05 across four telemetry levels. That bound failed
about a third of the time on CI while never failing locally, and nothing on hand
distinguished "a real signal on slower hardware" from "a bound that was never
right".

It has since been measured. `tests/t13-null.sh` runs groups of four at **one
fixed telemetry level**, so a spread inside a group cannot be telemetry; the
distribution of those spreads is what the statistic does when nothing is moving
it. Over 283 groups on a CI runner:

    min 0.0030   median 0.0286   p90 0.0503   p99 0.0718   max 0.0768

**Eleven percent of groups exceed 0.05 with telemetry held constant.** The bound
was not measuring the observer effect there, it was measuring the noise floor.
It is now 0.10, about 30% above the highest noise-only spread observed — and not
a weaker test than it sounds, since the regression it exists to catch halves a
fitted exponent, a move of about 0.42 on an exponent near 0.84.

The self-calibrating repair — compare the spread against the exponent's own seed
wobble — does not work, and the reason is recorded in the test: the spread is a
range over four independent runs while the wobble is one run's own, and CI
wobbles measure the same 0.032–0.036 as local ones, so the wobble does not grow
with the noise it would have to absorb.

## 1.0.0

First release. The simulator as a library: `publish.sh` writes it into an
assignment template, `losim update` replaces it afterwards, and the Maven tree is
published so a lab can resolve it with Gradle instead.
