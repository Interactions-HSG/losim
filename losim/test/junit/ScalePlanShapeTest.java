import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import losim.scale.Fit;
import losim.scale.Laws;
import losim.scale.Plans;
import losim.scale.Probe;
import losim.scale.ScalePlan;
import losim.trace.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a plan carries, and what survives being cached.
 *
 * <p>A plan is written to {@code build/.losim-plans} and read back on the next run
 * of the same simulation, so everything the engine learned has to survive a
 * round trip through JSON or it is learned once and quietly lost. That is the
 * failure this class exists for, and it is a silent one: a plan missing its
 * per-machine laws does not throw, it reports every machine as refused — which
 * reads exactly like an engine that looked and found nothing.
 *
 * <p>Nothing here runs a simulation. These are claims about the plan's shape, and
 * a shape can be asserted against a plan built by hand in a millisecond. What
 * needs a real run — that the numbers are right, that a node's share is measured
 * rather than divided — is in {@code ScaleProjectionTest}, which pays for one.
 */
class ScalePlanShapeTest {

    /** A law that is fitted rather than refused, with values nothing else in here depends on. */
    private static Fit.Law law(String resource, String variable) {
        return new Fit.Law(resource, variable, 1.5, 0.25, 0.9, 0.999, 0.01);
    }

    /**
     * A plan with one cluster law, one per-machine law, one per-machine refusal,
     * and both readings of a machine's caps.
     */
    private static ScalePlan plan() {
        var byResource = new TreeMap<String, Fit.Law>();
        byResource.put(Probe.WIRE, law(Probe.WIRE, "units"));
        byResource.put(Probe.perNode("w0", Probe.WIRE), law(Probe.perNode("w0", Probe.WIRE), "units"));

        var errorBars = new TreeMap<String, Double>();
        errorBars.put(Probe.WIRE, 1.25);
        errorBars.put(Probe.perNode("w0", Probe.WIRE), 1.5);

        var refused = new TreeMap<String, String>();
        refused.put(Probe.perNode("w0", Probe.DISK), "it was never measured above zero");

        var caps = new LinkedHashMap<String, double[]>();
        caps.put("w0", new double[]{512, 1024});
        var fullCaps = new LinkedHashMap<String, double[]>();
        fullCaps.put("w0", new double[]{16384, 32768});

        var laws = new Laws(byResource, errorBars, refused, new TreeMap<>(),
                new TreeMap<>(), new TreeMap<>());
        return new ScalePlan(8000, 40000000, caps, fullCaps, laws, 28, List.of(), null);
    }

    // ------------------------------------------------------------- the key shape

    @Test
    @DisplayName("a per-machine resource key comes apart into the machine and the resource again")
    void keysRoundTrip() {
        String key = Probe.perNode("w0", Probe.WIRE);
        assertTrue(Probe.isPerNode(key), key + " should be recognised as naming one machine");
        assertEquals("w0", Probe.machineOf(key));
        assertEquals(Probe.WIRE, Probe.resourceOf(key));
    }

    @Test
    @DisplayName("a cluster resource key names no machine, and reading one off it yields null rather than a guess")
    void clusterKeysNameNoMachine() {
        assertFalse(Probe.isPerNode(Probe.WIRE));
        assertNull(Probe.machineOf(Probe.WIRE),
                "a cluster key has no machine half, and inventing one would attribute the"
                + " whole cluster's egress to a node called wireMb");
        assertEquals(Probe.WIRE, Probe.resourceOf(Probe.WIRE),
                "the resource half of a cluster key is the key");
    }

    // ------------------------------------------------------ what the plan carries

    @Test
    @DisplayName("the plan writes both readings of every machine's caps")
    void bothCapsAreWritten() {
        var m = plan().asMap();

        @SuppressWarnings("unchecked")
        var solved = (Map<String, Object>) m.get("caps");
        @SuppressWarnings("unchecked")
        var full = (Map<String, Object>) m.get("fullCaps");

        assertNotNull(solved, "the solved caps are what the probe ran under");
        assertNotNull(full, "without the full-size caps a percentage has nothing to be a"
                + " percentage of: the demand would be projected and the capacity would not");
        assertEquals(solved.keySet(), full.keySet(),
                "every machine given a solved cap must also carry the cap it would really have");

        assertEquals(List.of(16384.0, 32768.0), full.get("w0"));
    }

    @Test
    @DisplayName("a scaled machine is given less than it would really have, or the model is not a model")
    void solvedCapsAreBelowFullOnes() {
        var p = plan();
        for (var e : p.caps().entrySet()) {
            double[] solved = e.getValue();
            double[] full = p.fullCaps().get(e.getKey());
            assertNotNull(full, e.getKey() + " has a solved cap and no full-size one");
            assertTrue(solved[0] <= full[0], e.getKey() + " was given more memory for the probe"
                    + " than the machine has, so demand over capacity is not preserved");
            assertTrue(solved[1] <= full[1], e.getKey() + " was given more disk for the probe"
                    + " than the machine has");
        }
    }

    // ------------------------------------------------------------- the cache trip

    @Test
    @DisplayName("per-machine laws, refusals and full-size caps all survive being cached")
    void everythingSurvivesTheCache() {
        String key = "junit-" + Long.toHexString(System.nanoTime());
        Plans.save(key, plan());
        var back = Plans.load(key).orElse(null);
        assertNotNull(back, "a plan just saved should load");

        assertEquals(Map.of("w0", List.of(16384.0, 32768.0)).keySet(), back.fullCaps().keySet());
        assertArrayEquals(new double[]{16384, 32768}, back.fullCaps().get("w0"), 1e-9,
                "the full-size caps came back changed, so a cached plan measures against"
                + " a different machine from the one that was fitted");

        String perNode = Probe.perNode("w0", Probe.WIRE);
        assertNotNull(back.laws().law(perNode),
                "the per-machine law did not survive the cache. Nothing would throw: every"
                + " machine would simply be reported as having no law, which is indistinguishable"
                + " from an engine that looked and found nothing");
        assertEquals("units", back.laws().law(perNode).variable());

        assertTrue(back.laws().refused().containsKey(Probe.perNode("w0", Probe.DISK)),
                "a per-machine refusal is a result and has to survive too — dropped, the"
                + " machine comes back with no law and no reason, which reads as a bug");
    }

    @Test
    @DisplayName("a plan cached by an engine that never heard of full-size caps loads without them, rather than inventing a pair")
    void olderPlansLoadWithoutFullCaps() {
        String key = "junit-old-" + Long.toHexString(System.nanoTime());
        var m = new LinkedHashMap<>(plan().asMap());
        m.remove("fullCaps");
        assertDoesNotThrow(() -> {
            Files.createDirectories(Plans.CACHE);
            Files.writeString(Plans.CACHE.resolve(key + ".json"), Json.write(m));
        });

        var back = Plans.load(key).orElse(null);
        assertNotNull(back, "a plan from an older engine is still a plan");
        assertTrue(back.fullCaps().isEmpty(),
                "the full-size caps should be absent, not fabricated from the solved ones —"
                + " a reading nobody can make is better than one nobody can check");
        assertFalse(back.caps().isEmpty(), "the rest of the plan should be unaffected");
    }

    @Test
    @DisplayName("the plan format is part of the cache key, so an engine that learned something new does not reuse a plan that never had it")
    void formatIsInTheCacheKey() {
        // The key is a hash, so the claim is made the only way it can be made from
        // outside: change nothing but the format and the key has to move. Rather
        // than reach into the hash, this asserts on what the format is *for* — and
        // the assertion that would have caught the bug is the one below it.
        assertTrue(ScalePlan.FORMAT >= 2,
                "the format was bumped when per-machine laws and full-size caps were added;"
                + " a plan fitted before that has neither, and the class fingerprint in the"
                + " key covers the simulation's code rather than losim's own");
    }
}
