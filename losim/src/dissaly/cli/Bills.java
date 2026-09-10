package dissaly.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import dissaly.price.Bill;
import dissaly.price.Account;
import dissaly.price.PriceList;
import dissaly.trace.Json;
import dissaly.trace.JsonReader;

/**
 * {@code losim bill} — what a run cost, and what the job it modelled would cost.
 *
 * <p>Five buckets, printed apart rather than summed, because they are five different
 * kinds of decision and adding them up hides the trade. Replication triples capacity
 * and adds to build in order to empty incidents; one number cannot say that.
 */
public final class Bills {
    private Bills() {}

    public static int run(Path trace, String priceFile) throws Exception {
        return run(trace, priceFile, false);
    }

    /**
     * With {@code --json}, the same four buckets as data, plus the rates they were
     * computed from.
     *
     * <p>The rates are the part that matters. The viewer accrues cost as the film
     * plays, which the totals here cannot give it — a bill is what a run cost, and
     * watching the incidents bucket fill up halfway through a cascade needs to know
     * what it cost *so far*. So the viewer has to do the arithmetic itself, and the
     * only thing that stops it and this becoming two accountants who will eventually
     * disagree is that both work from these numbers and the total is checked against
     * that one.
     */
    public static int run(Path trace, String priceFile, boolean asJson) throws Exception {
        if (!Files.exists(trace)) throw new IllegalArgumentException("no such trace: " + trace);
        PriceList prices = pricesFor(priceFile);

        var t = JsonReader.readObject(Files.readString(trace));
        var both = Bill.of(t, prices);

        if (asJson) {
            System.out.println(asJson(trace, prices, both));
            return 0;
        }

        System.out.printf("%s%n%n", trace);
        System.out.println("what happened");
        System.out.print(both.observed().render());

        if (both.projected() != null) {
            System.out.printf("%nwhat it is a model of%n");
            System.out.print(both.projected().render());
            if (!both.projected().complete())
                System.out.println("""

                      A line the engine refused is a line nobody can fill in. Capacity is
                      usually the largest of them and the one that depends on the timeline,
                      which is the noisiest thing losim measures — so the uncertainty in what
                      a design costs is rarely where anyone expects to find it.""");
        }

        System.out.println();
        for (String bucket : Account.BUCKETS)
            System.out.printf("  %-12s %s%n", bucket, wrap(Account.EXPLANATIONS.get(bucket)));
        return 0;
    }

    /** The price list named, the one losim ships under that name, or the defaults. */
    private static PriceList pricesFor(String priceFile) throws Exception {
        Path list = Path.of(priceFile);
        if (Files.exists(list)) return PriceList.load(list);
        // No file, but this may be a region losim ships. A lab carries no copy of
        // the price lists — it resolves losim from Maven — and billing it at the
        // defaults because of that would answer a question about Frankfurt when
        // somebody asked about Tokyo.
        PriceList bundled = PriceList.bundled(list.getFileName().toString());
        if (bundled != null) return bundled;
        // On stderr: a note printed onto stdout would be the first line of what
        // is supposed to be a JSON document.
        System.err.println("no price list at " + priceFile
                + " and none of that name inside losim; using the built-in defaults");
        return PriceList.defaults();
    }

    /**
     * The same document {@code --json} prints, for a caller that wants it rather
     * than a terminal.
     *
     * <p>So that sweeping a hundred traces into the viewer is a hundred reads
     * instead of a hundred JVMs — and so there is one bill, not a printed one and
     * a written one that can come to disagree.
     */
    public static String json(Path trace, String priceFile) throws Exception {
        PriceList prices = pricesFor(priceFile);
        var t = JsonReader.readObject(Files.readString(trace));
        return asJson(trace, prices, Bill.of(t, prices));
    }

    private static String asJson(Path trace, PriceList prices, Bill.Both both) {
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("trace", trace.toString());
        out.put("rates", prices.asMap());
        out.putAll(both.asMap());
        return Json.write(out);
    }

    /** Wrapped to the width of the rest of the output, indented under its bucket. */
    private static String wrap(String text) {
        var sb = new StringBuilder();
        int column = 0;
        for (String word : text.split(" ")) {
            if (column + word.length() > 74) { sb.append("\n").append(" ".repeat(15)); column = 0; }
            sb.append(word).append(' ');
            column += word.length() + 1;
        }
        return sb.toString().stripTrailing();
    }
}
