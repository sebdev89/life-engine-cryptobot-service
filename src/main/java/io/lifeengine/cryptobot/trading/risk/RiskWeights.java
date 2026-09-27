package io.lifeengine.cryptobot.trading.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/**
 * The versioned parameters of the deterministic risk engine (Endgame §5): thresholds in basis
 * points / micro-dollars, scoring points and bucket rules — <b>integers only</b>, loaded from a
 * JSON file that ships with the service ({@code risk-engine/weights-v<N>.json}).
 *
 * <p>{@link #hash()} is {@code SHA-256} of the RFC 8785 canonical form of the whole file: the
 * {@code weightsHash} every {@code RISK_DECISION} receipt names. Same file ⇒ same hash on any
 * host; an edited threshold ⇒ a different hash, and the golden test refuses it unless the engine
 * version is bumped with it. There is no runtime override of these values on purpose: a
 * property-driven threshold would make the receipt's {@code weightsHash} describe a file the
 * engine was not actually running.
 */
public final class RiskWeights {

    public static final String V1_RESOURCE = "risk-engine/weights-v1.json";

    private final Map<String, Object> tree;
    private final String hash;
    private final String engine;
    private final String version;

    private final int concentrationHighBps;
    private final int concentrationMediumBps;
    private final int minStableBps;
    private final long dustUsdMicros;
    private final int sharpMoveBps;
    private final int pointsHigh;
    private final int pointsMedium;
    private final int pointsLow;
    private final int scoreCap;
    private final int riskPointsPerBucket;
    private final int confidenceStart;
    private final int confidencePenaltyPerUnpriced;
    private final int confidencePenaltyNoHistory;
    private final int maxPositionBase;
    private final int maxPositionPenaltyHighRisk;
    private final int maxPositionPenaltySharpDrop;

    @SuppressWarnings("unchecked")
    private RiskWeights(Map<String, Object> tree) {
        this.tree = tree;
        // Canonicalising refuses floats and nulls: a weights file that is not integer-only never loads.
        this.hash = Digests.sha256(JsonCanonicalizer.canonicalBytes(tree));
        this.engine = text(tree, "engine");
        this.version = text(tree, "version");
        Map<String, Object> thresholds = section(tree, "thresholds");
        Map<String, Object> points = section(tree, "severityPoints");
        Map<String, Object> buckets = section(tree, "buckets");
        this.concentrationHighBps = integer(thresholds, "concentrationHighBps");
        this.concentrationMediumBps = integer(thresholds, "concentrationMediumBps");
        this.minStableBps = integer(thresholds, "minStableBps");
        this.dustUsdMicros = longValue(thresholds, "dustUsdMicros");
        this.sharpMoveBps = integer(thresholds, "sharpMoveBps");
        this.pointsHigh = integer(points, "HIGH");
        this.pointsMedium = integer(points, "MEDIUM");
        this.pointsLow = integer(points, "LOW");
        this.scoreCap = integer(tree, "scoreCap");
        this.riskPointsPerBucket = integer(buckets, "riskPointsPerBucket");
        this.confidenceStart = integer(buckets, "confidenceStart");
        this.confidencePenaltyPerUnpriced = integer(buckets, "confidencePenaltyPerUnpriced");
        this.confidencePenaltyNoHistory = integer(buckets, "confidencePenaltyNoHistory");
        this.maxPositionBase = integer(buckets, "maxPositionBase");
        this.maxPositionPenaltyHighRisk = integer(buckets, "maxPositionPenaltyHighRisk");
        this.maxPositionPenaltySharpDrop = integer(buckets, "maxPositionPenaltySharpDrop");
        if (concentrationMediumBps > concentrationHighBps || riskPointsPerBucket <= 0) {
            throw new IllegalArgumentException("risk weights are inconsistent: medium > high or bucket size <= 0");
        }
    }

    /** The weights shipped with this build (v1). */
    public static RiskWeights v1() {
        return fromClasspath(V1_RESOURCE);
    }

    public static RiskWeights fromClasspath(String resource) {
        try (InputStream in = RiskWeights.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("risk weights not found on the classpath: " + resource);
            }
            return fromJson(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read risk weights " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static RiskWeights fromJson(byte[] json) {
        try {
            return new RiskWeights(new ObjectMapper().readValue(json, Map.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("risk weights are not valid JSON", e);
        }
    }

    public static RiskWeights fromTree(Map<String, Object> tree) {
        return new RiskWeights(tree);
    }

    /** {@code sha256:…} of the canonical weights file — the receipt's {@code engine.weightsHash}. */
    public String hash() {
        return hash;
    }

    public String engine() {
        return engine;
    }

    public String version() {
        return version;
    }

    /** The value tree exactly as loaded, for anyone who wants to recompute {@link #hash()}. */
    public Map<String, Object> tree() {
        return tree;
    }

    public int concentrationHighBps() {
        return concentrationHighBps;
    }

    public int concentrationMediumBps() {
        return concentrationMediumBps;
    }

    public int minStableBps() {
        return minStableBps;
    }

    public long dustUsdMicros() {
        return dustUsdMicros;
    }

    public int sharpMoveBps() {
        return sharpMoveBps;
    }

    public int points(RiskSeverity severity) {
        return switch (severity) {
            case HIGH -> pointsHigh;
            case MEDIUM -> pointsMedium;
            case LOW -> pointsLow;
        };
    }

    public int scoreCap() {
        return scoreCap;
    }

    public int riskPointsPerBucket() {
        return riskPointsPerBucket;
    }

    public int confidenceStart() {
        return confidenceStart;
    }

    public int confidencePenaltyPerUnpriced() {
        return confidencePenaltyPerUnpriced;
    }

    public int confidencePenaltyNoHistory() {
        return confidencePenaltyNoHistory;
    }

    public int maxPositionBase() {
        return maxPositionBase;
    }

    public int maxPositionPenaltyHighRisk() {
        return maxPositionPenaltyHighRisk;
    }

    public int maxPositionPenaltySharpDrop() {
        return maxPositionPenaltySharpDrop;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> tree, String key) {
        Object v = tree.get(key);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("risk weights: missing section '" + key + "'");
        }
        return (Map<String, Object>) m;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("risk weights: missing text '" + key + "'");
        }
        return s;
    }

    private static int integer(Map<String, Object> m, String key) {
        long v = longValue(m, key);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("risk weights: '" + key + "' does not fit an int");
        }
        return (int) v;
    }

    private static long longValue(Map<String, Object> m, String key) {
        Object v = Objects.requireNonNull(m, "section").get(key);
        if (v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        throw new IllegalArgumentException("risk weights: '" + key + "' must be an integer, got " + v);
    }
}
