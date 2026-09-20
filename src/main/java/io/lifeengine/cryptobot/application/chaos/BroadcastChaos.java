package io.lifeengine.cryptobot.application.chaos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The armed fault (KAN-571). One mode at a time, with a shot counter: each faulted RPC call
 * consumes one shot; {@code -1} shots = until disarmed. Every injected fault is logged here so
 * the evidence can quote it.
 *
 * <ul>
 *   <li>{@link Mode#UNCERTAIN} — {@code sendTransaction} is performed for real, then its answer is
 *       "lost" (transport error). The bytes are on the network, the service does not know:
 *       {@code EXECUTION_BROADCAST_UNCERTAIN}, row {@code EXECUTING}+{@code SIGNED}. The reconciler
 *       finds the signature confirmed and closes the trade — no retry, no second transaction.
 *   <li>{@link Mode#RPC_DOWN} — nothing is sent: {@code sendTransaction}, {@code getSignatureStatuses}
 *       and {@code getBlockHeight} fail with a transport error. Same uncertain row; the reconciler
 *       gets no verdict until the fault is disarmed. With enough attempts it dead-letters the trade
 *       ({@code ambiguous}); once disarmed (requeue), the blockhash has expired unseen and the
 *       operation is retried idempotently — new signature, same {@code operationId}.
 *   <li>{@link Mode#CONFIRM_TIMEOUT} — the broadcast succeeds; the confirmation poll fails. Row
 *       {@code SUBMITTED}; the reconciler finds it confirmed.
 * </ul>
 */
public final class BroadcastChaos {

    public enum Mode {
        UNCERTAIN,
        RPC_DOWN,
        CONFIRM_TIMEOUT;

        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public static Mode parse(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            String norm = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return Mode.valueOf(norm);
        }
    }

    /** One fault as it was injected: which call, which mode, when, and what the caller saw. */
    public record Fault(Instant at, Mode mode, String method, String detail) {}

    public record State(Mode broadcast, int shotsLeft, List<Fault> faults) {
        public boolean armed() {
            return broadcast != null && shotsLeft != 0;
        }
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>();
    private final AtomicInteger shots = new AtomicInteger();
    private final List<Fault> faults = new ArrayList<>();

    /** Arms a mode and starts a fresh fault log: what {@code faults} shows afterwards is this injection's. */
    public synchronized State arm(Mode m, int n) {
        mode.set(m);
        shots.set(m == null ? 0 : n);
        faults.clear();
        return state();
    }

    public synchronized State disarm() {
        mode.set(null);
        shots.set(0);
        return state();
    }

    public synchronized State state() {
        return new State(mode.get(), shots.get(), List.copyOf(faults));
    }

    /**
     * Whether {@code method} is faulted right now under the armed mode, consuming one shot if so.
     * Which methods each mode touches is decided here so the decorator stays a plain switch.
     */
    public synchronized Mode consume(String method) {
        Mode m = mode.get();
        if (m == null || shots.get() == 0) {
            return null;
        }
        boolean applies = switch (m) {
            case UNCERTAIN -> "sendTransaction".equals(method);
            case RPC_DOWN -> "sendTransaction".equals(method) || "getSignatureStatuses".equals(method) || "getBlockHeight".equals(method);
            case CONFIRM_TIMEOUT -> "getSignatureStatuses".equals(method);
        };
        if (!applies) {
            return null;
        }
        if (shots.get() > 0 && shots.decrementAndGet() == 0) {
            mode.set(null);
        }
        return m;
    }

    public synchronized void record(Mode m, String method, String detail) {
        faults.add(new Fault(Instant.now(), m, method, detail));
        if (faults.size() > 200) {
            faults.remove(0);
        }
    }
}
