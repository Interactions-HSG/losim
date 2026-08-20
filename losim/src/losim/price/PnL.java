package losim.price;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A profit-and-loss account for one run.
 *
 * Five buckets, reported separately rather than summed, because they are five
 * different kinds of decision. Replication triples capacity and adds to build in
 * order to empty incidents — and summing those into one number hides exactly the
 * trade the student is meant to see.
 */
public final class PnL {

    public static final List<String> BUCKETS =
            List.of("revenue", "build", "capacity", "consumption", "incidents");

    public static final Map<String, String> EXPLANATIONS = Map.of(
            "build", "Engineering time to construct this design, spread over its life. "
                    + "Every mechanism you add is days of somebody's work, carried whether or not "
                    + "the thing it protects against ever happens.",
            "capacity", "The fleet you reserved, priced for the whole period. An idle machine "
                    + "costs exactly as much as a busy one.",
            "consumption", "What the work actually burned: machine time, storage, egress. "
                    + "This is the line a better algorithm moves.",
            "incidents", "What failure cost: reruns, lost work, being late. Zero until something "
                    + "breaks, then large — this is the bucket fault tolerance is bought to empty.",
            "revenue", "What the service earned, and only when it works."
    );

    private final List<LineItem> items = new ArrayList<>();
    public final String currency;

    public PnL(String currency) { this.currency = currency; }

    public void add(String bucket, String what, double qty, String unit, double unitPrice, String why) {
        if (qty == 0 && unitPrice == 0) return;
        items.add(new LineItem(bucket, what, qty, unit, unitPrice, round(qty * unitPrice), why));
    }

    static double round(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public List<LineItem> items() { return List.copyOf(items); }

    public Map<String, Double> byBucket() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String b : BUCKETS) out.put(b, 0.0);
        for (LineItem i : items) out.merge(i.bucket(), i.amount(), Double::sum);
        out.replaceAll((k, v) -> round(v));
        return out;
    }

    /** Revenue minus everything it cost to earn. */
    public double profit() {
        Map<String, Double> b = byBucket();
        return round(b.get("revenue") - b.get("build") - b.get("capacity")
                - b.get("consumption") - b.get("incidents"));
    }

    public double cost() {
        Map<String, Double> b = byBucket();
        return round(b.get("build") + b.get("capacity") + b.get("consumption") + b.get("incidents"));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", currency);
        m.put("buckets", byBucket());
        m.put("cost", cost());
        m.put("profit", profit());
        List<Object> lines = new ArrayList<>();
        for (LineItem i : items) lines.add(i.asMap());
        m.put("lines", lines);
        return m;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (String bucket : BUCKETS) {
            List<LineItem> inBucket = items.stream().filter(i -> i.bucket().equals(bucket)).toList();
            if (inBucket.isEmpty()) continue;
            for (LineItem i : inBucket) sb.append("  ").append(i.render(currency)).append('\n');
        }
        sb.append("  ").append("-".repeat(78)).append('\n');
        Map<String, Double> b = byBucket();
        for (String bucket : BUCKETS)
            sb.append(String.format("  %-12s %s %9.4f%n", bucket, currency, b.get(bucket)));
        sb.append(String.format("  %-12s %s %9.4f%n", "TOTAL COST", currency, cost()));
        sb.append(String.format("  %-12s %s %9.4f%n", "PROFIT", currency, profit()));
        return sb.toString();
    }
}
