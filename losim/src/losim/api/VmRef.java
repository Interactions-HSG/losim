package losim.api;

/** An opaque reference to a VM, used by schema-less (Style A) messaging. */
public record VmRef(String name) implements Peer {
    @Override public String toString() { return name; }
}
