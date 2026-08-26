package losim.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import losim.price.Bill;
import losim.price.PnL;
import losim.price.PriceList;
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
        if (!Files.exists(trace)) throw new IllegalArgumentException("no such trace: " + trace);
        Path list = Path.of(priceFile);
        PriceList prices;
        if (Files.exists(list)) {
            prices = PriceList.load(list);
        } else {
            prices = PriceList.defaults();
            System.out.println("no price list at " + priceFile + "; using the built-in defaults");
        }

        var t = JsonReader.readObject(Files.readString(trace));
        var both = Bill.of(t, prices);

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
