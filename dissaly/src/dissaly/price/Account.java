package dissaly.price;

import java.util.*;
import java.util.Locale;

/**
 * What one run cost, and what happened to it.
 *
 * <p>Three buckets, reported separately rather than summed, because they are three
 * different kinds of decision. Replication triples capacity and adds to build —
 * and summing those into one number hides exactly the trade a student is meant to
 * see.
 *
 * <p><b>What broke is counted, not charged.</b> A timeout, a lost machine and a
 * late finish are facts about the run; what they cost an organisation is a
 * number this course does not have. It used to invent one — a franc a second for
 * being late, two rappen a timeout — and on a run where everything failed that
 * invention was 99% of the bill, so the total said more about the made-up rates
 * than about the design. They are listed now: how many, of what, and how late.
 *
 * <p>A bucket can also be <b>unpriceable</b>, and that is not the same as zero. Where
 * the engine refused to project the quantity a line is made of, the line is absent
 * with the reason attached, because a bill that quietly leaves out its largest term
 * is worse than no bill at all.
 */
public final class Account {

    public static final List<String> BUCKETS = List.of("build", "capacity", "consumption");

    public static final Map<String, String> EXPLANATIONS = Map.of(
            "build", "Engineering time to construct this design, spread over its life. "
                    + "Every mechanism you add is days of somebody's work, carried whether or "
                    + "not the thing it protects against ever happens.",
            "capacity", "The cluster you reserved, priced for the whole period. An idle machine "
                    + "costs exactly as much as a busy one.",
            "consumption", "What the work actually burned: storage and egress. "
                    + "This is the line a better algorithm moves.");

    private final List<LineItem> items = new ArrayList<>();
    private final List<Counted> counted = new ArrayList<>();
    private final List<String[]> unpriceable = new ArrayList<>();
    public final String currency;
    public final String scale;

    public Account(String currency, String scale) {
        this.currency = currency;
        this.scale = scale;
    }

    public void add(String bucket, String what, double qty, String unit, double unitPrice,
                    String why) {
        if (qty == 0 && unitPrice == 0) return;
        items.add(new LineItem(bucket, what, round(qty), unit, unitPrice,
                round(qty * unitPrice), why));
    }

    /** Something that happened, counted and not charged. */
    public record Counted(String what, double quantity, String unit, String why) {
        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("what", what);
            m.put("quantity", round(quantity));
            m.put("unit", unit);
            m.put("why", why);
            return m;
        }
    }

    /**
     * What broke, as a count rather than a charge.
     *
     * <p>Never reaches {@link #cost()}. That is the whole point: the number is
     * real and its price is not, so the bill carries the first and refuses to
     * make up the second.
     */
    public void count(String what, double qty, String unit, String why) {
        if (qty <= 0) return;
        counted.add(new Counted(what, qty, unit, why));
    }

    public List<Counted> counted() { return List.copyOf(counted); }

    /**
     * A line that cannot be drawn, and why.
     *
     * <p>The quantity was refused by the engine, so there is no honest number to put
     * here. Leaving the line out silently would make the total look complete.
     */
    public void cannotPrice(String bucket, String what, String why) {
        unpriceable.add(new String[]{bucket, what, why});
    }

    public Map<String, String> unpriceable() {
        var out = new LinkedHashMap<String, String>();
        for (String[] row : unpriceable) out.put(row[1], row[2]);
        return out;
    }

    public boolean complete() { return unpriceable.isEmpty(); }

    static double round(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public List<LineItem> items() { return List.copyOf(items); }

    public Map<String, Double> byBucket() {
        var out = new LinkedHashMap<String, Double>();
        for (String b : BUCKETS) out.put(b, 0.0);
        for (LineItem i : items) out.merge(i.bucket(), i.amount(), Double::sum);
        out.replaceAll((k, v) -> round(v));
        return out;
    }

    public double cost() {
        var b = byBucket();
        return round(b.get("build") + b.get("capacity") + b.get("consumption"));
    }

    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("scale", scale);
        m.put("currency", currency);
        m.put("buckets", byBucket());
        m.put("cost", cost());
        var lines = new ArrayList<Object>();
        for (LineItem i : items) lines.add(i.asMap());
        m.put("lines", lines);
        if (!counted.isEmpty()) {
            var out = new ArrayList<Object>();
            for (Counted c : counted) out.add(c.asMap());
            m.put("counted", out);
        }
        if (!unpriceable.isEmpty()) m.put("unpriceable", unpriceable());
        return m;
    }

    public String render() {
        var sb = new StringBuilder();
        for (String bucket : BUCKETS) {
            for (LineItem i : items) {
                if (!i.bucket().equals(bucket)) continue;
                sb.append(String.format(Locale.ROOT, "  %-11s %-30s %12.4g %-14s %s %,12.2f%n",
                        i.bucket(), i.what(), i.quantity(), i.unit(), currency, i.amount()));
            }
            // In its own bucket, so that a bucket totalling zero is visibly a bucket
            // nobody could fill in rather than one that genuinely cost nothing.
            for (String[] row : unpriceable) {
                if (!row[0].equals(bucket)) continue;
                sb.append(String.format(Locale.ROOT, "  %-11s %-30s %12s %-14s %s %12s%n      %s%n",
                        row[0], row[1], "-", "", currency, "refused", row[2]));
            }
        }
        sb.append("  ").append("-".repeat(84)).append('\n');
        // Under the money and outside the total, because that is exactly what
        // these are: counted, and not priced.
        if (!counted.isEmpty()) {
            sb.append("  counted, and not priced\n");
            for (Counted c : counted)
                sb.append(String.format(Locale.ROOT, "  %-11s %-30s %12.4g %-14s %s%n",
                        "", c.what(), c.quantity(), c.unit(), "not priced"));
            sb.append("  ").append("-".repeat(84)).append('\n');
        }
        var b = byBucket();
        for (String bucket : BUCKETS)
            sb.append(String.format(Locale.ROOT, "  %-12s %s %,12.2f%n", bucket, currency, b.get(bucket)));
        sb.append(String.format(Locale.ROOT, "  %-12s %s %,12.2f%s%n", "TOTAL COST", currency, cost(),
                complete() ? "" : "   (of what could be priced)"));
        return sb.toString();
    }
}
