import losim.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emits raw (word, 1) pairs — the naive form, so the combiner has something to do.
 *
 * Folding the aggregation in here would be an implicit combiner and would hide
 * the lesson entirely.
 */
public final class Mapper implements Program, MapperService {

    @Override @Cost(ms = 2)
    public Pairs map(Ctx ctx, Chunk request) {
        List<Pair> out = new ArrayList<>();
        for (String w : request.text().split("\\s+"))
            if (!w.isBlank()) out.add(new Pair(w, 1));
        Pairs pairs = new Pairs(out);

        // A combiner is not special code: it is a Reducer, run early, on THIS machine.
        // Loopback, so it is free — deploy it elsewhere and it costs the very
        // traffic it was meant to save.
        var local = ctx.local(ctx.self(), ReducerPeer.class);
        if (local.isPresent()) {
            Counts combined = local.get().reduce(pairs);
            pairs = toPairs(combined);
            ctx.reveal("combined", pairs.pairs().size() + "/" + out.size());
        }
        return pairs;
    }

    static Pairs toPairs(Counts c) {
        List<Pair> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : c.counts().entrySet()) out.add(new Pair(e.getKey(), e.getValue()));
        return new Pairs(out);
    }
}
