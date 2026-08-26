import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Losim;

/**
 * The same combiner, watched a thousand times harder.
 *
 * <p>This is the extreme case, and it is mandatory rather than thorough. At one
 * {@code reveal} per handler an observer effect that halves a fitted exponent is
 * undetectable: every number stays plausible, nothing looks broken, and only the
 * projection is wrong. At a thousand it is unmissable.
 *
 * <p>What must hold is not that these calls are cheap — they are not, and the
 * ledger this machine carries in the trace says so — but that they are
 * <b>metered and taken back off</b>, so the law fitted here is the same law as the
 * one fitted next door. A program that leans on losim heavily is simply excluded
 * more.
 */
public final class Chatty extends Combiner {

    static final int REVEALS = 1000;

    @Override protected Counts map(Chunk c) {
        Counts out = super.map(c);
        var ctx = Losim.current();
        for (int i = 0; i < REVEALS; i++) ctx.reveal("chatter", i);
        return out;
    }
}
