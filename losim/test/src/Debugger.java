import java.util.*;
import java.util.stream.Collectors;
import losim.trace.Telemetry;
import losim.trace.Trace;
import losim.trace.Values;

/**
 * The questions a debugger would have answered, asked of the telemetry instead.
 *
 * <p>This design deliberately has no system-level debugger — no stepper, no
 * break-on-invariant, no time travel — because runs are not reproducible and a
 * stepper that stops the clock is lying about a distributed system anyway. What
 * replaces it is this trace, which makes the trace necessary rather than
 * decorative.
 *
 * <p>So the standard is explicit: <i>if the telemetry cannot answer a question a
 * debugger would have answered, that is a bug in the telemetry.</i> A plain
 * change log answers few of the questions below, because a change log is silent
 * exactly when a system is stuck, which is exactly when you want to look at it.
 */
public class Debugger {

    static int pass = 0, fail = 0;
    static Telemetry tel;

    static void check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
    }

    static double round(double x) { return Math.round(x * 1000) / 1000.0; }

    /** Everything the trace knows about one machine at one instant. */
    static Map<String, Double> stateAt(String vm, double t) {
        int i = tel.tickAt(t);
        var out = new LinkedHashMap<String, Double>();
        if (i < 0) return out;
        tel.series().forEach((k, s) -> {
            if (k.startsWith(vm + ".") && i < s.size()) out.put(k.substring(vm.length() + 1), s.at(i));
        });
        return out;
    }

    static List<Telemetry.Span> openAt(double t) {
        return tel.spans().stream().filter(s -> s.openAt(t)).toList();
    }

    public static void main(String[] args) throws Exception {
        tel = Wordcount.run();
        double end = tel.events().stream().mapToDouble(Telemetry.Event::t).max().orElse(0);
        System.out.printf("a run of %.0f simulated ms: %d events, %d spans, %d ticks at %.1f ms%n%n",
                end, tel.events().size(), tel.spans().size(),
                tel.sampleTimes().length, tel.sampleDtMs());

        double probe = end * 0.45;

        // ------------------------------------------------------------------ Q1
        System.out.printf("Q1  what was w3 holding at t=%.0f ms?%n", probe);
        var st = stateAt("w3", probe);
        System.out.printf("    %s%n", st.entrySet().stream()
                .filter(e -> !e.getKey().equals("vcpu"))
                .map(e -> e.getKey() + "=" + round(e.getValue()))
                .collect(Collectors.joining("  ")));
        check(st.containsKey("retainMb") && st.containsKey("memPct"),
              "state is sampled, so any instant can be read — not only instants that changed");

        // ------------------------------------------------------------------ Q2
        System.out.println("\nQ2  which call was in flight, and since when?");
        var stalled = tel.spans().stream()
                .filter(s -> s.kind.equals("rpc"))
                .max(Comparator.comparingDouble(Telemetry.Span::grossMs)).orElseThrow();
        System.out.printf("    #%d %s -> %s  open %.0f ms  %s%n", stalled.id, stalled.vm,
                stalled.detail.get("to"), stalled.grossMs(), stalled.status);
        check(!openAt(stalled.t0 + stalled.grossMs() / 2).isEmpty(),
              "a call in flight is found by asking, not by subtracting one set of events from another");

        // ------------------------------------------------------------------ Q3
        System.out.println("\nQ3  why did it stall?");
        var ev = tel.events().stream().sorted(Comparator.comparingDouble(Telemetry.Event::t)).toList();
        double ga = 0, gb = 0;
        for (int i = 1; i < ev.size(); i++)
            if (ev.get(i).t() - ev.get(i - 1).t() > gb - ga) { ga = ev.get(i - 1).t(); gb = ev.get(i).t(); }
        System.out.printf("    widest gap between events: %.0f -> %.0f  (%.0f ms of silence)%n",
                ga, gb, gb - ga);
        var openMid = openAt((ga + gb) / 2);
        for (var s : openMid)
            System.out.printf("      open: #%d %-8s %-7s %s %s%n", s.id, s.kind, s.vm, s.label,
                    s.detail.isEmpty() ? "" : s.detail);
        final double lo = ga, hi = gb;
        long covering = Arrays.stream(tel.sampleTimes()).filter(t -> t >= lo && t <= hi).count();
        System.out.printf("      samples covering that silence: %d%n", covering);
        check(!openMid.isEmpty(), "the stall is explained by an open span, not by an absence of events");
        check(covering > 10,
              "and the machines are observable throughout it — which is the whole reason for a "
              + "dense channel");

        // ------------------------------------------------------------------ Q4
        System.out.println("\nQ4  whose memory came closest to its cap, and when?");
        String worstVm = null;
        double worstPct = -1, worstAt = -1;
        var times = tel.sampleTimes();
        for (var e : tel.series().entrySet()) {
            if (!e.getKey().endsWith(".memPct")) continue;
            var s = e.getValue();
            for (int i = 0; i < s.size(); i++)
                if (s.at(i) > worstPct) {
                    worstPct = s.at(i);
                    worstVm = s.vm;
                    worstAt = times[Math.min(i, times.length - 1)];
                }
        }
        System.out.printf("    %s reached %.1f%% of its cap at t=%.0f ms%n", worstVm, worstPct, worstAt);
        check(worstPct > 0 && worstVm != null,
              "memory is a per-machine series against that machine's own cap, not a fleet scalar");
        check(worstAt >= 0, "and the moment it peaked is recoverable");

        // ------------------------------------------------------------------ Q5
        System.out.println("\nQ5  what made a machine run out of memory?");
        var ooms = tel.events().stream().filter(e -> e.kind().equals("oom")).toList();
        for (var o : ooms) System.out.printf("    t=%.0f ms  %s  %s%n", o.t(), o.vm(), o.detail());
        check(!ooms.isEmpty(),
              "an out-of-memory is an event naming machine, resource, cap, measured demand and cause");
        if (!ooms.isEmpty())
            check(ooms.get(0).detail().keySet().containsAll(Set.of("capMb", "demandMb", "cause")),
                  "and it says what the demand actually was, rather than that a limit was hit");

        // ------------------------------------------------------------------ Q6
        System.out.println("\nQ6  how did this call come to happen?");
        var byId = tel.spans().stream().collect(Collectors.toMap(s -> s.id, s -> s));
        var deepest = tel.spans().stream()
                .filter(s -> s.kind.equals("handler"))
                .max(Comparator.comparingInt(s -> depth(s, byId))).orElseThrow();
        var chain = new ArrayList<Telemetry.Span>();
        for (var s = deepest; s != null; s = byId.get(s.parent)) { chain.add(s); if (s.parent == 0) break; }
        Collections.reverse(chain);
        for (int i = 0; i < chain.size(); i++)
            System.out.printf("    %s#%d %-8s %-7s %s%n", "  ".repeat(i), chain.get(i).id,
                    chain.get(i).kind, chain.get(i).vm, chain.get(i).label);
        check(chain.size() >= 3,
              "a span names its parent across the RPC boundary, so a call has a distributed stack");
        check(chain.stream().map(s -> s.vm).distinct().count() > 1,
              "and that stack spans more than one machine, which is the only reason to have it");

        // ------------------------------------------------------------------ Q7
        System.out.println("\nQ7  was a machine blocked on a core, or waiting on someone else?");
        // Asked at the busiest instant of the run, which the series themselves name.
        double busiest = probe;
        double most = -1;
        for (int i = 0; i < times.length; i++) {
            double sum = 0;
            for (var e : tel.series().entrySet())
                if (e.getKey().endsWith(".inflight") && i < e.getValue().size()) sum += e.getValue().at(i);
            if (sum > most) { most = sum; busiest = times[i]; }
        }
        System.out.printf("    the fleet is busiest at t=%.0f ms%n", busiest);
        for (String vm : List.of("master", "w0", "w1")) {
            var s = stateAt(vm, busiest);
            System.out.printf("    %-7s inflight=%.0f queued=%.0f busy=%.0f%%%n", vm,
                    s.getOrDefault("inflight", 0.0), s.getOrDefault("queued", 0.0),
                    s.getOrDefault("busyPct", 0.0));
        }
        check(tel.series().containsKey("master.queued") && tel.series().containsKey("master.busyPct"),
              "queueing and occupancy are separate channels, so 'slow' can be told from 'waiting'");

        // ------------------------------------------------------------------ Q8
        System.out.println("\nQ8  is any part of the run unobserved?");
        double worstGap = 0;
        for (int i = 1; i < times.length; i++) worstGap = Math.max(worstGap, times[i] - times[i - 1]);
        System.out.printf("    widest unsampled window: %.1f ms against a %.1f ms cadence%n",
                worstGap, tel.sampleDtMs());
        check(worstGap < tel.sampleDtMs() * 20, "no window of the run is left unobserved");

        // ------------------------------------------------------------------ Q9
        System.out.println("\nQ9  did every call finish?");
        System.out.printf("    spans still open at the end: %d%n", tel.dangling().size());
        check(tel.dangling().isEmpty(),
              "every span closed exactly once — a dangling span is a telemetry bug, not a finding");

        // ----------------------------------------------------------------- Q10
        System.out.println("\nQ10 what did each machine actually compute?");
        var handlers = tel.spans().stream().filter(s -> s.kind.equals("handler")).toList();
        for (var s : handlers.stream().limit(3).toList())
            System.out.printf("    %-7s %-24s %s  ->  %s%n", s.vm, s.label,
                    Values.summary(s.detail.get("arg")), Values.summary(s.detail.get("result")));
        for (var s : handlers.stream().filter(x -> x.detail.containsKey("error")).toList())
            System.out.printf("    %-7s %-24s %s  ->  FAILED: %s%n", s.vm, s.label,
                    Values.summary(s.detail.get("arg")), s.detail.get("error"));
        var merge = tel.spans().stream().filter(s -> s.kind.equals("compute")).findFirst();
        merge.ifPresent(s -> System.out.printf("    %-7s %-24s ->  %s%n",
                s.vm, s.label, Values.summary(s.detail.get("result"))));
        long accounted = handlers.stream()
                .filter(s -> s.detail.containsKey("arg"))
                .filter(s -> s.detail.containsKey("result") || s.detail.containsKey("error")).count();
        System.out.printf("    %d of %d handled calls carry an argument and either a result or a reason%n",
                accounted, handlers.size());
        check(!handlers.isEmpty() && accounted == handlers.size(),
              "every handled call records what went in and what came out — or why it did not, "
              + "because the one call worth looking at must not be the one blank row");
        check(merge.isPresent() && merge.get().detail.containsKey("result"),
              "and a computation no RPC carried is telemetrized too, with its answer");
        var done = tel.events().stream().filter(e -> e.kind().equals("done")).findFirst();
        check(done.isPresent() && done.get().detail().get("value") instanceof Map,
              "the job's own answer is structured, not a toString()");

        budget();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static int depth(Telemetry.Span s, Map<Long, Telemetry.Span> byId) {
        int d = 0;
        for (var x = s; x != null && x.parent != 0; x = byId.get(x.parent)) if (++d > 32) break;
        return d;
    }

    // ------------------------------------------------------------------- budget

    /** What the dense channel costs. A trace nobody can load is its own kind of failure. */
    static void budget() {
        System.out.println("\n--- what it costs ---");
        long evBytes = 0;
        for (var e : tel.events())
            evBytes += 40 + e.kind().length() + e.vm().length() + String.valueOf(e.detail()).length();
        long spBytes = 0;
        for (var s : tel.spans())
            spBytes += 80 + s.label.length() + String.valueOf(s.detail).length();

        int points = 0;
        for (var s : tel.series().values()) points += s.size();
        long rawSeries = points * 7L;                       // ~7 characters a number, as JSON
        long encoded = 0;
        var forms = new TreeMap<String, Integer>();
        for (var s : tel.series().values()) {
            var enc = Telemetry.encode(s);
            encoded += enc.weight();
            forms.merge(enc.form(), 1, Integer::sum);
        }
        System.out.printf("    events %5d items %8.1f KB%n", tel.events().size(), evBytes / 1024.0);
        System.out.printf("    spans  %5d items %8.1f KB%n", tel.spans().size(), spBytes / 1024.0);
        System.out.printf("    series %5d points%8.1f KB raw, %.1f KB encoded %s%n",
                points, rawSeries / 1024.0, encoded / 1024.0, forms);
        double saved = 100.0 * (rawSeries - encoded) / rawSeries;
        System.out.printf("    quantise, then take the smallest of constant/runs/raw: %.1f%% smaller%n",
                saved);
        check(saved > 90,
              String.format("the dense channel encodes away %.0f%% of itself, because most of what a "
                          + "machine reports does not change", saved));

        String json = Trace.of(tel).toJson();
        System.out.printf("    the whole trace as JSON: %.1f KB%n", json.length() / 1024.0);
        check(json.length() < 400_000,
              "and the trace of a run this size stays small enough for a viewer to load whole");
    }
}
