package losim.res;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instance types. Specs only — prices belong to a scenario, not the library,
 * so the on-demand figures here are defaults a price list overrides.
 */
public final class InstanceCatalog {

    private static final Map<String, InstanceSpec> TYPES = new LinkedHashMap<>();

    private static void add(String n, String fam, double vcpu, long memMb, double netGbps,
                            long diskGb, boolean burst, double base, long credits, double price) {
        TYPES.put(n, new InstanceSpec(n, fam, vcpu, memMb, netGbps, diskGb, burst, base, credits, price));
    }

    static {
        // burstable — the straggler generator
        add("t3.nano",    "t3", 2, 512,   0.5, 8,  true, 0.05, 30,  0.0060);
        add("t3.micro",   "t3", 2, 1024,  0.5, 8,  true, 0.10, 60,  0.0120);
        add("t3.small",   "t3", 2, 2048,  1.0, 16, true, 0.20, 120, 0.0240);
        add("t3.medium",  "t3", 2, 4096,  1.0, 16, true, 0.20, 240, 0.0480);
        // balanced
        add("m5.large",   "m5", 2, 8192,  0.75, 32, false, 1.0, 0, 0.1150);
        add("m5.xlarge",  "m5", 4, 16384, 1.25, 32, false, 1.0, 0, 0.2300);
        add("m5.2xlarge", "m5", 8, 32768, 2.5,  32, false, 1.0, 0, 0.4600);
        // compute
        add("c5.large",   "c5", 2, 4096,  0.75, 32, false, 1.0, 0, 0.1020);
        add("c5.xlarge",  "c5", 4, 8192,  1.25, 32, false, 1.0, 0, 0.2040);
        add("c5.4xlarge", "c5", 16, 32768, 5.0, 32, false, 1.0, 0, 0.8160);
        // memory
        add("r5.large",   "r5", 2, 16384, 0.75, 32, false, 1.0, 0, 0.1520);
        add("r5.xlarge",  "r5", 4, 32768, 1.25, 32, false, 1.0, 0, 0.3040);
        // storage
        add("i3.large",   "i3", 2, 15616, 0.75, 475, false, 1.0, 0, 0.1720);
    }

    private InstanceCatalog() {}

    public static InstanceSpec get(String name) {
        InstanceSpec s = TYPES.get(name);
        if (s == null) throw new IllegalArgumentException(
                "unknown instance type '" + name + "'; known types: " + String.join(", ", TYPES.keySet()));
        return s;
    }

    public static boolean has(String name) { return TYPES.containsKey(name); }
    /**
     * Every type, in the order they are declared above.
     *
     * <p>Order-preserving on purpose: the declaration groups them by family —
     * burstable, balanced, compute, memory, storage — and that grouping is the
     * only thing about this list that helps somebody choose. Alphabetical puts
     * {@code c5.4xlarge} first, which teaches nothing.
     */
    public static Map<String, InstanceSpec> all() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }
}
