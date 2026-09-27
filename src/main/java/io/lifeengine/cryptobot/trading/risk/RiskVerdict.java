package io.lifeengine.cryptobot.trading.risk;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical output of the deterministic risk engine (Endgame §5): <b>discrete, never free
 * text</b>. An action from a fixed set, three 0–9 buckets, the reason codes, and the signals
 * (each rule that fired, in integer units) from which a UI renders prose.
 *
 * <ul>
 *   <li>{@code action}: {@code SELL} = trim {@code asset}; {@code BUY} = buy a stablecoin buffer
 *       (no {@code asset}); {@code AVOID} = nothing to value; {@code HOLD} otherwise.
 *   <li>{@code riskBucket}: 0 calm … 9 act now. {@code confidenceBucket}: how complete the input
 *       was (unpriced tokens and a missing history lower it). {@code maxPositionBucket}: largest
 *       share of one volatile asset the engine tolerates, in tenths (4 = 40 %).
 *   <li>{@code reasons}: {@code CODE} or {@code CODE:ASSET}, in evaluation order.
 *   <li>{@code signals}: {@code metric}/{@code threshold} are integers whose unit the code fixes —
 *       basis points for {@code CONCENTRATION}, {@code NO_STABLES} and {@code SHARP_MOVE}; a count
 *       for {@code DUST_POSITIONS} (threshold in micro-dollars) and {@code UNKNOWN_TOKENS}.
 * </ul>
 *
 * <p>Schema {@code risk-decision/1}. {@link #hash()} is the receipt's {@code output.hash}: what an
 * L1 verifier recomputes.
 */
public record RiskVerdict(
        Action action,
        String asset,
        int riskBucket,
        int confidenceBucket,
        int maxPositionBucket,
        List<String> reasons,
        List<Signal> signals,
        RiskSeverity overall,
        int score) {

    public static final String SCHEMA = "risk-decision/1";

    public enum Action { HOLD, BUY, SELL, AVOID }

    public record Signal(String code, RiskSeverity severity, String asset, long metric, Long threshold) {
        public Signal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("severity", severity.name());
            if (asset != null) {
                m.put("asset", asset);
            }
            m.put("metric", metric);
            if (threshold != null) {
                m.put("threshold", threshold);
            }
            return m;
        }
    }

    public RiskVerdict {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(overall, "overall");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        signals = List.copyOf(signals == null ? List.of() : signals);
        requireBucket("riskBucket", riskBucket);
        requireBucket("confidenceBucket", confidenceBucket);
        requireBucket("maxPositionBucket", maxPositionBucket);
    }

    private static void requireBucket(String name, int v) {
        if (v < 0 || v > 9) {
            throw new IllegalArgumentException(name + " must be 0..9, got " + v);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("action", action.name());
        if (asset != null) {
            m.put("asset", asset);
        }
        m.put("riskBucket", riskBucket);
        m.put("confidenceBucket", confidenceBucket);
        m.put("maxPositionBucket", maxPositionBucket);
        m.put("reasons", reasons);
        List<Map<String, Object>> ss = new ArrayList<>();
        for (Signal s : signals) {
            ss.add(s.toMap());
        }
        m.put("signals", ss);
        m.put("overall", overall.name());
        m.put("score", score);
        return m;
    }

    public byte[] canonicalBytes() {
        return JsonCanonicalizer.canonicalBytes(toMap());
    }

    /** {@code sha256:…} of the canonical bytes — the receipt's {@code output.hash}. */
    public String hash() {
        return Digests.sha256(canonicalBytes());
    }

    @SuppressWarnings("unchecked")
    public static RiskVerdict fromMap(Map<String, Object> m) {
        String schema = (String) m.get("schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("risk verdict schema " + schema + " is not " + SCHEMA);
        }
        List<Signal> signals = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("signals", List.of())) {
            Map<String, Object> s = (Map<String, Object>) o;
            Object threshold = s.get("threshold");
            signals.add(new Signal((String) s.get("code"), RiskSeverity.valueOf((String) s.get("severity")), (String) s.get("asset"),
                    ((Number) s.get("metric")).longValue(), threshold == null ? null : ((Number) threshold).longValue()));
        }
        return new RiskVerdict(Action.valueOf((String) m.get("action")), (String) m.get("asset"),
                ((Number) m.get("riskBucket")).intValue(), ((Number) m.get("confidenceBucket")).intValue(), ((Number) m.get("maxPositionBucket")).intValue(),
                (List<String>) m.getOrDefault("reasons", List.of()), signals, RiskSeverity.valueOf((String) m.get("overall")), ((Number) m.get("score")).intValue());
    }
}
