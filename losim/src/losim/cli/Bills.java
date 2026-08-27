package losim.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import losim.price.Bill;
import losim.price.PnL;
import losim.price.PriceList;
import losim.trace.Json;
import losim.trace.JsonReader;

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
     * With {@code --json}, the same five buckets as data, plus the rates they were
     * computed from.
     *
     * <p>The rates are the part that matters. The viewer accrues cost as the film
     * plays, which the totals here cannot give it — a bill is what a run cost, and
     * watching profit go negative halfway through a cascade needs to know what it
     * cost *so far*. So the viewer has to do the arithmetic itself, and the only
     * thing that stops it and this becoming two accountants who will eventually
     * disagree is that both work from these numbers and the total is checked against
     * that one.
     */
    public static int run(Path trace, String priceFile, boolean asJson) throws Exception {
        if (!Files.exists(trace)) throw new IllegalArgumentException("no such trace: " + trace);
        Path list = Path.of(priceFile);
        PriceList prices;
        if (Files.exists(list)) {
            prices = PriceList.load(list);
        } else {
            prices = PriceList.defaults();
            // On stderr: a note printed onto stdout would be the first line of what
            // is supposed to be a JSON document.
            System.err.println("no price list at " + priceFile + "; using the built-in defaults");
        }

        var t = JsonReader.readObject(Files.readString(trace));
        var both = Bill.of(t, prices);

        if (asJson) {
            var out = new java.util.LinkedHashMap<String, Object>();
            out.put("trace", trace.toString());
            out.put("rates", prices.asMap());
            out.putAll(both.asMap());
            System.out.println(Json.write(out));
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
        for (String bucket : PnL.BUCKETS)
            System.out.printf("  %-12s %s%n", bucket, wrap(PnL.EXPLANATIONS.get(bucket)));
        return 0;
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
