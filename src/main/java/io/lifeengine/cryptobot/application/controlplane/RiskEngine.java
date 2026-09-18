package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.risk.DeterministicDecision;
import io.lifeengine.cryptobot.domain.risk.DeterministicRiskEngine;
import io.lifeengine.cryptobot.domain.risk.RiskFinding;
import io.lifeengine.cryptobot.domain.risk.RiskInput;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.risk.RiskSeverity;
import io.lifeengine.cryptobot.domain.risk.RiskVerdict;
import io.lifeengine.cryptobot.domain.risk.RiskWeights;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Deterministic rules over a valued snapshot (and optionally its diff). No LLM here on purpose:
 * the findings are the ground truth the advisor is asked to explain, never the other way round.
 *
 * <p>Since KAN-392 this is an adapter: it quantises the snapshot into the canonical integer
 * {@link RiskInput}, runs the pure {@link DeterministicRiskEngine} (versioned, weights hashed),
 * and renders its discrete {@link RiskVerdict} as the {@link RiskReport} the UI already shows.
 * The prose below is presentation; nothing in it enters a hash.
 */
@Service
public class RiskEngine {

    public static final String CONCENTRATION = DeterministicRiskEngine.CONCENTRATION;
    public static final String NO_STABLES = DeterministicRiskEngine.NO_STABLES;
    public static final String DUST_POSITIONS = DeterministicRiskEngine.DUST_POSITIONS;
    public static final String SHARP_MOVE = DeterministicRiskEngine.SHARP_MOVE;
    public static final String UNKNOWN_TOKENS = DeterministicRiskEngine.UNKNOWN_TOKENS;
    public static final String EMPTY_PORTFOLIO = DeterministicRiskEngine.EMPTY_PORTFOLIO;

    private final DeterministicRiskEngine engine;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    /** Test-friendly: the shipped weights, metrics to a private registry nobody scrapes. */
    public RiskEngine() {
        this(DeterministicRiskEngine.v1(), CryptobotMetrics.noop());
    }

    @Autowired
    public RiskEngine(CryptobotMetrics metrics) {
        this(DeterministicRiskEngine.v1(), metrics);
    }

    public RiskEngine(DeterministicRiskEngine engine, CryptobotMetrics metrics) {
        this.engine = engine;
        this.metrics = metrics;
        this.clock = Clock.systemUTC();
    }

    public DeterministicRiskEngine engine() {
        return engine;
    }

    public RiskWeights weights() {
        return engine.weights();
    }

    public RiskReport evaluate(PortfolioSnapshot snapshot, PortfolioDiff diff) {
        DeterministicDecision decision = engine.decide(RiskInput.quantize(snapshot, diff));
        RiskVerdict v = decision.output();
        List<RiskFinding> findings = new ArrayList<>();
        for (RiskVerdict.Signal s : v.signals()) {
            findings.add(render(s));
        }
        metrics.riskAnalysis(v.overall().name());
        return new RiskReport(findings, v.overall(), v.score(), clock.instant(), decision);
    }

    /** Prose for one signal. Metric/threshold come back in the units the UI shows (percent, dollars, counts). */
    private RiskFinding render(RiskVerdict.Signal s) {
        RiskWeights w = engine.weights();
        return switch (s.code()) {
            case DeterministicRiskEngine.EMPTY_PORTFOLIO -> new RiskFinding(s.code(), s.severity(), "Nothing to value",
                    "The wallet holds no priced assets.", null, BigDecimal.ZERO, null);
            case DeterministicRiskEngine.CONCENTRATION -> {
                BigDecimal pct = pct(s.metric());
                BigDecimal threshold = pct(s.threshold());
                yield new RiskFinding(s.code(), s.severity(), s.asset() + " is " + pct.setScale(1, RoundingMode.HALF_UP) + "% of the portfolio",
                        s.severity() == RiskSeverity.HIGH
                                ? "A single volatile asset above " + plain(threshold) + "% means one drawdown moves the whole wallet."
                                : "Above " + plain(threshold) + "%: worth watching, not yet urgent.",
                        s.asset(), pct, threshold);
            }
            case DeterministicRiskEngine.NO_STABLES -> {
                BigDecimal pct = pct(s.metric());
                BigDecimal threshold = pct(s.threshold());
                yield new RiskFinding(s.code(), s.severity(), "Stablecoins are " + pct.setScale(1, RoundingMode.HALF_UP) + "% of the portfolio",
                        "Below " + plain(threshold) + "% there is no dry powder to rebalance into or to cover fees in a drawdown.",
                        null, pct, threshold);
            }
            case DeterministicRiskEngine.DUST_POSITIONS -> {
                BigDecimal dustUsd = usd(w.dustUsdMicros());
                yield new RiskFinding(s.code(), s.severity(), s.metric() + " dust position" + (s.metric() == 1 ? "" : "s") + " under $" + plain(dustUsd),
                        "Tiny balances cost more in fees to move than they are worth; consider closing the token accounts.",
                        null, BigDecimal.valueOf(s.metric()), dustUsd);
            }
            case DeterministicRiskEngine.UNKNOWN_TOKENS -> new RiskFinding(s.code(), s.severity(),
                    s.metric() + " token" + (s.metric() == 1 ? "" : "s") + " without a price",
                    "Unrecognised mints are excluded from the valuation. They may be airdrops, spam, or real exposure the model cannot see.",
                    null, BigDecimal.valueOf(s.metric()), null);
            case DeterministicRiskEngine.SHARP_MOVE -> {
                BigDecimal pct = pct(s.metric());
                BigDecimal threshold = pct(s.threshold());
                boolean down = s.metric() < 0;
                yield new RiskFinding(s.code(), s.severity(),
                        "Portfolio " + (down ? "fell" : "rose") + " " + pct.abs().setScale(1, RoundingMode.HALF_UP) + "% since last snapshot",
                        "Largest move: " + s.asset() + ". A move above " + plain(threshold) + "% between checks is worth a look.",
                        s.asset(), pct, threshold);
            }
            default -> new RiskFinding(s.code(), s.severity(), s.code(), "", s.asset(), BigDecimal.valueOf(s.metric()),
                    s.threshold() == null ? null : BigDecimal.valueOf(s.threshold()));
        };
    }

    private static BigDecimal pct(long bps) {
        return BigDecimal.valueOf(bps, 2);
    }

    private static BigDecimal pct(Long bps) {
        return bps == null ? null : pct(bps.longValue());
    }

    private static BigDecimal usd(long micros) {
        return BigDecimal.valueOf(micros, 6);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
