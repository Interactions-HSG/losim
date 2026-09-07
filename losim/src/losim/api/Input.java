package losim.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An input, at the size losim chose for this run.
 *
 * <p>A {@link Scalable} job says what its input is made of — a store's items, a
 * join's two tables, the bytes in one value — and the scenario says how much of
 * each. Neither number is in the job's Java, which is the whole point: a workload
 * size written into a constant cannot be swept, cannot be varied by an overlay, and
 * makes the direct run a different amount of work from the scaled run that is meant
 * to model it.
 *
 * <h2>Counts and constants</h2>
 *
 * <p>A <b>count</b> is a quantity that grows with the run: items, rows, orders.
 * Every count shrinks by the same factor, so a smaller rung is the same design at a
 * smaller size and the ratios it lives or dies by survive.
 *
 * <p>A <b>constant</b> is shape rather than size: a value's length, a fan-out, a key
 * width. It is held across every rung, because a store of a tenth as many items is
 * still a store of 64 KB values.
 *
 * <p>A quantity the program only discovers while running — a vocabulary, a count of
 * distinct keys — is neither, and does not belong here. Those are revealed to the
 * engine as the job runs and fitted against the counts, which is how it learns that
 * a vocabulary saturates where a disk does not.
 */
public final class Input {

    private final Shape shape;
    private final Map<String, Long> sizes;

    private Input(Shape shape, Map<String, Long> sizes) {
        this.shape = shape;
        this.sizes = sizes;
    }

    /**
     * What an input is made of. A job's answer to "what do you consume", with no
     * number in it — the numbers are the scenario's.
     *
     * <pre>{@code
     * public Input.Shape shape() {
     *     return Input.Shape.counting("items", "item").with("bytes");
     * }
     * }</pre>
     *
     * <p>Nested inside {@code Input} rather than standing alone because
     * {@code losim.cli.Shape} is a different thing one package away, and two bare
     * {@code Shape}s is how a wrong import becomes half an hour.
     */
    public static final class Shape {

        /**
         * One named part.
         *
         * @param name  what the scenario writes on the left of the colon
         * @param noun  the singular of the thing being counted, for the messages a
         *              run prints — {@code null} for a constant, which is not a
         *              count of anything
         */
        public record Part(String name, String noun) {
            /** Whether this part shrinks with the run, rather than being held. */
            public boolean isCount() { return noun != null; }
        }

        private final List<Part> parts;

        private Shape(List<Part> parts) { this.parts = parts; }

        /** A shape whose first part is a count. Every shape has at least one. */
        public static Shape counting(String part, String noun) {
            return new Shape(List.of()).andCounting(part, noun);
        }

        /** Another count, shrinking with the first and in the same proportion. */
        public Shape andCounting(String part, String noun) {
            if (noun == null || noun.isBlank())
                throw new IllegalArgumentException("the count '" + part + "' needs a singular"
                        + " noun, so a run can say what it did 240 of");
            return add(new Part(check(part), noun));
        }

        /** A constant: held at what the scenario said, whatever rung the run is on. */
        public Shape with(String part) {
            return add(new Part(check(part), null));
        }

        /** Every part, in the order the job declared them. */
        public List<Part> parts() { return parts; }

        /** The parts a scenario has to give a size to, which is all of them. */
        public List<String> names() { return parts.stream().map(Part::name).toList(); }

        /** The named part, or {@code null} if this shape has no such part. */
        public Part part(String name) {
            for (Part p : parts) if (p.name().equals(name)) return p;
            return null;
        }

        private Shape add(Part p) {
            if (part(p.name()) != null)
                throw new IllegalArgumentException("'" + p.name() + "' is declared twice in"
                        + " the same shape");
            var out = new java.util.ArrayList<>(parts);
            out.add(p);
            return new Shape(List.copyOf(out));
        }

        private static String check(String part) {
            if (part == null || part.isBlank())
                throw new IllegalArgumentException("a part of an input needs a name — it is"
                        + " what the scenario writes to say how big it is");
            return part;
        }

        @Override public String toString() { return String.join(", ", names()); }
    }

    /**
     * Resolves a shape against what the scenario declared, at the fraction of full
     * size this run is doing.
     *
     * <p>{@code declared} is every part at full size. The fraction is 1 for a direct
     * run and whatever rung the scale engine picked for a scaled one; it applies to
     * the counts and to nothing else.
     *
     * @throws IllegalArgumentException if a part has no size, a size names no part,
     *         or a count would shrink away to nothing at this fraction — each of
     *         which is a run that would otherwise quietly measure the wrong thing
     */
    public static Input of(Shape shape, Map<String, Long> declared, double fraction) {
        if (fraction <= 0 || fraction > 1)
            throw new IllegalArgumentException("a run does " + share(fraction) + " of the full"
                    + " input, which is not a fraction of anything");
        for (String said : declared.keySet())
            if (shape.part(said) == null)
                throw new IllegalArgumentException("nothing in the input is called '" + said
                        + "'. The job's shape() declares " + shape + ".");

        var sizes = new LinkedHashMap<String, Long>();
        for (Shape.Part p : shape.parts()) {
            Long full = declared.get(p.name());
            if (full == null)
                throw new IllegalArgumentException("the input has a part called '" + p.name()
                        + "' and nothing says how big it is. A job says what its input is made"
                        + " of; the scenario says how much.");
            if (full < 1)
                throw new IllegalArgumentException("'" + p.name() + "' is " + full
                        + ", which is not a smaller run — it is no run.");
            if (!p.isCount()) { sizes.put(p.name(), full); continue; }
            long here = Math.round(full * fraction);
            if (here < 1)
                throw new IllegalArgumentException("this run is " + share(fraction) + " of full size,"
                        + " and " + full + " " + p.noun() + "s shrinks to none at all. Either"
                        + " '" + p.name() + "' is a constant rather than a count, or it is too"
                        + " small beside the parts it shrinks with.");
            sizes.put(p.name(), here);
        }
        return new Input(shape, Map.copyOf(sizes));
    }

    /** A fraction as a reader would say it, rather than as a double prints. */
    private static String share(double fraction) {
        return String.format("%.3g%%", fraction * 100);
    }

    /** What the input is made of. */
    public Shape shape() { return shape; }

    /**
     * How many of that part this run is to process.
     *
     * @throws IllegalArgumentException if the part is not a count, or not declared —
     *         both of which are a typo in the job rather than a smaller run
     */
    public long count(String part) {
        Shape.Part p = declaredPart(part);
        if (!p.isCount())
            throw new IllegalArgumentException("'" + part + "' is a constant, not a count."
                    + " Ask for value(\"" + part + "\").");
        return sizes.get(part);
    }

    /** A constant, at what the scenario said. Counts are readable this way too. */
    public long value(String part) {
        declaredPart(part);
        return sizes.get(part);
    }

    /**
     * Every count added up: the one number the scale engine works in.
     *
     * <p>The parts move together, so their total is enough to say how big a run was,
     * and one axis is what makes a projection from four rungs honest rather than a
     * surface fitted through too few points.
     */
    public long units() {
        long n = 0;
        for (Shape.Part p : shape.parts()) if (p.isCount()) n += sizes.get(p.name());
        return n;
    }

    private Shape.Part declaredPart(String part) {
        Shape.Part p = shape.part(part);
        if (p == null)
            throw new IllegalArgumentException("nothing in the input is called '" + part
                    + "'. shape() declares " + shape + ".");
        return p;
    }

    @Override public String toString() {
        var out = new StringBuilder();
        for (Shape.Part p : shape.parts()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(sizes.get(p.name())).append(' ').append(p.isCount() ? p.noun() + "s" : p.name());
        }
        return out.toString();
    }
}
