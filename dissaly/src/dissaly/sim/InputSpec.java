package dissaly.sim;

/**
 * The workload a simulation declares, at full size.
 *
 * <p>Three fields, and this is the whole of what a simulation says about its
 * data. It replaces a block of named parts held against a Java class's own
 * declaration of what it consumed — which needed the class loaded to check, put
 * the shape of the input in two places, and gave the engine several numbers when
 * it only ever varies one.
 *
 * <p>What reaches the Job is a {@link dissaly.pb.Input} carrying these three,
 * with {@code count} already multiplied down to the rung this run is measuring.
 * Everything past that is the Job's: its {@code Load} reads the source or draws
 * from the seed, and returns a {@link dissaly.pb.Workload} of its own shape.
 *
 * @param source a file or a directory, relative to the project root, or null
 *               when the simulation named none — and then {@code Load} generates
 *               from {@link dissaly.api.DissalyCtx#seed()} instead. Generating is
 *               the shape a scaled workload wants anyway: reproducible from the
 *               seed, varying with it across a sweep, and costing nothing on the
 *               clock because {@code Load} is off it
 * @param unit   what one item is, singular. The same word
 *               {@code Dissaly.current().units(n)} counts and {@code perUnit:}
 *               prices, so the three cannot disagree about what is being counted
 * @param count  how many, at full scale
 * @param where  the line it was written on
 */
public record InputSpec(String source, String unit, long count, String where) {

    /** What a simulation that says nothing about its input gets: one of something. */
    public static InputSpec none(String where) { return new InputSpec(null, "unit", 1, where); }
}
