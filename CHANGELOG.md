# Changelog

What changed between releases, for somebody deciding whether to take one.

A version is what a lab resolves from Gradle, so it is a fact about a jar rather
than about a branch. Every release is cut from a tag whose name and `./VERSION`
are checked against each other before anything is built.

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
`git rm --cached` ;  **out of the index, not off the disk**. Every byte is still
there afterwards, so the conversion is one commit to revert.

By hand it is the block above plus the `losimToolchain` task, which is twenty
lines: [./losim and the toolchain file ->](/ref/cli-losim).

Then one edit to your Java, and it is a deletion: **every `@Takes` comes off, and
its numbers go into the scenario** ;  see below. Nothing else in a `.java` moves,
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
classpath now and **nothing** stops compiling ;  which is the promise to make to
somebody who arrives with a working gRPC system.

```yaml
takes:
  Mapper:  { Map: { refMs: 20 }, Note: { refMs: 1 } }
  Reducer: { Map: { refMs: 8 } }
```

Keyed by the class `runs:` names rather than by the rpc, because two implementations
of one rpc in one cluster must be able to cost different amounts ;  comparing two
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
;  it gets a note saying it is instant, which is what an unannotated handler always
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
| `build.sh` and ten more scripts | `losim` verbs; `losim dev ...` for the maintainer ones |
| the release zips | one asset, `losim.jar`, for the `adopt` bootstrap |

`build/classes` moved to `build/losim/classes`. Gradle's java plugin writes
`build/classes/java/main`, and losim wiped that directory before every run ;  so a
lab that is a Gradle project was deleting its own build output, and its editor's,
on every press of the arrow.

### D10, for a lab

A lab's classpath used to be jars committed to its repository, and now it is a
resolved graph. losim's own build still resolves nothing to compile the simulator,
so the rule survives where it was written ;  but a lab's classpath does not, and
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
`lib/pace.ts` exists for ;  a three-millisecond call held on screen long enough to
see a shape cross a gap ;  and it is a property of the **film**, which is the only
view that draws one. Overview, usage and cost inherited it for nothing.

The cost was not small. Held at a second a moment, a six-second run becomes a
436-second film ;  seventy times longer, about fourteen reference milliseconds a
second. Watching a memory line grow on the usage page took seven minutes while the
readout said `1x`. That reads as a broken page rather than a careful one.

So the film owns its clock and the transport it already had, and the console's
transport is the system timeline: linear, one reference second per second, `1x`
meaning what it says. The hold control goes with the pacing, to the film, since it
was a film control everywhere it appeared.

**The two timelines are now independent.** The cost page is no longer pinned to
the same instant as the picture two tabs away. That coupling was worth something;
it was not worth seven minutes to watch a five-second run.

### No revenue, no profit ;  including the part that said so

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
and hopelessly below the real total ;  600 refMs against `2 + 0.02` per record,
about 800 at forty thousand ;  timed out with every number looking reasonable and
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
slept and never subtracted ;  `@Takes` can make a run longer and never shorter ;  so
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
failed print the same thing, so there is nothing to compare ;  where a failure at
least announced itself. So the fallback now names what it ignored and what it
used instead, and says nothing at all when it honoured the declaration.

### A generated package may be called `docs`

The same bug 1.1.2 fixed, in the place nobody would have looked. `gen/` is
excluded from the walk over the lab root so javac is not handed it twice, and
then walked separately and compiled ;  which is why generated stubs always
compiled despite `gen` being on the list, and why "in NOT_CODE" was never the
same as "not compiled".

That second walk was still filtering `gen`'s own children by the furniture names.
Its children are packages `protoc` named from a `java_package`, so a schema
declaring itself in `docs` or `input` would have had its stubs skipped in the same
silent way ;  and been harder to diagnose, because nobody suspects the generated
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
not enough to stop it travelling: copy a working directory that has run Gradle ; 
which is exactly what marking a submission is ;  and it arrives holding absolute
paths from somebody else's laptop. A grading container honoured them and reported

    No protobuf compiler for linux-aarch_64 at /Users/.../protoc-osx-aarch_64

which reads as the submission failing to build, while the right binary sat unused
in `lib/bin`.

So a declaration is now checked before it is used. A declared tool that cannot be
executed here falls back to the vendored one for this platform. A declared
classpath none of whose entries exist here is not this machine's, and `lib/` is
used instead ;  one surviving entry is enough to accept it, because a build
part-way through is still the build's business and second-guessing a classpath
that mostly resolves would be worse than useless.

**This is robustness, not a permission check.** A grader that mounts a submission
should still delete the file rather than rely on this: losim cannot tell a stale
declaration from a deliberate one, and a classpath naming paths that do exist in
the container will be honoured. The file is a statement by the thing under
examination about what it should be measured with ;  reasonable for a lab, wrong
for a grader.

## 1.1.2

A Java package may be called `input`.

`NOT_CODE` names the furniture at a lab root ;  `build/`, `gen/`, `scenarios/`, a
data directory called `input/`. It was applied at **every depth** of the source
walk, which quietly made it a list of forbidden *package* names as well. A lab
that hands students a corpus generator at `src/input/Corpus.java` had that
package skipped without a word: 23 files compiled instead of 24, and the failure
arrived as

    Coordinator.java:201: error: package input does not exist

on the line that used it ;  which reads as the author importing something
imaginary rather than as the compiler being told not to look. Renaming to
`corpus` fails identically, because that is on the list too.

The names apply at the root now and nowhere below it, which is where the things
they name actually are: `build/` and `gen/` are both resolved against the root,
so the duplicate-classes problem the list was written for is untouched.
Dot-directories stay excluded at every depth ;  `.git` can be nested, and no
package can be called `.anything`.

**A reserved directory that holds Java now says so.** The root-only rule leaves a
narrower version of the same trap: a lab keeping its sources at the root rather
than under `src/` still has `input` reserved there. That skip is correct and it
was still invisible, so it is stated on the compile line instead of being
inferred from an error somewhere else. losim's own output ;  `gen/`, `build/` ; 
says nothing, because Java under `gen/` is Java losim put there, and a warning
that fires every run is a warning nobody reads.

### A price list is found inside the jar

`losim bill` looked for `lib/prices/<region>.yaml` and then `prices/<region>.yaml`
on disk. A lab that resolves losim from Maven has neither, because it has no
`lib/` ;  so it billed at the built-in defaults and said so in one line on stderr.
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
such a folder is generally *not a lab* ;  it holds the labs, and has no `lib/` and
no scenarios of its own ;  so `losim update` met it with "this is not a lab" and
stopped. The redirect was a dead end of this command's own making, found by
running the instruction rather than reading it.

Being a lab was never the right test. It is required to replace `lib/`, and has
nothing to do with replacing a viewer or a manual. So a root with no `lib/` is
now accepted when it holds a `viewer/` or `docs/` that losim published, and only
those are touched: outside a lab, a directory is replaced only if it carries the
stamp, or failing that has the shape of the thing it claims to be ;  `_next/` for
the export, `index.mdx` for the manual. A folder that merely has a directory
called `viewer/` in it is refused and left exactly as it was.

Such a root also no longer reports "this lab has an older lib/", which is how a
person starts looking for something that was never there.

`lib/` may be a symlink too, and was the one of the three that never checked. A
course that builds one `lib/` and points every lab at it ;  which is what a Gradle
assignment does ;  would have had that link replaced by a real directory on the
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
and after that were unreachable ;  a fix to either arrived only if a maintainer
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

The compilers are now published rather than shipped ;  one small archive per
platform, fetched only by a host that needs one and unpacked into the `lib/bin`
it already has. It is fetched again after any update that replaces `lib/`, and
it is explicitly *not* offered for committing: it belongs to one machine, and
putting it in a fork is the 36 MB the template exists to avoid.

### A lab can declare its toolchain instead of holding one

A lab that resolves losim with Gradle has no `lib/` and cannot sensibly be given
one: its jars are in a package cache, under names and versions the build chose.
Such a lab used to need an otherwise pointless `lib/` beside it purely so that
`Lab.isLab()` and `Lab.cp()` had something to look at ;  a directory that existed
to be found, holding a second copy of what the build had already resolved, and
free to disagree with it.

A build may now write `build/losim-toolchain.properties` instead:

    classpath=/.../losim-1.1.0.jar:/.../grpc-api-1.83.1.jar:...
    protoc=/.../protoc-osx-aarch_64.exe
    protoc-gen-grpc-java=/.../protoc-gen-grpc-java-osx-aarch_64.exe

Any key left out falls back to `lib/`, so a Gradle lab in a container can declare
its classpath and still use the vendored compilers. It is generated rather than
committed ;  it names absolute paths on one machine ;  and `build/` is already both
gitignored and excluded from a lab's own code.

### A timeout says what it was up against

`rpc_timeout` now carries `deadlineRefMs`, `declaredRefMs` and `unmeetable`, and
a run whose calls failed says so beside whether the job returned:

    t3.yaml  seed 1  completed in 496 refMs
      2 calls, 2 failed (timed out)
        lab.Worker.Map: the deadline was 200 refMs and the handler declares at least 500
        a deadline below the declared cost cannot be met on any host

Only the fixed part of the declared cost is claimed, because `refNsPerRecord` is
not knowable before the handler declares its count ;  so it is a lower bound, and
a deadline under even that is already impossible on any machine.

### Publishing no longer needs npm

The viewer export is committed precisely so that nothing downstream of a
developer needs npm, and `publish.sh` could still run a build. Three places
staged the committed export by hand; they now all call `viewer/stage.sh`.

### Fixes

- `t7` scripted its kill at 300 refMs, inside the 266-379 window the mappers
  dispatch in, so ~60 refMs of startup jitter decided whether a machine died
  holding work. Moved clear of it.
- `t11` asserted a speedup and was really measuring the host: eight workers sleep
  their declared costs concurrently anywhere, but the protobuf and gRPC around
  those sleeps need cores a two-core runner has not got, and the figure fell from
  3.10 to 1.29 as CI machines varied. It now asserts how the work was *divided* ; 
  80 chunks on the busiest machine at two workers against 20 at eight ;  which no
  host can move.
- Three assertions passed over empty collections and so proved nothing. Fixed and
  checked by pointing each at data that should fail it.

### Known ;  since measured, and settled

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
It is now 0.10, about 30% above the highest noise-only spread observed ;  and not
a weaker test than it sounds, since the regression it exists to catch halves a
fitted exponent, a move of about 0.42 on an exponent near 0.84.

The self-calibrating repair ;  compare the spread against the exponent's own seed
wobble ;  does not work, and the reason is recorded in the test: the spread is a
range over four independent runs while the wobble is one run's own, and CI
wobbles measure the same 0.032-0.036 as local ones, so the wobble does not grow
with the noise it would have to absorb.

## 1.0.0

First release. The simulator as a library: `publish.sh` writes it into an
assignment template, `losim update` replaces it afterwards, and the Maven tree is
published so a lab can resolve it with Gradle instead.
