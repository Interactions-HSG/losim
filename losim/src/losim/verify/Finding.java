package losim.verify;

/**
 * One place the code stepped outside the simulated world.
 *
 * <p>Carries its line, because a finding nobody can locate is a finding nobody
 * fixes — and because everything else losim refuses or reports says exactly where.
 *
 * @param where  {@code Counter.java:42}, or the class alone when the compiler kept
 *               no line table
 * @param inside the member it happened in, so a flag on a rarely-taken branch does
 *               not read like a flag on the handler
 * @param what   what was called, or what was declared
 */
public record Finding(Rule rule, String owner, String where, String inside, String what) {

    public Flag flag() { return rule.flag; }

    /** One line, to sit under the machine it belongs to. */
    public String describe() {
        return String.format("%-28s %s", where, inside.isEmpty() ? what : what + " in " + inside);
    }
}
