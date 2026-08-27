import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import losim.api.Losim;
import losim.api.Takes;
import mr.pb.MapDone;
import mr.pb.MapWorkerGrpc;
import mr.pb.Pair;
import mr.pb.Pairs;
import mr.pb.Fetch;
import mr.pb.Region;
import mr.pb.Split;

/**
 * Phases (3) read, (4) map, (5) combine and (6) local write — one machine.
 *
 * <p>The four are written as one handler because that is what they are: a map
 * task is not four calls, it is one call that does four things, and separating
 * them into RPCs would put a network between a mapper and its own scratch space.
 * What <i>is</i> a network hop is {@link #pull}, and the difference between the
 * two is the point of the whole design.
 *
 * <h2>Why the output stays here</h2>
 *
 * A map task does not send its output anywhere. It writes R regions to its own
 * disk and tells the master they exist; whoever needs region {@code p} comes and
 * reads it. That buys the two properties the whole architecture rests on — the
 * master never relays the data, and a reducer that dies re-reads rather than
 * re-computes — and it costs one thing, which is the most instructive failure in
 * MapReduce:
 *
 * <p><b>a dead map worker takes its finished work with it.</b> The task completed,
 * the master recorded it, and the regions are gone anyway, so it has to be run
 * again — on a machine that has room. Nothing about "the task succeeded" survives
 * the machine that succeeded at it. {@link #pull} is where that is discovered, by
 * a shuffler, long after the map phase is over.
 */
public final class MapMachine extends MapWorkerGrpc.MapWorkerImplBase {

    /**
     * What a pair costs on disk once it is written out: the key's characters, its
     * count, and the framing around them. Declared rather than measured because a
     * real intermediate file has a format and this one does not — and a disk that
     * fills has to fill for a reason a reader can check.
     */
    static final int BYTES_PER_PAIR = 48;

    /** This machine's local disk: task and partition, to the pairs written there. */
    private final Map<Long, List<Pair>> disk = new ConcurrentHashMap<>();

    private static long slot(int task, int part) { return ((long) task << 20) | part; }

    // ------------------------------------------------------------ (3)(4)(5)(6)

    @Takes(refMs = 4, refNsPerRecord = 90_000)
    @Override public void map(Split split, StreamObserver<MapDone> out) {
        try {
            out.onNext(run(split));
            out.onCompleted();
        } catch (RuntimeException e) {
            // A map task that could not finish has to say so with a status, not
            // with a half-written answer. The master cannot tell a slow machine
            // from a broken one by waiting, but it can read a status.
            trouble(mr.pb.Phase.MAP, e);
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(Losim.current().machine() + ": " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private MapDone run(Split split) {
        // (3) read. Declared, because the per-record term of this handler's cost is
        // charged against it and because the engine has to know which variable this
        // site's demand is a function of.
        String[] words = split.getText().split("\\s+");
        Losim.current().records(split.getLines());

        // (4) map — one pair per word, duplicates and all. Summing here would be a
        // combiner, and a combiner folded in silently is just a mapper you can no
        // longer reason about.
        int emitted = 0;
        var combined = new HashMap<String, Integer>();
        for (String w : words) {
            if (w.isEmpty()) continue;
            emitted++;
            // (5) combine — legitimate only because addition is associative. Try the
            // same trick with an average and watch it come apart.
            combined.merge(w, 1, Integer::sum);
        }

        // (6a) the spill. Before anything is merged, the raw buffer goes to disk —
        // one record per word, duplicates and all — because a map task's output
        // buffer is finite and the text is not. This is the write that makes the
        // combiner worth having: what lands here is proportional to how many words
        // were read, and what survives the merge below is proportional to how many
        // *distinct* words there were, which on real text is far fewer.
        Losim.current().wroteDisk((long) emitted * BYTES_PER_PAIR);

        // (6b) the merge: cut into R regions and put each on this machine's disk.
        int parts = Math.max(1, split.getParts());
        var regions = new ArrayList<Region>(parts);
        var byPart = new ArrayList<List<Pair>>(parts);
        for (int p = 0; p < parts; p++) byPart.add(new ArrayList<>());
        combined.forEach((key, n) -> byPart.get(Corpus.partition(key, parts))
                .add(Pair.newBuilder().setKey(key).setValue(n).build()));

        for (int p = 0; p < parts; p++) {
            List<Pair> region = byPart.get(p);
            long bytes = (long) region.size() * BYTES_PER_PAIR;

            // Throws when the volume is full, and it must: a write that could not
            // happen must not appear to have happened. Everything written so far
            // stays written — a disk that fills halfway through a task leaves half a
            // task behind, which is exactly what makes the task have to be redone
            // somewhere else rather than resumed here.
            Losim.current().wroteDisk(bytes);

            disk.put(slot(split.getTask(), p), region);
            regions.add(Region.newBuilder().setTask(split.getTask()).setPart(p)
                    .setPairs(region.size()).setBytes(bytes).build());
        }

        Losim.current().reveal("emitted", emitted);
        Losim.current().reveal("kept", combined.size());
        Losim.current().reveal("regions", disk.size());
        return MapDone.newBuilder()
                .setTask(split.getTask())
                .setWorker(Losim.current().machine())
                .addAllRegions(regions)
                .setEmitted(emitted)
                .setKept(combined.size())
                .build();
    }

    // -------------------------------------------------------------------- (7)

    /**
     * The remote read: somebody else's reducer, reaching for a region of this
     * machine's disk.
     *
     * <p>Cheap in time and expensive in bytes, which is the opposite of the map
     * that produced it, and it is the only call in the system that routinely
     * crosses a zone.
     */
    @Takes(refMs = 1, refNsPerRecord = 4_000)
    @Override public void pull(Fetch request, StreamObserver<Pairs> out) {
        List<Pair> region = disk.get(slot(request.getTask(), request.getPart()));
        if (region == null) {
            // Not an error in this machine: the machine is fine. The *disk* is not
            // what it was, because this process is not the process that wrote it.
            // Saying NOT_FOUND rather than INTERNAL is what lets the caller tell
            // "run that task again" apart from "try again in a moment".
            trouble(mr.pb.Phase.SHUFFLE, new IllegalStateException(
                    "task " + request.getTask() + " part " + request.getPart()
                    + " is not on this disk"));
            out.onError(Status.NOT_FOUND
                    .withDescription(Losim.current().machine() + " has no region "
                            + request.getTask() + "/" + request.getPart()
                            + "; that map task has to be run again")
                    .asRuntimeException());
            return;
        }
        Losim.current().records(region.size());
        Losim.current().reveal("served", region.size());
        out.onNext(Pairs.newBuilder().addAllPairs(region).build());
        out.onCompleted();
    }

    // ------------------------------------------------------------------- aside

    /** Tells the master, and does not wait to be told it was heard. */
    static void trouble(mr.pb.Phase phase, RuntimeException e) {
        Reporting.tell(phase, e.getMessage());
    }
}
