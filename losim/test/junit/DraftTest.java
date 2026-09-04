import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import losim.cli.Draft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an existing scenario looks like to the authoring form — and what it
 * refuses rather than shows with something quietly missing.
 *
 * <p>No lab, no compile, no fixture: {@code Loader.of} never checks that
 * {@code job:} or {@code runs:} name real compiled classes — that is a run's
 * problem, not a load's — so every case here is a plain string in, a record
 * or a refusal out.
 */
class DraftTest {

    // -------------------------------------------------------------- round trip

    @Test
    @DisplayName("a pool of one keeps the pool's own name, and needs no count or prefix")
    void poolOfOne() {
        var d = Draft.of("main.yaml", """
                job: WordCountJob
                machines:
                  master:
                    instance: m5.large
                    zone: eu-central-1a
                """);
        assertEquals("main", d.name());
        assertEquals("WordCountJob", d.job());
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
                job: WordCountJob
                machines:
                  coordinator: { instance: m5.large, zone: eu-central-1a }
                  workers: { instance: c5.large, zone: [eu-central-1a, eu-central-1b, eu-central-1c], count: 6, prefix: workers, runs: [Counter] }
                """);
        assertEquals(2, d.pools().size());
        var w = d.pools().get(1);
        assertEquals("workers", w.name());
        assertEquals(6, w.count());
        assertEquals(3, w.zones().size());
        assertEquals(java.util.List.of("Counter"), w.runs());
    }

    @Test
    @DisplayName("expectedRun is read in seconds regardless of which unit the file used")
    void expectedRunInSeconds() {
        var d = Draft.of("main.yaml", """
                job: J
                expectedRun: 20 refSeconds
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(20.0, d.expectedRunRefSeconds(), 1e-9);

        var e = Draft.of("main.yaml", """
                job: J
                expectedRun: 5000 refMs
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(5.0, e.expectedRunRefSeconds(), 1e-9);
    }

    @Test
    @DisplayName("a kill fault and its restart, read back in refMs")
    void killFault() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 300 refMs, kill: a, restart_after: 2000 refMs }
                """);
        assertEquals(1, d.faults().size());
        var k = d.faults().get(0);
        assertEquals("kill", k.kind());
        assertEquals(300.0, k.atRefMs());
        assertEquals("a", k.target());
        assertEquals(2000.0, k.restartAfterRefMs());
    }

    @Test
    @DisplayName("a freeze holds for a while; one that says nothing holds for the loader's own default")
    void freezeFault() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 300 refMs, freeze: a, for: 800 refMs }
                  - { at: 900 refMs, freeze: a }
                """);
        assertEquals(2, d.faults().size());
        assertEquals("freeze", d.faults().get(0).kind());
        assertEquals("a", d.faults().get(0).target());
        assertEquals(800.0, d.faults().get(0).forRefMs());
        // Not 0: `Loader.faults` defaults a freeze to 1000 refMs, and reading it
        // back as anything else would write a different scenario on the next save.
        assertEquals(1000.0, d.faults().get(1).forRefMs());
    }

    @Test
    @DisplayName("a degrade carries its factor and nothing else — it has no end")
    void degradeFault() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 300 refMs, degrade: a, factor: 4 }
                """);
        assertEquals(1, d.faults().size());
        var f = d.faults().get(0);
        assertEquals("degrade", f.kind());
        assertEquals("a", f.target());
        assertEquals(4.0, f.factor());
    }

    @Test
    @DisplayName("the network, read back in the four numbers it is written in")
    void network() {
        var d = Draft.of("main.yaml", """
                job: J
                network: { sameZone: 0.5 refMs, crossZone: 30 refMs, jitter: 2 refMs, loss: 0.01 }
                machines:
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
                job: J
                machines:
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
                job: J
                network: { loss: 0.2 }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(0.2, d.net().loss(), 1e-9);
        assertEquals(0.0, d.net().crossZoneRefMs());
    }

    @Test
    @DisplayName("chaos, every kind, with its own factor and duration")
    void chaosRules() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 3, prefix: a, runs: [Counter] }
                chaos:
                  - { freeze: { every: 700 refMs, among: a, for: 150 refMs } }
                  - { degrade: { every: 400 refMs, among: a, factor: 3 } }
                """);
        assertEquals(2, d.chaos().size());
        assertEquals("freeze", d.chaos().get(0).kind());
        assertEquals("a", d.chaos().get(0).among());
        assertEquals("degrade", d.chaos().get(1).kind());
        assertEquals(3.0, d.chaos().get(1).factor());
    }

    @Test
    @DisplayName("a retry, unsafe or not, with no multiplier")
    void retryRule() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: a, runs: [Counter] }
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
    @DisplayName("a scenario the loader itself would refuse is refused with the loader's own words")
    void aBrokenScenarioNeverReachesTheDraftWalk() {
        // No `machines:` at all — the loader's own refusal, not a Draft-shaped one.
        String said = refusal("job: J\n");
        assertTrue(said.contains("main.yaml:"), said);
    }

    @Test
    @DisplayName("tightMargin: reads back as the marker it is")
    void tightMarginReadsBack() {
        assertTrue(Draft.of("main.yaml", """
                job: J
                tightMargin: true
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).tightMargin());
        assertFalse(Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).tightMargin(), "false is what a scenario gets by saying nothing");
    }

    @Test
    @DisplayName("a workload, read back with the ladder the loader fills in when the file does not")
    void workloadReadsBack() {
        var d = Draft.of("main.yaml", """
                job: J
                workload: { records: 5000, probe: [500, 1000, 2000, 4000], workers: [2, 4, 6] }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, runs: [Counter] }
                """);
        assertEquals("direct", d.mode());
        assertEquals(5000, d.workload().records());
        assertEquals(List.of(500, 1000, 2000, 4000), d.workload().probe());
        assertEquals(List.of(2, 4, 6), d.workload().workers());

        // A ladder the file leaves out is still the ladder the run climbs, so the
        // form has to be shown it — an empty box here would write a different
        // scenario back on the next save.
        var e = Draft.of("main.yaml", """
                job: J
                workload: { records: 900 }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, runs: [Counter] }
                """);
        assertEquals(4, e.workload().probe().size(), "the loader's own default ladder");
        assertFalse(e.workload().workers().isEmpty());
    }

    @Test
    @DisplayName("no workload: at all is null, which is not a workload of one record")
    void noWorkloadIsNotAWorkloadOfOne() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertNull(d.workload(),
                "a scenario that never mentions a workload has none; the run gives the job one "
                + "record, which is a different file from one that says so");
        assertEquals("direct", d.mode());
    }

    @Test
    @DisplayName("mode: scaled reads back as scaled")
    void scaledMode() {
        var d = Draft.of("main.yaml", """
                job: J
                mode: scaled
                workload: { records: 1000000 }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, runs: [Counter] }
                """);
        assertEquals("scaled", d.mode());
        assertEquals(1000000, d.workload().records());
    }

    @Test
    @DisplayName("kTime is a plain field, read back exactly, and defaults to 1 when the file omits it")
    void kTimeRoundTrips() {
        var d = Draft.of("main.yaml", """
                job: J
                kTime: 20
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(20.0, d.kTime(), 1e-9);

        var e = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(1.0, e.kTime(), 1e-9);
    }

    @Test
    @DisplayName("a pool's caps read back, and null is not zero")
    void poolCaps() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, memoryMb: 4096, diskMb: 1024 }
                  b: { instance: m5.large, zone: eu-central-1a }
                """);
        assertEquals(4096.0, d.pools().get(0).memoryMb(), 1e-9);
        assertEquals(1024.0, d.pools().get(0).diskMb(), 1e-9);
        // The third state. A pool that never mentioned a cap has the instance
        // type's own, and reading that back as 0 would be a machine that cannot
        // hold anything — a legal scenario, and not this one.
        assertNull(d.pools().get(1).memoryMb());
        assertNull(d.pools().get(1).diskMb());
    }

    @Test
    @DisplayName("a pool's overrides read back, one entry per machine set apart")
    void poolOverrides() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
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
        assertEquals("w1", over.get(0).machine());
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
                job: J
                machines:
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
                job: J
                machines:
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
                job: J
                machines:
                  a: { instance: m5.large }
                """).contains("zone"));
    }

    @Test
    @DisplayName("a prefix that names the machines apart from their pool reads back")
    void prefixReadsBack() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  mappers: { instance: c5.large, zone: eu-central-1a, count: 4, prefix: m }
                """);
        assertEquals("mappers", d.pools().get(0).name());
        assertEquals("m", d.pools().get(0).prefix());
        assertEquals(4, d.pools().get(0).count());

        // A pool that never said one is named after itself, which is what the
        // loader does with it.
        var e = Draft.of("main.yaml", """
                job: J
                machines:
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
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 1 }
                """);
        assertTrue(said.contains("a0") && said.contains("count"), said);
    }

    @Test
    @DisplayName("every fault kind the loader accepts opens in the form")
    void everyFaultKindOpens() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                  b: { instance: m5.large, zone: eu-central-1b }
                faults:
                  - { at: 100 refMs, kill: a, restart_after: 500 refMs }
                  - { at: 200 refMs, freeze: a, for: 300 refMs }
                  - { at: 300 refMs, degrade: b, factor: 4 }
                  - { at: 400 refMs, restart: a }
                  - { at: 500 refMs, spot_reclaim: b, notice: 120 refMs }
                  - { at: 600 refMs, partition: [a, b] }
                  - { at: 900 refMs, heal: [a, b] }
                """);
        assertEquals(List.of("kill", "freeze", "degrade", "restart", "spot_reclaim",
                             "partition", "heal"),
                d.faults().stream().map(Draft.Fault::kind).toList());

        // The warning is the whole of what a spot reclaim teaches, so it is the
        // one field that has to survive the trip.
        assertEquals(120.0, d.faults().get(4).noticeRefMs(), 1e-9);

        // And the pair, read apart. Reachability is a property of two machines,
        // and a Draft that carried only the first would write a partition of one.
        assertEquals("a", d.faults().get(5).target());
        assertEquals("b", d.faults().get(5).other());
        assertEquals("a", d.faults().get(6).target());
        assertEquals("b", d.faults().get(6).other());

        // Every other kind names one machine and leaves the second empty, rather
        // than repeating the first — which would read as a machine cut off from
        // itself if anything ever wrote it out.
        for (int i = 0; i < 5; i++)
            assertEquals("", d.faults().get(i).other(), "fault " + i + " has no second machine");
    }

    @Test
    @DisplayName("a key belonging to a different fault kind is refused, naming the kind it is on")
    void faultKeysThatBelongToAnotherKind() {
        // `for:` on a kill: the loader takes it, and nothing ever reads it.
        String said = refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, kill: a, for: 500 refMs }
                """);
        assertTrue(said.contains("for:") && said.contains("kill"), said);

        // A freeze thaws on its own, so `restart_after:` means nothing to it.
        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, freeze: a, restart_after: 200 refMs }
                """).contains("restart_after:"));

        // And the one that reads like it ought to work and does not: a one-time
        // degrade schedules no thaw, so `for:` is accepted by the loader and then
        // ignored, and the machine stays slow for the rest of the run.
        String degrade = refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, degrade: a, factor: 3, for: 500 refMs }
                """);
        assertTrue(degrade.contains("for:") && degrade.contains("degrade"), degrade);

        // `notice:` belongs to spot_reclaim, which has no control of its own either.
        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, kill: a, notice: 50 refMs }
                """).contains("notice:"));
    }

    @Test
    @DisplayName("a retry's multiplier reads back, and 1 is the flat default")
    void retryMultiplier() {
        var d = Draft.of("main.yaml", """
                job: J
                machines:
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
