package losim.api;

/**
 * A pointer to a dataset that already lives where it is needed.
 *
 * Sending a {@link Data} means moving it, and the network charges every byte.
 * Sending a DataRef means telling a worker which shard it already holds — the
 * message is tiny. That difference is what "move the computation to the data"
 * actually costs, and it is why a task assignment is not a data transfer.
 */
public record DataRef(String name, long records, long bytesPerRecord) {
    public Data resolve() { return new Data(name, records, bytesPerRecord); }
    public long bytes() { return records * bytesPerRecord; }
    @Override public String toString() { return "ref:" + name; }
}
