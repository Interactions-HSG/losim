package losim.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A dataset that is described, not materialised.
 *
 * This is how a lab runs a terabyte on a laptop. A Data handle carries a record
 * count and a record size; the framework charges time for processing it, bytes
 * for moving it, and memory for holding it — so a 4 GB shard handed to a 1 GB
 * machine raises OutOfMemory exactly as it would in reality, without anyone
 * allocating 4 GB.
 *
 * Real values still flow alongside it when a lab wants them on screen; Data is
 * for the volume you could not afford to actually create.
 */
public record Data(String name, long records, long bytesPerRecord) {

    public static Data of(String name, long records, long bytesPerRecord) {
        if (records < 0 || bytesPerRecord < 0) throw new IllegalArgumentException("negative dataset");
        return new Data(name, records, bytesPerRecord);
    }

    public static Data gigabytes(String name, double gb, long bytesPerRecord) {
        long total = (long) (gb * 1_000_000_000L);
        return new Data(name, Math.max(1, total / Math.max(1, bytesPerRecord)), bytesPerRecord);
    }

    public long bytes() { return records * bytesPerRecord; }

    /** A pointer to this dataset. Sending the pointer does not move the data. */
    public DataRef ref() { return new DataRef(name, records, bytesPerRecord); }

    public double gigabytes() { return bytes() / 1e9; }

    /** Split into n shards, deterministically, remainder spread over the first shards. */
    public List<Data> split(int n) {
        if (n < 1) throw new IllegalArgumentException("split needs at least one shard");
        List<Data> out = new ArrayList<>(n);
        long per = records / n, extra = records % n;
        for (int i = 0; i < n; i++) {
            long r = per + (i < extra ? 1 : 0);
            out.add(new Data(name + "#" + i, r, bytesPerRecord));
        }
        return out;
    }

    /** What comes out of a stage that keeps a fraction of what went in. */
    public Data derive(String newName, double selectivity, long newBytesPerRecord) {
        long r = Math.max(0, Math.round(records * selectivity));
        return new Data(newName, r, newBytesPerRecord);
    }

    public Data merge(Data other, String newName) {
        long total = records + other.records();
        long avg = total == 0 ? bytesPerRecord
                : (bytes() + other.bytes()) / total;
        return new Data(newName, total, avg);
    }

    @Override public String toString() {
        return name + "(" + String.format("%,d", records) + " recs, "
                + String.format("%.2f", gigabytes()) + " GB)";
    }
}
