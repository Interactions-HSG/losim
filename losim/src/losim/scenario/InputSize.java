package losim.scenario;

/**
 * One part of the input, at full size, as the scenario wrote it.
 *
 * <p>Plain numbers, with the unit in the name the job chose — {@code items: 240},
 * {@code valueBytes: 65536} — which is how {@code memoryMb} and {@code refNsPerUnit}
 * are already written. Time is the one thing that has to say its unit, because
 * simulated seconds and yours differ by a factor nobody sees.
 *
 * @param name  the part, matching one the job's {@code shape()} declares
 * @param n     how many, or how much, at full scale
 * @param where the line it was written on, for the refusal that comes once the job
 *              class is loaded and can be asked what it consumes
 */
public record InputSize(String name, long n, String where) {}
