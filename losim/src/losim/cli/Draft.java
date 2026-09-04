package losim.cli;

import java.util.ArrayList;
import java.util.List;
import losim.scenario.Loader;
import losim.scenario.Node;
import losim.scenario.Yaml;

/**
 * What an existing scenario looks like to the authoring form.
 *
 * <p>The mirror image of {@link Palette}: that reads a lab's code and says
 * what <i>could</i> be placed; this reads a scenario already on disk and says
 * what <i>was</i> — a pool, an instance, a set of zones, which classes run on
 * it, and the weather — in the same shape the form composes a new one in.
 *
 * <p><b>Parses nothing itself.</b> {@link Yaml#parse} is called once, by
 * {@link Loader#of}, which is asked first: a file that would not load at all
 * fails there, with the loader's own line-numbered refusal, so "this scenario
 * is broken" and "this scenario is valid but the form has no control for
 * something in it" are never confused with each other. What follows is a
 * second walk of the same parsed tree — the relationship {@link Loader}'s own
 * private methods already have to it — not a second grammar.
 *
 * <p><b>Everything the form has no control for is a refusal, not a guess.</b>
 * A scenario using {@code mode:}, a pool's {@code memoryMb}, a retry's
 * {@code multiplier}, a {@code partition:} fault — anything present here and
 * absent from the Draft model comes back naming the key and the line, never a
 * Draft missing it silently and a save that quietly drops what it never showed.
 */
public final class Draft {
    private Draft() {}

    public record Pool(String name, int count, String instance, List<String> zones, List<String> runs) {}

    /**
     * One thing that happens to one machine at one instant.
     *
     * <p>{@code kind} is {@code kill}, {@code freeze} or {@code degrade} — the
     * three of {@link losim.scenario.Scenario.Kind} that happen to a single
     * machine and that the form has controls for. Which of the remaining
     * fields means anything depends on it, and the ones that do not are not
     * written back: a kill's {@code restartAfterRefMs}, a freeze's
     * {@code forRefMs}, a degrade's {@code factor}.
     */
    public record Fault(String kind, double atRefMs, String target,
                        double forRefMs, double factor, double restartAfterRefMs) {}

    public record Chaos(String kind, double everyRefMs, String among, double forRefMs, double factor) {}
    public record Retry(String method, int attempts, double backoffRefMs, boolean unsafe) {}

    /** The medium, in the same four numbers {@code network:} is written in. All zero is no key at all. */
    public record Net(double sameZoneRefMs, double crossZoneRefMs, double jitterRefMs, double loss) {}

    public record Of(String name, String job, long seed, double kTime, double expectedRunRefSeconds,
                      Net net, List<Pool> pools, List<Fault> faults, List<Chaos> chaos,
                      List<Retry> retries) {}

    /**
     * The one-time faults the form can show, and for each the keys it cannot.
     *
     * <p>Refused rather than dropped, and refused rather than shown, because
     * every one of them is a key that either belongs to a different fault kind
     * or does nothing at all. {@code for:} on a degrade is the interesting one:
     * {@link losim.scenario.Loader} accepts it, and {@code Run.schedule}
     * schedules no thaw for a one-time degrade — the machine stays slow for the
     * rest of the run. A control writing a key the run ignores is a lie told in
     * a form, so the form does not have one and a file using it is sent back.
     */
    private static final java.util.Map<String, List<String>> UNUSABLE = java.util.Map.of(
            "kill",    List.of("notice", "for", "factor"),
            "freeze",  List.of("notice", "factor", "restart_after"),
            "degrade", List.of("notice", "for", "restart_after"));

    /**
     * @param name the file's own name, {@code two-machines.yaml} — trimmed to the
     *             stem the form knows it by, since the loader's own comment line
     *             (the only place {@code toYaml} writes a name) is not something
     *             {@link Yaml#parse} keeps; comments are stripped before anything
     *             sees them
     * @param text the file's own content
     * @throws IllegalArgumentException the loader's own refusal, or this one's —
     *         both {@code file:line: message}, indistinguishable to whoever reads it
     */
    public static Of of(String name, String text) {
        Node root = Yaml.parse(name, text);
        var sc = Loader.of(root);   // the real check, first — baseline correctness is never re-done below

        // `network:` is not here: the form has a control for all four keys
        // `Loader.network` allows, so there is nothing to refuse and nothing to
        // walk — `sc.net()` below is the loader's own already-resolved answer.
        for (String key : List.of("tightMargin", "mode", "workload")) {
            if (root.opt(key).present()) throw root.at(key).fail(
                    "uses " + key + ":, which the form doesn't have a control for yet. Edit the"
                    + " file directly, then open it here again once it's out.");
        }

        var pools = new ArrayList<Pool>();
        for (var entry : root.at("machines").map().entrySet()) {
            String poolName = entry.getKey();
            Node spec = entry.getValue();
            for (String key : List.of("memoryMb", "diskMb", "overrides")) {
                if (spec.opt(key).present()) throw spec.at(key).fail(
                        "'" + poolName + "' uses " + key + ":, which the form doesn't have a"
                        + " control for yet. Edit the file directly, then open it here again.");
            }
            if (!spec.opt("zone").present()) throw spec.fail(
                    "'" + poolName + "' has no zone: — the form always writes one, so a scenario"
                    + " without one did not come from it. Add a zone: and open it here again.");
            String instance = spec.at("instance").str();
            var zones = spec.at("zone").strings();
            var runs = spec.opt("runs").present() ? spec.at("runs").strings() : List.<String>of();
            int count = spec.opt("count").integer(0);
            if (count == 0) {
                pools.add(new Pool(poolName, 1, instance, zones, runs));
                continue;
            }
            String prefix = spec.opt("prefix").str(poolName);
            if (!prefix.equals(poolName)) throw spec.at("prefix").fail(
                    "'" + poolName + "' uses prefix: '" + prefix + "', different from the pool's"
                    + " own name — the form always keeps them the same. Edit the file directly,"
                    + " then open it here again.");
            pools.add(new Pool(poolName, count, instance, zones, runs));
        }

        var faults = new ArrayList<Fault>();
        for (Node f : root.opt("faults").list()) {
            String kind = null;
            for (String k : UNUSABLE.keySet()) if (f.opt(k).present()) kind = k;
            // Two kinds at once is already the loader's refusal, so reaching here
            // with none of the three means one of the kinds it accepts and this
            // does not: spot_reclaim, partition, heal, restart.
            if (kind == null) throw f.fail(
                    "a fault the form has no control for — it edits kill, freeze and degrade,"
                    + " which are the ones that happen to a single machine. Edit the file"
                    + " directly, then open it here again.");
            for (String key : UNUSABLE.get(kind)) {
                if (f.opt(key).present()) throw f.at(key).fail(
                        "this " + kind + " fault uses " + key + ":, which does nothing to a "
                        + kind + " and which the form has no control for. Edit the file"
                        + " directly, then open it here again.");
            }
            // Defaults match the loader's own, so a freeze that omits `for:` reads
            // back as the 1000 refMs it will actually be run with rather than a 0
            // the form would then write down and change the scenario by.
            faults.add(new Fault(kind, f.at("at").refMs(), f.at(kind).str(),
                    f.opt("for").refMs(1000), f.opt("factor").num(2),
                    f.opt("restart_after").refMs(0)));
        }

        var chaos = new ArrayList<Chaos>();
        for (Node c : root.opt("chaos").list()) {
            for (var entry : c.map().entrySet()) {
                Node body = entry.getValue();
                chaos.add(new Chaos(entry.getKey(), body.at("every").refMs(), body.at("among").str(),
                        body.opt("for").refMs(1000), body.opt("factor").num(2)));
            }
        }

        var retries = new ArrayList<Retry>();
        for (Node r : root.opt("retries").list()) {
            if (r.opt("multiplier").present()) throw r.at("multiplier").fail(
                    "this retry uses multiplier:, which the form doesn't have a control for yet."
                    + " Edit the file directly, then open it here again.");
            retries.add(new Retry(r.at("method").str(), r.at("attempts").integer(),
                    r.opt("backoff").refMs(0), r.opt("unsafe").bool(false)));
        }

        var net = new Net(sc.net().sameZoneRefMs(), sc.net().crossZoneRefMs(),
                sc.net().jitterRefMs(), sc.net().loss());

        return new Of(name.replaceAll("\\.ya?ml$", ""), sc.job(), sc.seed(), sc.kTime(),
                sc.expectedRunRefMs() / 1000, net, List.copyOf(pools), List.copyOf(faults),
                List.copyOf(chaos), List.copyOf(retries));
    }
}
