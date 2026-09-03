package losim.res;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a machine is, and how far it is from another one.
 *
 * <p>A scenario places machines in <b>zones</b> — {@code eu-central-1a} — and
 * two things follow from a zone that nothing else in losim knows how to work
 * out. The first is latency, which the network already models. The second is
 * <b>what it costs to talk</b>, and that is why this exists: a byte sent to the
 * rack next door, to Frankfurt, and to Sydney are three different prices, and a
 * design that talks across an ocean because nobody looked is one of the more
 * expensive mistakes this course can teach cheaply.
 *
 * <p><b>Geography is library data; prices are not.</b> Which continent Tokyo is
 * on is not a choice a course makes, so it is here. What a gigabyte to Tokyo
 * costs <i>is</i> a choice, and lives in {@code prices/} where it can be
 * distorted for an exercise without touching the simulator.
 *
 * <p><b>Nothing here is validated against.</b> A scenario may use any zone name
 * it likes — {@code rack-3}, {@code left}, {@code eu-central-1a} — because
 * refusing a name would break every scenario ever written against an earlier
 * losim for the sake of a table this file happens to carry. A name that is not
 * known is its own region on an unknown continent, and priced as the cheaper
 * kind of distance rather than the more expensive one: see {@link #between}.
 */
public final class Regions {

    /**
     * One region.
     *
     * @param name      as a scenario writes it — {@code eu-central-1}, {@code switzerlandnorth}
     * @param provider  {@code aws} or {@code azure}, for nothing but the reader's benefit
     * @param continent what decides whether traffic to it crosses an ocean
     * @param where     the city anybody would actually name it by
     */
    public record Region(String name, String provider, String continent, String where) {}

    private static final Map<String, Region> KNOWN = new LinkedHashMap<>();

    private static void add(String name, String provider, String continent, String where) {
        KNOWN.put(name, new Region(name, provider, continent, where));
    }

    static {
        // Ten, spread over six continents and two providers. Not every region
        // either cloud sells: a list of thirty-six is a list nobody reads, and
        // what a student needs is enough places that "somewhere else" and "the
        // other side of the world" are visibly different prices.
        add("us-east-1",        "aws",   "north-america", "N. Virginia");
        add("us-west-2",        "aws",   "north-america", "Oregon");
        add("eu-central-1",     "aws",   "europe",        "Frankfurt");
        add("eu-west-1",        "aws",   "europe",        "Ireland");
        add("ap-northeast-1",   "aws",   "asia",          "Tokyo");
        add("ap-south-1",       "aws",   "asia",          "Mumbai");
        add("sa-east-1",        "aws",   "south-america", "São Paulo");
        add("switzerlandnorth", "azure", "europe",        "Zurich");
        add("australiaeast",    "azure", "oceania",       "Sydney");
        add("southafricanorth", "azure", "africa",        "Johannesburg");
    }

    private Regions() {}

    /**
     * Every region, in the order they are declared above.
     *
     * <p>Order-preserving on purpose. `Map.copyOf` does not promise one, and what
     * came out was ten places in a scrambled order — which is fine for a lookup
     * and wrong for a list somebody has to choose from, where the two European
     * ones being next to each other is the whole help.
     */
    public static Map<String, Region> all() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(KNOWN));
    }

    public static List<String> names() { return List.copyOf(KNOWN.keySet()); }

    public static Region get(String region) { return KNOWN.get(region); }

    /** An availability zone in the AWS style: a region, then one letter. */
    private static final Pattern AWS = Pattern.compile("^([a-z]{2}(?:-[a-z]+)+-\\d+)[a-z]$");

    /** An availability zone in the Azure style: a region, then a number. */
    private static final Pattern AZURE = Pattern.compile("^([a-z]+)-(\\d+)$");

    /**
     * The region a zone is in.
     *
     * <p>Read off the name rather than declared, because the name already says
     * it and a second place to declare it is a second place to get it wrong.
     * A zone this cannot parse is its own region, which is the honest answer:
     * losim does not know where {@code rack-3} is.
     */
    public static String regionOf(String zone) {
        if (zone == null || zone.isBlank()) return "";
        String z = zone.trim().toLowerCase(Locale.ROOT);
        if (KNOWN.containsKey(z)) return z;
        var aws = AWS.matcher(z);
        if (aws.matches()) return aws.group(1);
        var azure = AZURE.matcher(z);
        if (azure.matches() && KNOWN.containsKey(azure.group(1))) return azure.group(1);
        return z;
    }

    /** What continent a region is on, or "" when losim has never heard of it. */
    public static String continentOf(String region) {
        Region r = KNOWN.get(region);
        return r == null ? "" : r.continent();
    }

    /**
     * How far apart two zones are, in the only four steps a bill distinguishes.
     *
     * <p>Four rather than a distance in kilometres, because that is how the
     * traffic is actually charged — and because a price that varied continuously
     * with distance would invite a student to optimise a number losim made up.
     */
    public enum Link {
        /** The same rack, as far as anybody is billed. Free. */
        SAME_ZONE,
        /** Another availability zone in the same region. */
        SAME_REGION,
        /** Another region on the same continent. */
        CROSS_REGION,
        /** Across an ocean. */
        INTERCONTINENTAL
    }

    /**
     * The link between two zones.
     *
     * <p><b>An unknown region is never intercontinental.</b> When either end is a
     * name losim cannot place, the answer is {@link Link#CROSS_REGION} — the
     * cheaper of the two cross-region rates. Guessing "across an ocean" for a
     * name nobody recognised would put the largest egress line on the bill on the
     * strength of a guess, and a bill has to be defensible line by line.
     */
    public static Link between(String fromZone, String toZone) {
        if (fromZone == null || toZone == null) return Link.SAME_ZONE;
        if (fromZone.equals(toZone)) return Link.SAME_ZONE;
        return toRegion(fromZone, regionOf(toZone));
    }

    /**
     * The link from a zone to a region, for bytes already known to have left the zone.
     *
     * <p>This is the question a bill asks. A trace records egress by destination
     * <i>region</i> — the destination zone is not kept, because the price does not
     * depend on it — so {@link Link#SAME_ZONE} cannot come back from here: bytes
     * that never left the zone were never counted in the first place.
     */
    public static Link toRegion(String fromZone, String toRegion) {
        String a = regionOf(fromZone);
        if (a.equals(toRegion)) return Link.SAME_REGION;
        String ca = continentOf(a), cb = continentOf(toRegion);
        if (ca.isEmpty() || cb.isEmpty()) return Link.CROSS_REGION;
        return ca.equals(cb) ? Link.CROSS_REGION : Link.INTERCONTINENTAL;
    }
}
