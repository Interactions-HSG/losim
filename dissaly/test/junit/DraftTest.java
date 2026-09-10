import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import dissaly.cli.Draft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an existing simulation looks like to the authoring form — and what it
 * refuses rather than shows with something quietly missing.
 *
 * <p>No lab and no compile: every case here is a plain string in, a record or a
 * refusal out. The paths under {@code runs:} are this repository's own, because
 * the loader does stat them — a path is a thing that either exists or does not,
 * and saying so on its line is the whole reason a simulation names code by path.
 * What the loader still does not do is load anything: whether that file's class
 * compiles, and whether it implements the service it is filed under, are a run's
 * questions and are asked where there is a bound server to answer them.
 */
class DraftTest {

    // -------------------------------------------------------------- round trip

    @Test
    @DisplayName("a pool of one keeps the pool's own name, and needs no count or prefix")
    void poolOfOne() {
        var d = Draft.of("main.yaml", """
                nodes:
                  master:
                    instance: m5.large
                    zone: eu-central-1a
                """);
        assertEquals("main", d.name());
        assertEquals(1, d.pools().size());
        var p = d.pools().get(0);
        assertEquals("master", p.name());
        assertEquals(1, p.count());
        assertEquals("m5.large", p.instance());
        assertEquals(java.util.List.of("eu-central-1a"), p.zones());
        assertTrue(p.runs().isEmpty());
    }

    @Test
    @DisplayName("a pool dealt over three zones, running a service")
    void poolOverZones() {
        var d = Draft.of("spread.yaml", """
                nodes:
                  master: { instance: m5.large, zone: eu-central-1a }
                  workers: { instance: c5.large, zone: [eu-central-1a, eu-central-1b, eu-central-1c], count: 6, prefix: workers, runs: { Worker: dissaly/test/src/Counter.java } }
                """);
        assertEquals(2, d.pools().size());
        var w = d.pools().get(1);
        assertEquals("workers", w.name());
        assertEquals(6, w.count());
        assertEquals(3, w.zones().size());
        assertEquals(java.util.Set.of("Worker"), w.runs().keySet(),
                "the form reads back both halves of what a node runs, or it cannot write "
                + "the half it did not read");
        assertEquals("dissaly/test/src/Counter.java", w.runs().get("Worker").file());
        assertTrue(w.runs().get("Worker").failures().isEmpty(),
                "and the shorthand is a service with nothing wrong with it");
    }

    @Test
    @DisplayName("a kill and its restart, read back in refMs")
    void killFailure() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    failures:
                      - { kill: true, at: 300 refMs, restartAfter: 2000 refMs }
                """);
        var k = only(d);
        assertEquals("kill", k.kind());
        assertEquals(300.0, k.atRefMs());
        assertEquals(0.0, k.perRefMs(), "an at: is not a per:");
        assertEquals(2000.0, k.restartAfterRefMs());
    }

    @Test
    @DisplayName("a freeze holds for a while; one that says nothing holds for the loader's own default")
    void freezeFailure() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    failures:
                      - { freeze: true, at: 300 refMs, for: 800 refMs }
                      - { freeze: true, at: 900 refMs }
                """);
        var f = d.pools().get(0).failures();
        assertEquals(2, f.size());
        assertEquals("freeze", f.get(0).kind());
        assertEquals(800.0, f.get(0).forRefMs());
        // Not 0: `Loader.failures` defaults a freeze to 1000 refMs, and reading it
        // back as anything else would write a different simulation on the next save.
        assertEquals(1000.0, f.get(1).forRefMs());
    }

    @Test
    @DisplayName("a degrade carries its factor in its value — it has no end")
    void degradeFailure() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    failures:
                      - { degrade: 4, at: 300 refMs }
                """);
        var f = only(d);
        assertEquals("degrade", f.kind());
        assertEquals(4.0, f.factor());
    }

    @Test
    @DisplayName("a rate reads back as a rate, and names no instant")
    void drawnFailure() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    failures:
                      - { kill: true, per: 2 refSeconds }
                """);
        var f = only(d);
        assertEquals(2000.0, f.perRefMs());
        assertEquals(0.0, f.atRefMs(), "a per: is not an at:, and the form draws one control "
                + "or the other rather than both with one left at zero");
    }

    @Test
    @DisplayName("an rpc failure reads back under the rpc it happens to")
    void rpcFailure() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    runs:
                      Worker:
                        file: dissaly/test/src/Counter.java
                        failures:
                          Map:
                            - { status: UNAVAILABLE, per: 20 calls }
                            - { slow: 4, per: 3 calls }
                          Reduce:
                            - { drop: true, per: 9 calls }
                """);
        var runs = d.pools().get(0).runs().get("Worker");
        assertEquals("dissaly/test/src/Counter.java", runs.file());
        assertEquals(java.util.Set.of("Map", "Reduce"), runs.failures().keySet());
        var map = runs.failures().get("Map");
        assertEquals("status", map.get(0).kind());
        assertEquals("UNAVAILABLE", map.get(0).status());
        assertEquals(20, map.get(0).perCalls());
        assertEquals("slow", map.get(1).kind());
        assertEquals(4.0, map.get(1).factor());
        assertEquals("drop", runs.failures().get("Reduce").get(0).kind());
    }

    /** The one failure on the one node, for a case that declares exactly that. */
    private static Draft.Failure only(Draft.Of d) {
        assertEquals(1, d.pools().get(0).failures().size());
        return d.pools().get(0).failures().get(0);
    }

    @Test
    @DisplayName("the network, read back in the four numbers it is written in")
    void network() {
        var d = Draft.of("main.yaml", """
                network: { sameZone: 0.5 refMs, crossZone: 30 refMs, jitter: 2 refMs, loss: 0.01 }
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(0.5, d.net().sameZoneRefMs(), 1e-9);
        assertEquals(30.0, d.net().crossZoneRefMs(), 1e-9);
        assertEquals(2.0, d.net().jitterRefMs(), 1e-9);
        assertEquals(0.01, d.net().loss(), 1e-9);
    }

    @Test
    @DisplayName("no network: at all is four zeros — instant and lossless, the same file either way")
    void networkAbsent() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(0.0, d.net().sameZoneRefMs());
        assertEquals(0.0, d.net().crossZoneRefMs());
        assertEquals(0.0, d.net().jitterRefMs());
        assertEquals(0.0, d.net().loss());
    }

    @Test
    @DisplayName("a network setting only one of the four leaves the rest at zero")
    void networkPartial() {
        var d = Draft.of("main.yaml", """
                network: { loss: 0.2 }
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(0.2, d.net().loss(), 1e-9);
        assertEquals(0.0, d.net().crossZoneRefMs());
    }

    @Test
    @DisplayName("a retry, unsafe or not, with no multiplier")
    void retryRule() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: a, runs: { Worker: dissaly/test/src/Counter.java } }
                retries:
                  - { method: lab.Worker.Map, attempts: 3, backoff: 40 refMs, unsafe: true }
                """);
        assertEquals(1, d.retries().size());
        var r = d.retries().get(0);
        assertEquals("lab.Worker.Map", r.method());
        assertEquals(3, r.attempts());
        assertTrue(r.unsafe());
    }

    // ---------------------------------------------------------------- refusals

    private static String refusal(String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> Draft.of("main.yaml", yaml))
                .getMessage();
    }

    @Test
    @DisplayName("a simulation the loader itself would refuse is refused with the loader's own words")
    void aBrokenScenarioNeverReachesTheDraftWalk() {
        // No `machines:` at all — the loader's own refusal, not a Draft-shaped one.
        String said = refusal("");
        assertTrue(said.contains("main.yaml:"), said);
    }

    @Test
    @DisplayName("scale reads back, and defaults to 1 — one run, nothing projected")
    void scaleReadsBack() {
        var d = Draft.of("main.yaml", """
                scale: 6
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a, runs: { Worker: dissaly/test/src/Counter.java } }
                """);
        // One number, and nothing beside it. Above scale 1 a simulation is a model
        // of something bigger and there is nothing else it could be, so the draft
        // carries no mode: a second field saying what `scale:` already says is a
        // second field that can disagree with it.
        assertEquals(6.0, d.scale(), 1e-9);

        var e = Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a, runs: { Worker: dissaly/test/src/Counter.java } }
                """);
        assertEquals(1.0, e.scale(), 1e-9,
                "a simulation that never mentions a scale is a run of itself, not a model of "
                + "something bigger");
    }

    @Test
    @DisplayName("a pool's caps read back, and null is not zero")
    void poolCaps() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a, memoryMb: 4096, diskMb: 1024 }
                  b: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(4096.0, d.pools().get(0).memoryMb(), 1e-9);
        assertEquals(1024.0, d.pools().get(0).diskMb(), 1e-9);
        // The third state. A pool that never mentioned a cap has the instance
        // type's own, and reading that back as 0 would be a machine that cannot
        // hold anything — a legal simulation, and not this one.
        assertNull(d.pools().get(1).memoryMb());
        assertNull(d.pools().get(1).diskMb());
    }

    @Test
    @DisplayName("a pool's overrides read back, one entry per machine set apart")
    void poolOverrides() {
        var d = Draft.of("main.yaml", """
                nodes:
                  workers:
                    instance: c5.large
                    zone: eu-central-1a
                    count: 4
                    prefix: w
                    overrides:
                      w1: { instance: a1.medium }
                      w2: { zone: eu-central-1b }
                      w3: { memoryMb: 4, diskMb: 512 }
                """);
        var over = d.pools().get(0).overrides();
        assertEquals(3, over.size());
        assertEquals("w1", over.get(0).node());
        assertEquals("a1.medium", over.get(0).instance());
        // Empty and null are "the pool's own", which is what a missing key means.
        assertEquals("", over.get(0).zone());
        assertNull(over.get(0).memoryMb());
        assertEquals("eu-central-1b", over.get(1).zone());
        assertEquals(4.0, over.get(2).memoryMb(), 1e-9);
        assertEquals(512.0, over.get(2).diskMb(), 1e-9);

        // A pool nobody made an exception in carries none, rather than one empty
        // entry per machine.
        assertTrue(Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).pools().get(0).overrides().isEmpty());
    }

    @Test
    @DisplayName("an override with a key nothing reads is refused, even for a machine the loader ignores")
    void overrideWithAnUnknownKey() {
        // The loader checks an override only when it names a machine it expands,
        // and silently ignores one that names nothing. This has to check every
        // entry: what it cannot read it cannot write back, and a save would drop
        // it without saying so.
        String said = refusal("""
                nodes:
                  workers:
                    instance: c5.large
                    zone: eu-central-1a
                    count: 2
                    prefix: w
                    overrides:
                      nobody: { memoryMB: 4 }
                """);
        assertTrue(said.contains("memoryMB"), said);
    }

    @Test
    @DisplayName("a pool with no zone: is refused — the form always writes one")
    void poolWithNoZone() {
        assertTrue(refusal("""
                nodes:
                  a: { instance: m5.large }
                """).contains("zone"));
    }

    @Test
    @DisplayName("a prefix that names the machines apart from their pool reads back")
    void prefixReadsBack() {
        var d = Draft.of("main.yaml", """
                nodes:
                  mappers: { instance: c5.large, zone: eu-central-1a, count: 4, prefix: m }
                """);
        assertEquals("mappers", d.pools().get(0).name());
        assertEquals("m", d.pools().get(0).prefix());
        assertEquals(4, d.pools().get(0).count());

        // A pool that never said one is named after itself, which is what the
        // loader does with it.
        var e = Draft.of("main.yaml", """
                nodes:
                  workers: { instance: c5.large, zone: eu-central-1a, count: 2 }
                """);
        assertEquals("workers", e.pools().get(0).prefix());
    }

    @Test
    @DisplayName("a pool of one spelled count: 1 is refused — its machine is a0, and the form's is a")
    void poolOfOneWithAnExplicitCount() {
        // The one shape left that the form cannot write back unchanged. Both
        // files are one machine; they disagree about what it is called, and
        // every fault points at a name.
        String said = refusal("""
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a, count: 1 }
                """);
        assertTrue(said.contains("a0") && said.contains("count"), said);
    }

    @Test
    @DisplayName("every failure kind the loader accepts opens in the form")
    void everyKindOpens() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a:
                    instance: m5.large
                    zone: eu-central-1a
                    failures:
                      - { kill: true, at: 100 refMs, restartAfter: 500 refMs }
                      - { freeze: true, at: 200 refMs, for: 300 refMs }
                      - { degrade: 4, at: 300 refMs }
                      - { restart: true, at: 400 refMs }
                      - { spotReclaim: true, at: 500 refMs, notice: 120 refMs }
                      - { partition: b, at: 600 refMs }
                      - { heal: b, at: 900 refMs }
                  b: { instance: m5.large, zone: eu-central-1b }
                """);
        var f = d.pools().get(0).failures();
        assertEquals(List.of("kill", "freeze", "degrade", "restart", "spotReclaim",
                             "partition", "heal"),
                f.stream().map(Draft.Failure::kind).toList());

        // The warning is the whole of what a spot reclaim teaches, so it is the
        // one field that has to survive the trip.
        assertEquals(120.0, f.get(4).noticeRefMs(), 1e-9);

        // The far end of a pair, read back. Reachability is a property of two
        // nodes, and a Draft carrying only the block's own name would write a
        // partition of one.
        assertEquals("b", f.get(5).other());
        assertEquals("b", f.get(6).other());

        // Every other kind is aimed at the node it is written in and leaves the
        // second name empty. That name used to be the first one repeated, which
        // would read as a node cut off from itself if anything wrote it out.
        for (int i = 0; i < 5; i++)
            assertEquals("", f.get(i).other(), "failure " + i + " has no second node");
    }

    @Test
    @DisplayName("a key belonging to a different failure kind is refused, naming the kind it is on")
    void keysThatBelongToAnotherKind() {
        // `for:` on a kill: nothing ever reads it, so the loader refuses it rather
        // than accepting a line it will not act on. The console used to make this
        // refusal itself, off its own table of which key belongs to which kind —
        // two copies of one fact, and only one of them enforced on the run.
        String said = failureRefusal("      - { kill: true, at: 100 refMs, for: 500 refMs }");
        assertTrue(said.contains("for:") && said.contains("kill"), said);

        // A freeze thaws on its own, so `restartAfter:` means nothing to it.
        assertTrue(failureRefusal("      - { freeze: true, at: 100 refMs, restartAfter: 200 refMs }")
                .contains("restartAfter:"));

        // And the one that reads like it ought to work and does not: a one-time
        // degrade schedules no thaw, so `for:` would be accepted and then ignored,
        // and the node would stay slow for the rest of the simulation.
        String degrade = failureRefusal("      - { degrade: 3, at: 100 refMs, for: 500 refMs }");
        assertTrue(degrade.contains("for:") && degrade.contains("degrade"), degrade);

        // `notice:` belongs to spotReclaim, and to nothing else.
        assertTrue(failureRefusal("      - { kill: true, at: 100 refMs, notice: 50 refMs }")
                .contains("notice:"));
    }

    /** What one bad failure entry on one node is refused with. */
    private static String failureRefusal(String entry) {
        return refusal(""
                     + "nodes:\n"
                     + "  a:\n"
                     + "    instance: m5.large\n"
                     + "    zone: eu-central-1a\n"
                     + "    failures:\n"
                     + entry + "\n");
    }

    @Test
    @DisplayName("a retry's multiplier reads back, and 1 is the flat default")
    void retryMultiplier() {
        var d = Draft.of("main.yaml", """
                nodes:
                  a: { instance: m5.large, zone: eu-central-1a }
                retries:
                  - { method: lab.Worker.Map, attempts: 5, backoff: 20 refMs, multiplier: 2 }
                  - { method: lab.Worker.Reduce, attempts: 2, backoff: 40 refMs }
                """);
        assertEquals(2.0, d.retries().get(0).multiplier(), 1e-9);
        assertEquals(1.0, d.retries().get(1).multiplier(), 1e-9,
                "a flat backoff is what a retry gets by saying nothing");
    }
}
