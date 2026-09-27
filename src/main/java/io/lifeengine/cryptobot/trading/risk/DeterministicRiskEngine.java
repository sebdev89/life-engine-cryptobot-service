package io.lifeengine.cryptobot.trading.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic risk model, v1 (Endgame §5): a pure function
 * {@code (weights, RiskInput) → RiskVerdict} over integers only. No floating point, no clock, no
 * randomness, no I/O, no parallelism, no native code — so the output bytes, and therefore the
 * {@code outputHash}, are identical on every JVM ≥ 17 and every architecture by construction.
 * {@code RiskEngineGoldenTest} pins 200 input → hash pairs and fails on any drift that is not
 * accompanied by a version bump.
 *
 * <p>What it does: validates, classifies, bounds. It does <b>not</b> reason or investigate — that
 * is the smart layer (the LLM advisor), which is L0. The right sentence is <i>"the final decision
 * passes through a deterministic, versioned, reproducible validator"</i>, never "the AI is
 * deterministic".
 *
 * <h2>Rules (all thresholds from {@link RiskWeights})</h2>
 *
 * <ol>
 *   <li>Nothing to value ({@code totalUsdMicros == 0}) → {@code EMPTY_PORTFOLIO} (LOW), action {@code AVOID}.
 *   <li>Each priced, non-stable position with {@code exposureBps ≥ concentrationHighBps} →
 *       {@code CONCENTRATION} HIGH; {@code ≥ concentrationMediumBps} → MEDIUM.
 *   <li>Stable share {@code < minStableBps} → {@code NO_STABLES} MEDIUM.
 *   <li>Priced, held positions worth {@code < dustUsdMicros} → {@code DUST_POSITIONS} LOW (metric = count).
 *   <li>Held, unpriced positions → {@code UNKNOWN_TOKENS} MEDIUM (metric = count).
 *   <li>{@code |deltaBps| ≥ sharpMoveBps} → {@code SHARP_MOVE} HIGH if down, MEDIUM if up.
 * </ol>
 *
 * Score = Σ points(severity), capped; buckets and action are integer functions of the signals
 * (see the code, it is short on purpose). Ties are broken by fixed order: the {@code SELL} target
 * is the concentrated asset with the largest exposure, then the smallest mint (the input's order).
 */
public final class DeterministicRiskEngine {

    public static final String ID = "risk-engine";
    public static final String VERSION = "1.0.0";

    public static final String CONCENTRATION = "CONCENTRATION";
    public static final String NO_STABLES = "NO_STABLES";
    public static final String DUST_POSITIONS = "DUST_POSITIONS";
    public static final String SHARP_MOVE = "SHARP_MOVE";
    public static final String UNKNOWN_TOKENS = "UNKNOWN_TOKENS";
    public static final String EMPTY_PORTFOLIO = "EMPTY_PORTFOLIO";

    private final RiskWeights weights;

    public DeterministicRiskEngine(RiskWeights weights) {
        this.weights = Objects.requireNonNull(weights, "weights");
        if (!ID.equals(weights.engine())) {
            throw new IllegalArgumentException("weights are for engine " + weights.engine() + ", not " + ID);
        }
    }

    public static DeterministicRiskEngine v1() {
        return new DeterministicRiskEngine(RiskWeights.v1());
    }

    public RiskWeights weights() {
        return weights;
    }

    public String weightsHash() {
        return weights.hash();
    }

    /** The model, end to end: quantised input in, discrete verdict out. */
    public DeterministicDecision decide(RiskInput input) {
        return new DeterministicDecision(ID, VERSION, weights.hash(), input, verdict(input));
    }

    public RiskVerdict verdict(RiskInput in) {
        List<RiskVerdict.Signal> signals = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (in.totalUsdMicros() <= 0) {
            signals.add(new RiskVerdict.Signal(EMPTY_PORTFOLIO, RiskSeverity.LOW, null, 0, null));
            reasons.add(EMPTY_PORTFOLIO);
            int confidence = clamp(weights.confidenceStart() - (in.deltaBps() == null ? weights.confidencePenaltyNoHistory() : 0));
            return new RiskVerdict(RiskVerdict.Action.AVOID, null, 0, confidence, clamp(weights.maxPositionBase()), reasons, signals,
                    RiskSeverity.LOW, 0);
        }

        // 1. Concentration.
        RiskInput.PositionInput sellTarget = null;
        for (RiskInput.PositionInput p : in.positions()) {
            if (!p.priced() || p.stable()) {
                continue;
            }
            if (p.exposureBps() >= weights.concentrationHighBps()) {
                signals.add(new RiskVerdict.Signal(CONCENTRATION, RiskSeverity.HIGH, p.asset(), p.exposureBps(), (long) weights.concentrationHighBps()));
                reasons.add(CONCENTRATION + "_HIGH:" + p.asset());
                if (sellTarget == null || p.exposureBps() > sellTarget.exposureBps()) {
                    sellTarget = p; // positions are sorted by mint, so an equal exposure keeps the first: deterministic
                }
            } else if (p.exposureBps() >= weights.concentrationMediumBps()) {
                signals.add(new RiskVerdict.Signal(CONCENTRATION, RiskSeverity.MEDIUM, p.asset(), p.exposureBps(), (long) weights.concentrationMediumBps()));
                reasons.add(CONCENTRATION + "_MEDIUM:" + p.asset());
            }
        }

        // 2. Stable buffer.
        long stableBps = 0;
        for (RiskInput.PositionInput p : in.positions()) {
            if (p.priced() && p.stable()) {
                stableBps += p.exposureBps();
            }
        }
        boolean noStables = stableBps < weights.minStableBps();
        if (noStables) {
            signals.add(new RiskVerdict.Signal(NO_STABLES, RiskSeverity.MEDIUM, null, stableBps, (long) weights.minStableBps()));
            reasons.add(NO_STABLES);
        }

        // 3. Dust.
        long dust = 0;
        for (RiskInput.PositionInput p : in.positions()) {
            if (p.priced() && p.held() && p.valueUsdMicros() < weights.dustUsdMicros()) {
                dust++;
            }
        }
        if (dust > 0) {
            signals.add(new RiskVerdict.Signal(DUST_POSITIONS, RiskSeverity.LOW, null, dust, weights.dustUsdMicros()));
            reasons.add(DUST_POSITIONS);
        }

        // 4. Unpriced.
        long unknown = 0;
        for (RiskInput.PositionInput p : in.positions()) {
            if (!p.priced() && p.held()) {
                unknown++;
            }
        }
        if (unknown > 0) {
            signals.add(new RiskVerdict.Signal(UNKNOWN_TOKENS, RiskSeverity.MEDIUM, null, unknown, null));
            reasons.add(UNKNOWN_TOKENS);
        }

        // 5. Sharp move since the previous snapshot.
        boolean sharpDrop = false;
        if (in.deltaBps() != null && Math.abs(in.deltaBps()) >= weights.sharpMoveBps()) {
            sharpDrop = in.deltaBps() < 0;
            signals.add(new RiskVerdict.Signal(SHARP_MOVE, sharpDrop ? RiskSeverity.HIGH : RiskSeverity.MEDIUM, in.largestMoveAsset(), in.deltaBps(),
                    (long) weights.sharpMoveBps()));
            reasons.add(SHARP_MOVE + (sharpDrop ? "_DOWN" : "_UP"));
        }

        // Aggregate: severity, score, buckets, action.
        RiskSeverity overall = RiskSeverity.LOW;
        int score = 0;
        for (RiskVerdict.Signal s : signals) {
            if (s.severity().atLeast(overall)) {
                overall = s.severity();
            }
            score += weights.points(s.severity());
        }
        score = Math.min(weights.scoreCap(), score);
        int riskBucket = clamp(score / weights.riskPointsPerBucket());
        int confidence = clamp(weights.confidenceStart() - (int) Math.min(9, unknown * weights.confidencePenaltyPerUnpriced())
                - (in.deltaBps() == null ? weights.confidencePenaltyNoHistory() : 0));
        int maxPosition = clamp(weights.maxPositionBase() - (overall == RiskSeverity.HIGH ? weights.maxPositionPenaltyHighRisk() : 0)
                - (sharpDrop ? weights.maxPositionPenaltySharpDrop() : 0));

        RiskVerdict.Action action;
        String asset = null;
        if (sellTarget != null) {
            action = RiskVerdict.Action.SELL;
            asset = sellTarget.asset();
        } else if (sharpDrop) {
            action = RiskVerdict.Action.HOLD;
        } else if (noStables) {
            action = RiskVerdict.Action.BUY;
        } else {
            action = RiskVerdict.Action.HOLD;
        }
        return new RiskVerdict(action, asset, riskBucket, confidence, maxPosition, reasons, signals, overall, score);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(9, v));
    }
}
