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
 * A scenario using {@code network:}, a pool's {@code memoryMb}, a retry's
 * {@code multiplier} — anything present here and absent from the Draft model
 * comes back naming the key and the line, never a Draft missing it silently.
 */
public final class Draft {
    private Draft() {}

    public record Pool(String name, int count, String instance, List<String> zones, List<String> runs) {}
    public record Kill(double atRefMs, String target, double restartAfterRefMs) {}
    public record Chaos(String kind, double everyRefMs, String among, double forRefMs, double factor) {}
    public record Retry(String method, int attempts, double backoffRefMs, boolean unsafe) {}

    public record Of(String name, String job, long seed, double expectedRunRefSeconds,
                      List<Pool> pools, List<Kill> kills, List<Chaos> chaos, List<Retry> retries) {}

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

        for (String key : List.of("kTime", "network", "tightMargin", "mode", "workload")) {
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

        var kills = new ArrayList<Kill>();
        for (Node f : root.opt("faults").list()) {
            if (!f.opt("kill").present()) throw f.fail(
                    "a fault that isn't kill: — the form only edits one-time kill faults yet."
                    + " Edit the file directly, then open it here again.");
            for (String key : List.of("notice", "for")) {
                if (f.opt(key).present()) throw f.at(key).fail(
                        "this kill fault uses " + key + ":, which the form doesn't have a control"
                        + " for yet. Edit the file directly, then open it here again.");
            }
            kills.add(new Kill(f.at("at").refMs(), f.at("kill").str(),
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

        return new Of(name.replaceAll("\\.ya?ml$", ""), sc.job(), sc.seed(),
                sc.expectedRunRefMs() / 1000, List.copyOf(pools), List.copyOf(kills),
                List.copyOf(chaos), List.copyOf(retries));
    }
}
