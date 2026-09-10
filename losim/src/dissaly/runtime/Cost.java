package dissaly.runtime;

/**
 * How long one call takes on the reference machine.
 *
 * <p>"2 ms" names no machine and does not compose with scaling, so every declared
 * duration in losim is reference-machine time (D3). The interceptor sleeps
 * {@code refMs * machineFactor / k_time}: a machine with half the reference's
 * vCPUs takes twice as long, and the whole run is compressed by k_time so a
 * forty-minute job lands in tens of seconds.
 *
 * <p><b>Why this is declared at all, when everything else is measured.</b> losim
 * shrinks the workload and the machines by the same factor, so memory and bytes
 * survive it honestly: a reducer that would exhaust a 16 GiB machine exhausts a
 * 16 MiB one, in the program's own code, for the same reason. Time does not
 * survive it, because the host's CPU is not shrunk — it runs at full speed on a
 * five-thousandth of the data, so every handler is genuinely instant. Without a
 * declared duration there is no queueing, no contention, no deadline pressure and
 * no critical path, which is most of what a cluster is interesting for.
 *
 * <p>Measuring it instead does not work at this scale: handler durations at probe
 * size sit inside the host's own jitter, where the fitted time exponent moves by
 * 0.25 between seed sets of an identical workload. So it is declared — and it is
 * <b>optional</b>: a method nobody has timed takes no time, deliberately, rather
 * than a made-up amount.
 *
 * <p><b>It lives in the simulation, not in the Java.</b> A duration is a claim about
 * the machine the design would run on, which is the simulation's subject; and
 * putting it there is what leaves a student's handlers with no losim symbol in
 * them at all, so a system can be written, compiled and unit-tested without this
 * project on the classpath. The cost of that is that a renamed rpc no longer
 * takes its number with it — which is why a {@code simulatedDuration:} key naming a method
 * the cluster does not serve is refused with a line number rather than ignored.
 *
 * <p>The two terms answer different questions. {@link #refMs()} is what the call
 * takes regardless of what is in it — the fixed part, known before the handler
 * runs, and slept before it. {@link #refNsPerUnit()} is what each unit of input
 * adds, which nothing outside the handler can know: the handler declares the count
 * with {@code Dissaly.current().units(n)} and the variable part is slept after the
 * body returns, before the span closes. A handler that never declares a count is
 * charged the fixed part only, and its span says so.
 *
 * @param where the file and line it was written on, for a refusal to name
 */
public record Cost(double fixedRefMs, double perUnitRefMs, String where) {

    public Cost(double fixedRefMs, double perUnitRefMs) { this(fixedRefMs, perUnitRefMs, ""); }
}
