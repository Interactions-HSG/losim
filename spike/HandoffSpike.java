import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Week-1 spike: prove a discrete-event kernel can drive N virtual threads with
 * strict two-way semaphore handoff and produce a byte-identical trace.
 */
public class HandoffSpike {

    // ---------- kernel ----------
    static final class Event implements Comparable<Event> {
        final long time; final long seq; final Runnable action;
        Event(long time, long seq, Runnable action) { this.time=time; this.seq=seq; this.action=action; }
        public int compareTo(Event o) {
            int c = Long.compare(time, o.time);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    static final class Kernel {
        long now = 0;
        long seq = 0;
        final PriorityQueue<Event> queue = new PriorityQueue<>();
        final Random rng;
        final List<String> trace = new ArrayList<>();
        final Semaphore kernelPermit = new Semaphore(0);
        int liveVms = 0;

        Kernel(long seed) { this.rng = new Random(seed); }

        void schedule(long at, Runnable action) {
            if (at < now) throw new IllegalStateException("scheduling in the past");
            queue.add(new Event(at, seq++, action));
        }

        void log(String s) { trace.add(now + " " + s); }

        /** Hand control to a VM and block until it yields back. Only one thread runnable. */
        void resume(Vm vm) {
            vm.permit.release();
            kernelPermit.acquireUninterruptibly();
        }

        void run() {
            while (!queue.isEmpty()) {
                Event e = queue.poll();
                now = e.time;
                e.action.run();
            }
        }
    }

    // ---------- vm ----------
    static abstract class Vm {
        final String name; final Kernel k;
        final Semaphore permit = new Semaphore(0);
        final ArrayDeque<String> inbox = new ArrayDeque<>();
        boolean waitingForMessage = false;
        boolean done = false;

        Vm(String name, Kernel k) { this.name=name; this.k=k; }

        abstract void main() throws Exception;

        void start() {
            k.liveVms++;
            Thread.ofVirtual().name("vm-" + name).start(() -> {
                permit.acquireUninterruptibly();      // wait for first activation
                try { main(); }
                catch (Throwable t) { k.log("ERROR " + name + " " + t); }
                finally {
                    done = true;
                    k.liveVms--;
                    k.kernelPermit.release();          // final yield
                }
            });
            k.schedule(0, () -> k.resume(this));
        }

        /** Yield to the kernel and park until resumed. */
        void yieldToKernel() {
            k.kernelPermit.release();
            permit.acquireUninterruptibly();
        }

        void sleep(long ms) {
            k.schedule(k.now + ms, () -> k.resume(this));
            yieldToKernel();
        }

        /** Declared cost: advances virtual time without yielding scheduling decisions. */
        void compute(long ms) { sleep(ms); }

        void send(Vm to, String msg) {
            long latency = 10 + k.rng.nextInt(10);
            k.log("SEND " + name + "->" + to.name + " " + msg);
            k.schedule(k.now + latency, () -> to.deliver(msg));
        }

        void deliver(String msg) {
            inbox.add(msg);
            k.log("RECV " + name + " " + msg);
            if (waitingForMessage) k.resume(this);
        }

        String receive() {
            while (inbox.isEmpty()) {
                waitingForMessage = true;
                yieldToKernel();
            }
            waitingForMessage = false;
            return inbox.poll();
        }
    }

    // ---------- workload: a ring passing a token, with allocation churn ----------
    static final class RingVm extends Vm {
        final int index; final int fleet; final int laps;
        Vm next;
        RingVm(int index, int fleet, int laps, Kernel k) {
            super("vm" + index, k); this.index=index; this.fleet=fleet; this.laps=laps;
        }
        void main() throws Exception {
            if (index == 0) { compute(1); send(next, "token:0"); }
            while (true) {
                String msg = receive();
                int hops = Integer.parseInt(msg.substring(msg.indexOf(':') + 1));
                churn();                                  // provoke GC
                if (hops >= fleet * laps) { k.log("DONE " + name + " hops=" + hops); return; }
                compute(1 + (index % 3));
                send(next, "token:" + (hops + 1));
            }
        }
        void churn() {
            List<byte[]> junk = new ArrayList<>();
            for (int i = 0; i < 200; i++) junk.add(new byte[1024]);
            if (junk.size() < 0) System.out.println("unreachable");
        }
    }

    static String runOnce(long seed, int fleet, int laps) {
        Kernel k = new Kernel(seed);
        List<RingVm> vms = new ArrayList<>();
        for (int i = 0; i < fleet; i++) vms.add(new RingVm(i, fleet, laps, k));
        for (int i = 0; i < fleet; i++) vms.get(i).next = vms.get((i + 1) % fleet);
        for (RingVm vm : vms) vm.start();
        k.run();
        return String.join("\n", k.trace);
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        int fleet = 6, laps = 3;
        String golden = runOnce(42, fleet, laps);
        Set<String> distinct = new LinkedHashSet<>();
        distinct.add(golden);
        for (int i = 1; i < runs; i++) distinct.add(runOnce(42, fleet, laps));

        System.out.println("parallelism=" + System.getProperty("jdk.virtualThreadScheduler.parallelism", "default"));
        System.out.println("runs=" + runs + " distinct traces=" + distinct.size());
        System.out.println("trace lines=" + golden.split("\n").length);
        System.out.println("golden head:");
        String[] lines = golden.split("\n");
        for (int i = 0; i < Math.min(6, lines.length); i++) System.out.println("  " + lines[i]);
        System.out.println("golden tail: " + lines[lines.length - 1]);

        // different seed must differ (proves the trace is actually seed-sensitive)
        String other = runOnce(43, fleet, laps);
        System.out.println("seed 43 differs from seed 42: " + !other.equals(golden));

        if (distinct.size() != 1) { System.out.println("FAIL: nondeterministic"); System.exit(1); }
        if (other.equals(golden)) { System.out.println("FAIL: seed has no effect"); System.exit(1); }
        System.out.println("PASS");
    }
}
