package losim.runtime;

import io.grpc.MethodDescriptor;
import java.util.List;

/**
 * Retry policy for one RPC method.
 *
 * <p>The simulation supplies the retry policy. The service definition supplies
 * idempotency, and loading rejects policies that conflict with it.
 *
 * <p>{@code unsafe: true} explicitly permits retrying a method without an
 * idempotency declaration.
 */
public record Retry(String method, int attempts, double backoffRefMs,
                    double multiplier, boolean unsafe, String where) {

    /** Matches a bare method name, a dotted {@code Service.Method}, or {@code *}. */
    public boolean matches(MethodDescriptor<?, ?> md) {
        if (method.equals("*")) return true;
        String dotted = Wire.dotted(md.getFullMethodName());
        return dotted.equals(method)
            || dotted.endsWith("." + method)
            || dotted.substring(dotted.lastIndexOf('.') + 1).equals(method);
    }

    /**
     * Validates this policy against the methods served by the cluster.
     *
     * @param known every method the cluster actually serves
     * @throws IllegalArgumentException if no served method matches or the policy
     *         retries a non-idempotent method without explicit permission
     */
    public void checkAgainst(List<MethodDescriptor<?, ?>> known) {
        var matched = known.stream().filter(this::matches).toList();
        if (matched.isEmpty())
            throw new IllegalArgumentException(where + ": retry policy names '" + method
                    + "', which no node in this simulation serves");
        if (unsafe) return;
        var unsound = matched.stream().filter(md -> !md.isIdempotent()).toList();
        if (unsound.isEmpty()) return;
        throw new IllegalArgumentException(where + ": retrying "
                + Wire.dotted(unsound.get(0).getFullMethodName())
                + " is refused — its .proto declares no idempotency_level, so running it twice"
                + " is not known to be safe. Declare"
                + " 'option idempotency_level = IDEMPOTENT;' on the rpc if it is,"
                + " or write 'unsafe: true' here if you mean to retry it anyway.");
    }

    /** Backoff before attempt {@code n}, counting the first attempt as 1. */
    public double backoffBefore(int n) {
        double b = backoffRefMs;
        for (int i = 2; i < n; i++) b *= multiplier;
        return b;
    }
}
