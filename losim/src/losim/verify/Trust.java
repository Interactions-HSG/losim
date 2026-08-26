package losim.verify;

import java.nio.file.Path;
import java.util.*;
import losim.scenario.Scenario;
import losim.trace.Telemetry;

/**
 * Which machines' numbers mean what they say, and which do not.
 *
 * <p>The verifier finds things in classes; a run reports things about machines. This
 * is the join: a machine is flagged by whatever its own services do, and the machine
 * the job runs on is flagged by the job as well. Two machines serving the same class
 * are both flagged, which is right — the same code makes the same figure wrong on
 * each of them.
 *
 * <p>Nothing here stops a run. What it produces is a caveat with an address:
 * <i>w3 read the real clock, so its timeline is not projectable</i> — attached to the
 * machine, in the trace, next to the figure it undermines.
 */
public final class Trust {

    /** No check was asked for: no classpath was given, so nothing is claimed either way. */
    public static Trust unchecked() {
        return new Trust(Map.of(), List.of(), List.of(), null, false);
    }

    private final Map<String, List<Finding>> byMachine;
    private final List<String> walked, generated;
    private final String unavailable;
    private final boolean checked;

    private Trust(Map<String, List<Finding>> byMachine, List<String> walked,
                  List<String> generated, String unavailable, boolean checked) {
        this.byMachine = byMachine;
        this.walked = walked;
        this.generated = generated;
        this.unavailable = unavailable;
        this.checked = checked;
    }

    /**
     * Checks every machine in the fleet against the code it will run.
     *
     * <p>The job is attributed to the machine it runs on — the first in the file —
     * because that is whose pool executes it and whose counters it lands on.
     */
    public static Trust of(Scenario s, List<Path> code) {
        if (code.isEmpty()) return unchecked();
        var verifier = Verifier.over(code);
        var services = new HashSet<String>();
        for (var m : s.machines()) services.addAll(m.serves());

        var byMachine = new LinkedHashMap<String, List<Finding>>();
        var walked = new TreeSet<String>();
        var generated = new TreeSet<String>();
        String unavailable = null;
        boolean first = true;

        for (var m : s.machines()) {
            var roots = new ArrayList<>(m.serves());
            if (first) { roots.add(s.job()); first = false; }
            var report = verifier.from(roots, services);
            walked.addAll(report.walked());
            generated.addAll(report.generated());
            if (report.unavailable() != null) unavailable = report.unavailable();
            if (!report.findings().isEmpty()) byMachine.put(m.name(), report.findings());
        }
        // Nothing walked is not the same as nothing wrong, and reporting it as clean
        // would be the one failure mode a check like this cannot afford.
        if (unavailable == null && walked.isEmpty() && generated.isEmpty())
            unavailable = "none of the classes this scenario names are on the classpath it was"
                    + " given, so nothing was read. A jar is not walked; point --cp at the"
                    + " directory the lab compiles to.";
        return new Trust(byMachine, List.copyOf(walked), List.copyOf(generated),
                         unavailable, true);
    }

    // -------------------------------------------------------------------- reading

    /** Whether the check ran at all. A run given no classpath claims nothing. */
    public boolean checked()      { return checked && unavailable == null; }
    public String unavailable()   { return unavailable; }
    /** The lab's own classes the walk actually read. */
    public List<String> walked()  { return walked; }
    /** Classes skipped because protoc wrote them — the ones a hard gate would have to argue with. */
    public List<String> generated() { return generated; }

    public boolean clean() { return byMachine.isEmpty(); }

    public Set<String> machines() { return byMachine.keySet(); }

    public List<Finding> findingsFor(String machine) {
        return byMachine.getOrDefault(machine, List.of());
    }

    public Set<Flag> flagsFor(String machine) {
        var out = EnumSet.noneOf(Flag.class);
        for (Finding f : findingsFor(machine)) out.add(f.flag());
        return out;
    }

    /**
     * The machines whose flags undermine one measured resource.
     *
     * <p>This is what makes a flag load-bearing rather than decorative: a projected
     * makespan over a fleet that reads the real clock is a projection of the host's
     * afternoon, and the number is worth exactly as much as that.
     */
    public List<String> undermining(String resource) {
        var out = new ArrayList<String>();
        for (var e : byMachine.entrySet())
            for (Finding f : e.getValue())
                if (f.flag().undermines().contains(resource)) { out.add(e.getKey()); break; }
        return out;
    }

    /**
     * What to print beside one measured resource: who, and what it costs.
     *
     * <p>Beside it rather than beneath the table, because an error bar and a caveat
     * answer different questions and only one of them is visible in the number. An
     * error bar says how well the law was fitted; this says whether what it was fitted
     * to meant anything.
     */
    public List<String> caveats(String resource) {
        var byFlag = new LinkedHashMap<Flag, Set<String>>();
        for (var e : byMachine.entrySet())
            for (Finding f : e.getValue())
                if (f.flag().undermines().contains(resource))
                    byFlag.computeIfAbsent(f.flag(), k -> new LinkedHashSet<>()).add(e.getKey());

        var out = new ArrayList<String>();
        for (var e : byFlag.entrySet())
            out.add(String.join(", ", e.getValue()) + ": " + e.getKey().consequence);
        return out;
    }

    // -------------------------------------------------------------------- writing

    /**
     * Puts the flags on the machines, in the trace.
     *
     * <p>An event rather than a footnote: the event channel is already per-machine,
     * which is exactly where someone reading a machine's memory figure needs to be
     * told that it is a lower bound.
     */
    public void recordInto(Telemetry tel) {
        if (unavailable != null) {
            tel.event("-", "trust_unchecked", "why", unavailable);
            return;
        }
        for (var e : byMachine.entrySet()) {
            var flags = new ArrayList<String>();
            var undermined = new TreeSet<String>();
            var sites = new ArrayList<Object>();
            for (Finding f : e.getValue()) {
                if (!flags.contains(f.flag().key)) flags.add(f.flag().key);
                undermined.addAll(f.flag().undermines());
                var m = new LinkedHashMap<String, Object>();
                m.put("rule", f.rule().name().toLowerCase());
                m.put("flag", f.flag().key);
                m.put("where", f.where());
                m.put("inside", f.inside());
                m.put("what", f.what());
                sites.add(m);
            }
            tel.event(e.getKey(), "trust", "flags", flags,
                      "untrustworthy", new ArrayList<>(undermined),
                      "says", says(e.getKey(), e.getValue()), "sites", sites);
        }
    }

    /** The sentence to put beside the machine: what it did, and what that costs. */
    private static String says(String machine, List<Finding> findings) {
        var byRule = new LinkedHashMap<Rule, Integer>();
        for (Finding f : findings) byRule.merge(f.rule(), 1, Integer::sum);
        var parts = new ArrayList<String>();
        for (var e : byRule.entrySet())
            parts.add(e.getKey().because + " (" + e.getKey().flag.consequence + ")");
        return machine + " " + String.join("; ", parts);
    }

    // ------------------------------------------------------------------ reporting

    /** What the command line prints. Grouped by what was done, not by which class did it. */
    public String describe() {
        var sb = new StringBuilder();
        if (unavailable != null)
            return "  trust: not checked — " + unavailable + "\n";
        if (!checked) return "";
        if (clean())
            return String.format("  trust: %d classes checked, nothing outside the simulated"
                    + " world%n", walked.size());

        // One block per rule, listing the machines it applies to: eight workers
        // serving one class is one mistake, not eight.
        var machinesByRule = new LinkedHashMap<Rule, Set<String>>();
        var sitesByRule = new LinkedHashMap<Rule, Set<String>>();
        for (var e : byMachine.entrySet())
            for (Finding f : e.getValue()) {
                machinesByRule.computeIfAbsent(f.rule(), r -> new LinkedHashSet<>()).add(e.getKey());
                sitesByRule.computeIfAbsent(f.rule(), r -> new LinkedHashSet<>()).add(f.describe());
            }

        sb.append(String.format("  trust: %d machine%s report figures that do not mean what"
                + " they say%n", byMachine.size(), byMachine.size() == 1 ? "" : "s"));
        for (var e : machinesByRule.entrySet()) {
            // "each" so that one rule over eight workers still reads as one sentence,
            // and still agrees with the "its" the consequence is written in.
            sb.append(String.format("    %s%n      %s%s, so %s%n",
                    String.join(", ", e.getValue()), e.getValue().size() > 1 ? "each " : "",
                    e.getKey().because, e.getKey().flag.consequence));
            for (String site : sitesByRule.get(e.getKey()))
                sb.append("        ").append(site).append('\n');
        }
        sb.append("    Nothing was stopped: each of these is a wrong number, not a broken run.\n");
        return sb.toString();
    }
}
