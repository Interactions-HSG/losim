import losim.api.*;

import java.time.Duration;
import java.util.*;

/**
 * The master. Assignment policy is the exercise, so it is written out rather
 * than hidden behind a scatter helper.
 */
public final class Master implements Program {

    /** How long to wait before deciding a worker is gone. Too low duplicates work; too high stalls. */
    private static final Duration MAP_DEADLINE = Duration.ofMillis(800);

    private final List<Pair> emitted = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void main(Ctx ctx) {
        List<MapperPeer> mappers = ctx.peers(MapperPeer.class);
        List<ReducerPeer> reducers = ctx.peers(ReducerPeer.class);
        if (mappers.isEmpty()) { ctx.done(Map.of()); return; }

        List<String> splits = split(String.valueOf(ctx.input()), mappers.size() * 2);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < splits.size(); i++) indices.add(i);
        WorkQueue<Integer> work = ctx.workQueue(indices);

        for (MapperPeer w : mappers) {
            ctx.spawn(() -> {
                for (Integer i : work) {                       // blocking take
                    try {
                        Pairs p = ctx.within(MAP_DEADLINE, () -> w.map(new Chunk(splits.get(i))));
                        if (work.done(i)) emitted.addAll(p.pairs());   // false => already redone
                        else ctx.log("late reply for split " + i + " from " + w.name() + " — discarded");
                    } catch (Faults.Timeout | Faults.Unreachable e) {
                        ctx.log(w.name() + " quiet on split " + i + " — requeueing");
                        work.requeue(i);
                    }
                }
            });
        }
        ctx.awaitAll();

        // shuffle: partition by key, then reduce
        Map<String, Integer> output = new TreeMap<>();
        if (reducers.isEmpty()) {
            for (Pair p : emitted) output.merge(p.key(), p.value(), Integer::sum);
        } else {
            List<List<Pair>> buckets = new ArrayList<>();
            for (int i = 0; i < reducers.size(); i++) buckets.add(new ArrayList<>());
            for (Pair p : emitted) buckets.get(Math.floorMod(p.key().hashCode(), reducers.size())).add(p);

            for (int i = 0; i < reducers.size(); i++) {
                ReducerPeer r = reducers.get(i);
                List<Pair> bucket = buckets.get(i);
                if (bucket.isEmpty()) continue;
                try {
                    Counts c = ctx.within(Duration.ofSeconds(5), () -> r.reduce(new Pairs(bucket)));
                    output.putAll(c.counts());
                } catch (Faults.Timeout | Faults.Unreachable e) {
                    ctx.log("reducer " + r.name() + " unreachable — merging locally");
                    for (Pair p : bucket) output.merge(p.key(), p.value(), Integer::sum);
                }
            }
        }
        ctx.reveal("output", output);
        ctx.done(output);
    }

    static List<String> split(String text, int n) {
        String[] words = text.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        int per = Math.max(1, (int) Math.ceil(words.length / (double) n));
        for (int i = 0; i < words.length; i += per) {
            out.add(String.join(" ", Arrays.copyOfRange(words, i, Math.min(words.length, i + per))));
        }
        return out;
    }
}
