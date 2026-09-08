/**
 * A file whose class is not named after it, which Java permits for a
 * package-private type and a scenario cannot survive.
 *
 * <p>Here so that the loader's check on it has something real to refuse. Nothing
 * runs this; it exists to be pointed at from Phase2, because a refusal nobody has
 * watched fire is a refusal nobody knows is wired up.
 */
final class Renamed {
    private Renamed() {}
}
