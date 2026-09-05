# Changelog

What changed between releases, for somebody deciding whether to take one.

A version is what `losim update` compares against and what a lab resolves from
Gradle, so it is a fact about a jar rather than about a branch. Every release is
cut from a tag whose name and `./VERSION` are checked against each other before
anything is built.

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

### Known

`t13-transparent` bounds the observer effect by requiring the fitted allocation
exponent to move less than 0.05 across four telemetry levels. That bound was set
on a fast machine and is marginal on a slow one: it fails about a third of the
time on two-core CI runners while running 0.006–0.028 locally. It is left as it
is rather than widened to something equally unjustifiable, and the test now
prints the four exponents and each law's own seed wobble on every run, so the
next failure carries the evidence that would set a defensible bound.

## 1.0.0

First release. The simulator as a library: `publish.sh` writes it into an
assignment template, `losim update` replaces it afterwards, and the Maven tree is
published so a lab can resolve it with Gradle instead.
