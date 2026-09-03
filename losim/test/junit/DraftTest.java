import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(1, d.kills().size());
        var k = d.kills().get(0);
        assertEquals(300.0, k.atRefMs());
        assertEquals("a", k.target());
        assertEquals(2000.0, k.restartAfterRefMs());
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
    @DisplayName("network:, tightMargin:, mode: and workload: are all refused by name")
    void topLevelKeysTheFormHasNoControlFor() {
        assertTrue(refusal("""
                job: J
                network: { loss: 0.1 }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).contains("network:"));

        assertTrue(refusal("""
                job: J
                tightMargin: true
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).contains("tightMargin:"));

        assertTrue(refusal("""
                job: J
                mode: scaled
                workload: { records: 1000000 }
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                """).contains("mode:"));
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
    @DisplayName("a pool's memoryMb, diskMb and overrides are refused, naming the pool")
    void poolKeysTheFormHasNoControlFor() {
        String said = refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, memoryMb: 4096 }
                """);
        assertTrue(said.contains("'a'") && said.contains("memoryMb:"), said);

        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: a, diskMb: 1024 }
                """).contains("diskMb:"));

        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: a, overrides: { a1: { instance: c5.large } } }
                """).contains("overrides:"));
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
    @DisplayName("a prefix that disagrees with the pool's own name is refused")
    void prefixMismatch() {
        String said = refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: other }
                """);
        assertTrue(said.contains("prefix:") && said.contains("'a'"), said);
    }

    @Test
    @DisplayName("a fault that isn't kill: is refused")
    void nonKillFault() {
        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                  b: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, partition: [a, b] }
                """).contains("kill:"));

        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, freeze: a, for: 500 refMs }
                """).contains("kill:"));
    }

    @Test
    @DisplayName("a kill fault's notice: or for: is refused — Draft.Kill has neither field")
    void killFaultWithNoticeOrFor() {
        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, kill: a, notice: 50 refMs }
                """).contains("notice:"));

        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a }
                faults:
                  - { at: 100 refMs, kill: a, for: 500 refMs }
                """).contains("for:"));
    }

    @Test
    @DisplayName("a retry's multiplier: is refused — Draft.Retry has no field for it")
    void retryWithMultiplier() {
        assertTrue(refusal("""
                job: J
                machines:
                  a: { instance: m5.large, zone: eu-central-1a, count: 2, prefix: a }
                retries:
                  - { method: lab.Worker.Map, attempts: 3, backoff: 40 refMs, multiplier: 2 }
                """).contains("multiplier:"));
    }
}
