package losim.api;

/** The failures student code can observe. */
public final class Faults {
    private Faults() {}

    /** An RPC did not answer before its deadline. Dead or slow — you cannot tell. */
    public static class Timeout extends RuntimeException {
        public Timeout(String m) { super(m); }
    }

    /** The transport refused the call outright. */
    public static class Unreachable extends RuntimeException {
        public Unreachable(String m) { super(m); }
    }

    /** The VM crossed its configured memory cap. */
    public static class OutOfMemory extends RuntimeException {
        public OutOfMemory(String m) { super(m); }
    }

    /** The VM's disk quota is exhausted. */
    public static class NoSpace extends RuntimeException {
        public NoSpace(String m) { super(m); }
    }
}
