import losim.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Splits a described terabyte across the fleet and collects what comes back. */
public final class Planner implements Program {

    @Override
    public void main(Ctx ctx) {
        List<CrunchPeer> workers = ctx.peers(CrunchPeer.class);
        if (workers.isEmpty()) { ctx.done("no workers"); return; }

        Data corpus = Data.gigabytes("corpus", 1000, 200);      // 1 TB, 200 B records
        ctx.reveal("input", corpus.toString());

        List<Data> shards = corpus.split(workers.size());
        List<Summary> results = new ArrayList<>();
        List<String> lost = new ArrayList<>();

        for (int i = 0; i < workers.size(); i++) {
            CrunchPeer w = workers.get(i);
            Data shard = shards.get(i);
            ctx.spawn(() -> {
                try {
                    Summary s = ctx.within(Duration.ofSeconds(600), () -> w.process(new Shard(shard.ref())));
                    synchronized (results) { results.add(s); }
                } catch (Faults.Timeout | Faults.Unreachable e) {
                    ctx.log(w.name() + " did not survive " + shard + " — " + e.getClass().getSimpleName());
                    synchronized (lost) { lost.add(w.name()); }
                }
            });
        }
        ctx.awaitAll();

        long records = 0;
        double gb = 0;
        for (Summary s : results) { records += s.records(); gb += s.gigabytes(); }
        ctx.reveal("shardsCompleted", results.size() + "/" + workers.size());
        ctx.done("emitted " + String.format("%,d", records) + " records ("
                + String.format("%.1f", gb) + " GB) from " + results.size() + "/" + workers.size()
                + " shards" + (lost.isEmpty() ? "" : "; lost " + lost));
    }
}
