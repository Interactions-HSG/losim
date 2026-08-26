package losim.price;

import java.util.*;

/**
 * A profit-and-loss account for one run.
 *
 * <p>Five buckets, reported separately rather than summed, because they are five
 * different kinds of decision. Replication triples capacity and adds to build in
 * order to empty incidents — and summing those into one number hides exactly the
 * trade a student is meant to see.
 *
 * <p>A bucket can also be <b>unpriceable</b>, and that is not the same as zero. Where
 * the engine refused to project the quantity a line is made of, the line is absent
 * with the reason attached, because a bill that quietly leaves out its largest term
 * is worse than no bill at all.
 */
public final class PnL {

    public static final List<String> BUCKETS =
            List.of("revenue", "build", "capacity", "consumption", "incidents");

    public static final Map<String, String> EXPLANATIONS = Map.of(
            "build", "Engineering time to construct this design, spread over its life. "
                    + "Every mechanism you add is days of somebody's work, carried whether or "
                    + "not the thing it protects against ever happens.",
            "capacity", "The fleet you reserved, priced for the whole period. An idle machine "
                    + "costs exactly as much as a busy one.",
            "consumption", "What the work actually burned: machine time, storage, egress. "
                    + "This is the line a better algorithm moves.",
            "incidents", "What failure cost: reruns, lost work, being late. Zero until "
                    + "something breaks, then large — this is the bucket fault tolerance is "
                    + "bought to empty.",
            "revenue", "What the service earned, and only when it works.");

    private final List<LineItem> items = new ArrayList<>();
    private final List<String[]> unpriceable = new ArrayList<>();
    public final String currency;
    public final String scale;

    public PnL(String currency, String scale) {
        this.currency = currency;
        this.scale = scale;
    }

    public void add(String bucket, String what, double qty, String unit, double unitPrice,
                    String why) {
        if (qty == 0 && unitPrice == 0) return;
        items.add(new LineItem(bucket, what, round(qty), unit, unitPrice,
                round(qty * unitPrice), why));
    }

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

    /** Revenue minus everything it cost to earn. */
    public double profit() {
        var b = byBucket();
        return round(b.get("revenue") - b.get("build") - b.get("capacity")
                - b.get("consumption") - b.get("incidents"));
    }

    public double cost() {
        var b = byBucket();
        return round(b.get("build") + b.get("capacity") + b.get("consumption")
                + b.get("incidents"));
    }

    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("scale", scale);
        m.put("currency", currency);
        m.put("buckets", byBucket());
        m.put("cost", cost());
        m.put("profit", profit());
        var lines = new ArrayList<Object>();
        for (LineItem i : items) lines.add(i.asMap());
        m.put("lines", lines);
        if (!unpriceable.isEmpty()) m.put("unpriceable", unpriceable());
        return m;
    }

    public String render() {
        var sb = new StringBuilder();
        for (String bucket : BUCKETS) {
            for (LineItem i : items) {
                if (!i.bucket().equals(bucket)) continue;
                sb.append(String.format("  %-11s %-30s %12.4g %-14s %s %9.4f%n",
                        i.bucket(), i.what(), i.quantity(), i.unit(), currency, i.amount()));
            }
            // In its own bucket, so that a bucket totalling zero is visibly a bucket
            // nobody could fill in rather than one that genuinely cost nothing.
            for (String[] row : unpriceable) {
                if (!row[0].equals(bucket)) continue;
                sb.append(String.format("  %-11s %-30s %12s %-14s %s %9s%n      %s%n",
                        row[0], row[1], "-", "", currency, "refused", row[2]));
            }
        }
        sb.append("  ").append("-".repeat(84)).append('\n');
        var b = byBucket();
        for (String bucket : BUCKETS)
            sb.append(String.format("  %-12s %s %12.4f%n", bucket, currency, b.get(bucket)));
        sb.append(String.format("  %-12s %s %12.4f%n", "TOTAL COST", currency, cost()));
        sb.append(String.format("  %-12s %s %12.4f%s%n", "PROFIT", currency, profit(),
                complete() ? "" : "   (of what could be priced)"));
        return sb.toString();
    }
}
