package io.lifeengine.cryptobot.application.paper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paper trading (KAN-1217, CryptoBot v3). Off by default; enabled only where it is measured (k8s-uat). Every
 * {@code interval} the job applies each closed {@code granularity} candle to every portfolio. The portfolios and
 * their knobs are fixed before the window starts and frozen in the database at the first tick: a portfolio whose
 * configuration later differs from what was frozen is not advanced (that would be fitting the window), it is
 * reported as {@code PARAMS_CHANGED}. Start a new window with a new id instead.
 *
 * <p>KAN-1276: the job fires on the wall clock, at every multiple of {@code interval} plus {@code settle} (default
 * 30 s, the time a just-closed candle takes to be served), not {@code interval} after the process started — so the
 * delay from a candle's close to its decision is the same after every deploy. {@code settle} must be shorter than
 * {@code interval}.
 *
 * <p>Defaults: 1000 USD each, 30 bps fee + 20 bps slippage (the v2 backtest's), and four portfolios — the two
 * benchmarks, the 50 % ± 5 % band and 50 USD/day DCA.
 */
@ConfigurationProperties(prefix = "cryptobot.paper")
public record PaperProperties(
        boolean enabled,
        Duration interval,
        Duration granularity,
        Integer maxCatchUpBars,
        BigDecimal initialUsd,
        Integer feeBps,
        Integer slippageBps,
        List<PortfolioDef> portfolios,
        Duration settle) {

    public PaperProperties {
        interval = interval == null ? Duration.ofMinutes(5) : interval;
        settle = settle == null ? Duration.ofSeconds(30) : settle;
        if (interval.isNegative() || interval.isZero() || settle.isNegative() || settle.compareTo(interval) >= 0) {
            throw new IllegalArgumentException("cryptobot.paper: interval must be > 0 and 0 <= settle < interval (interval=" + interval + ", settle=" + settle + ")");
        }
        granularity = granularity == null ? Duration.ofHours(1) : granularity;
        maxCatchUpBars = maxCatchUpBars == null ? 168 : maxCatchUpBars;
        initialUsd = initialUsd == null ? new BigDecimal("1000") : initialUsd;
        feeBps = feeBps == null ? 30 : feeBps;
        slippageBps = slippageBps == null ? 20 : slippageBps;
        portfolios = portfolios == null || portfolios.isEmpty()
                ? List.of(
                        new PortfolioDef("hold-sol", "hold-sol", null, null, null, null),
                        new PortfolioDef("hold-usdc", "hold-usdc", null, null, null, null),
                        new PortfolioDef("band-50-500", "band-rebalance", new BigDecimal("0.5"), 500, null, null),
                        new PortfolioDef("dca-50-24", "dca", null, null, new BigDecimal("50"), 24))
                : List.copyOf(portfolios);
    }

    /** One forward-tested strategy; {@code id} names the window (change it to start a new one). */
    public record PortfolioDef(String id, String strategy, BigDecimal targetSolWeight, Integer bandBps, BigDecimal dcaAmountUsd, Integer dcaEveryBars) {}
}
