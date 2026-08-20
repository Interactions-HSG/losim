import losim.api.*;

import java.util.TreeMap;

/**
 * Sums pairs by key.
 *
 * Stateless per call, which is exactly what lets the same function serve as a
 * combiner. That only works because addition is associative and commutative —
 * swap in an average and the early application silently corrupts the answer.
 */
public final class Reducer implements Program, ReducerService {

    @Override @Cost(ms = 3)
    public Counts reduce(Ctx ctx, Pairs request) {
        ctx.retain(request);                       // the partition is held while merging
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Pair p : request.pairs()) out.merge(p.key(), p.value(), Integer::sum);
        ctx.reveal("keys", out.size());
        return new Counts(out);
    }
}
