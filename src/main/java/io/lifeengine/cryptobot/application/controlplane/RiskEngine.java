package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.portfolio.Position;
import io.lifeengine.cryptobot.domain.risk.RiskFinding;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.risk.RiskSeverity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deterministic rules over a valued snapshot (and optionally its diff). No LLM here on purpose:
 * the findings are the ground truth the advisor is asked to explain, never the other way round.
 */
@Service
public class RiskEngine {

    public static final String CONCENTRATION = "CONCENTRATION";
    public static final String NO_STABLES = "NO_STABLES";
    public static final String DUST_POSITIONS = "DUST_POSITIONS";
    public static final String SHARP_MOVE = "SHARP_MOVE";
    public static final String UNKNOWN_TOKENS = "UNKNOWN_TOKENS";
    public static final String EMPTY_PORTFOLIO = "EMPTY_PORTFOLIO";

    private final RiskRulesProperties rules;
    private final Clock clock;

    public RiskEngine(RiskRulesProperties rules) {
        this.rules = rules;
        this.clock = Clock.systemUTC();
    }

    public RiskRulesProperties rules() {
        return rules;
    }

    public RiskReport evaluate(PortfolioSnapshot snapshot, PortfolioDiff diff) {
        List<RiskFinding> findings = new ArrayList<>();
        BigDecimal total = snapshot.totalUsd() == null ? BigDecimal.ZERO : snapshot.totalUsd();

        if (total.signum() <= 0) {
            findings.add(new RiskFinding(EMPTY_PORTFOLIO, RiskSeverity.LOW, "Nothing to value",
                    "The wallet holds no priced assets.", null, BigDecimal.ZERO, null));
            return finish(findings);
        }

        // 1. Concentration — the headline rule of the demo.
        for (Position p : snapshot.positions()) {
            if (!p.priced() || p.stable()) {
                continue;
            }
            BigDecimal w = p.weightPct();
            if (w.compareTo(rules.concentrationHighPct()) >= 0) {
                findings.add(new RiskFinding(CONCENTRATION, RiskSeverity.HIGH,
                        p.symbol() + " is " + w.setScale(1, RoundingMode.HALF_UP) + "% of the portfolio",
                        "A single volatile asset above " + rules.concentrationHighPct() + "% means one drawdown moves the whole wallet.",
                        p.symbol(), w, rules.concentrationHighPct()));
            } else if (w.compareTo(rules.concentrationMediumPct()) >= 0) {
                findings.add(new RiskFinding(CONCENTRATION, RiskSeverity.MEDIUM,
                        p.symbol() + " is " + w.setScale(1, RoundingMode.HALF_UP) + "% of the portfolio",
                        "Above " + rules.concentrationMediumPct() + "%: worth watching, not yet urgent.",
                        p.symbol(), w, rules.concentrationMediumPct()));
            }
        }

        // 2. No stablecoin buffer.
        BigDecimal stablePct = snapshot.positions().stream()
                .filter(p -> p.priced() && p.stable())
                .map(Position::weightPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stablePct.compareTo(rules.minStablePct()) < 0) {
            findings.add(new RiskFinding(NO_STABLES, RiskSeverity.MEDIUM,
                    "Stablecoins are " + stablePct.setScale(1, RoundingMode.HALF_UP) + "% of the portfolio",
                    "Below " + rules.minStablePct() + "% there is no dry powder to rebalance into or to cover fees in a drawdown.",
                    null, stablePct, rules.minStablePct()));
        }

        // 3. Dust.
        long dust = snapshot.positions().stream()
                .filter(p -> p.priced() && p.valueUsd().compareTo(rules.dustUsd()) < 0 && p.amount().signum() > 0)
                .count();
        if (dust > 0) {
            findings.add(new RiskFinding(DUST_POSITIONS, RiskSeverity.LOW,
                    dust + " dust position" + (dust == 1 ? "" : "s") + " under $" + rules.dustUsd(),
                    "Tiny balances cost more in fees to move than they are worth; consider closing the token accounts.",
                    null, BigDecimal.valueOf(dust), rules.dustUsd()));
        }

        // 4. Unknown / unpriced tokens.
        long unknown = snapshot.positions().stream().filter(p -> !p.priced() && p.amount().signum() > 0).count();
        if (unknown > 0) {
            findings.add(new RiskFinding(UNKNOWN_TOKENS, RiskSeverity.MEDIUM,
                    unknown + " token" + (unknown == 1 ? "" : "s") + " without a price",
                    "Unrecognised mints are excluded from the valuation. They may be airdrops, spam, or real exposure the model cannot see.",
                    null, BigDecimal.valueOf(unknown), null));
        }

        // 5. Sharp move since the previous snapshot.
        if (diff != null && diff.totalUsdDeltaPct() != null && diff.totalUsdDeltaPct().abs().compareTo(rules.sharpMovePct()) >= 0) {
            boolean down = diff.totalUsdDeltaPct().signum() < 0;
            findings.add(new RiskFinding(SHARP_MOVE, down ? RiskSeverity.HIGH : RiskSeverity.MEDIUM,
                    "Portfolio " + (down ? "fell" : "rose") + " " + diff.totalUsdDeltaPct().abs().setScale(1, RoundingMode.HALF_UP) + "% since last snapshot",
                    "Largest move: " + diff.largestMoveSymbol() + ". A move above " + rules.sharpMovePct() + "% between checks is worth a look.",
                    diff.largestMoveSymbol(), diff.totalUsdDeltaPct(), rules.sharpMovePct()));
        }

        return finish(findings);
    }

    private RiskReport finish(List<RiskFinding> findings) {
        RiskSeverity overall = findings.stream().map(RiskFinding::severity).max(Enum::compareTo).orElse(RiskSeverity.LOW);
        int score = 0;
        for (RiskFinding f : findings) {
            score += switch (f.severity()) {
                case HIGH -> 40;
                case MEDIUM -> 20;
                case LOW -> 5;
            };
        }
        return new RiskReport(findings, overall, Math.min(100, score), clock.instant());
    }
}
