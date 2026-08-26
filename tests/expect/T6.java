import java.util.*;

/**
 * t6-pipeline — split, map, shuffle, reduce, with nothing going wrong.
 *
 * <p><b>Catches:</b> fan-out collapsing to sequential, and byte-accounting drift.
 * Both are silent: the job still answers correctly, and only the shape of the
 * timeline or the size of a number says that it did so the wrong way.
 */
public final class T6 {
    public static void main(String[] args) {
        var e = Expect.of("t6-pipeline", args);

        // Exact, and observed. Not a projection of anything: either the words came
        // back or they did not.
        var done = e.of("done");
        String answer = done.isEmpty() ? "" : String.valueOf(Expect.detail(done.get(0)).get("value"));
        var truth = WordCount.truth();
        boolean whole = truth.entrySet().stream()
                .allMatch(w -> answer.contains(w.getKey() + "=" + w.getValue()));
        e.note(truth.size() + " distinct words, " +
               truth.values().stream().mapToInt(Integer::intValue).sum() + " in total");
        e.check(whole, "no word was lost and none was counted twice — every one of the "
                + truth.size() + " came back with the count it should have");

        // Every mapper got work. A pipeline where one machine does everything answers
        // just as correctly and teaches nothing.
        Set<String> mapped = new TreeSet<>();
        for (var s : e.spansOf("handler"))
            if (String.valueOf(s.get("label")).endsWith(".Map")) mapped.add(String.valueOf(s.get("vm")));
        e.check(mapped.size() == 4, "all four mappers were given work — " + mapped);

        Set<String> folded = new TreeSet<>();
        for (var s : e.spansOf("handler"))
            if (String.valueOf(s.get("label")).endsWith(".Fold")) folded.add(String.valueOf(s.get("vm")));
        e.check(folded.size() == 2, "and both reducers folded a bucket — " + folded);

        // Fan-out means overlap. If the map phase were sequential the answer would be
        // identical and the gantt would be a staircase.
        int widest = 0;
        var maps = e.spansOf("handler").stream()
                .filter(s -> String.valueOf(s.get("label")).endsWith(".Map")).toList();
        for (var probe : maps) {
            double t = Expect.num(probe.get("t0")) + 0.001;
            int at = 0;
            for (var other : maps)
                if (Expect.num(other.get("t0")) <= t && t <= Expect.num(other.get("t1"))) at++;
            widest = Math.max(widest, at);
        }
        e.check(widest >= 2, "the map phase really ran in parallel — " + widest + " handlers "
                + "were in flight at once, so the gantt is blocks side by side rather than a "
                + "staircase that happens to add up");

        // Bytes are counted on both sides of every call, from the marshaller. Drift
        // between the two is the accounting quietly coming apart.
        long sent = 0, received = 0;
        for (var s : e.spansOf("rpc")) sent += Expect.lng(Expect.detail(s).get("bytes"));
        for (var s : e.spansOf("handler")) received += Expect.lng(Expect.detail(s).get("inBytes"));
        e.note("client counted " + sent + " bytes out, servers counted " + received + " in");
        e.check(sent > 0 && sent == received,
                "what the callers marshalled is exactly what the servers were handed — the "
                + "in-process transport passes a reference and serialises nothing, so both "
                + "numbers come from marshalling explicitly and either could drift alone");

        e.check(e.of("oom").isEmpty() && Boolean.TRUE.equals(e.meta().get("completed")),
                "and it finished without anybody running out of anything, which is what "
                + "makes this the case the harder ones are measured against");
        e.done();
    }
}
