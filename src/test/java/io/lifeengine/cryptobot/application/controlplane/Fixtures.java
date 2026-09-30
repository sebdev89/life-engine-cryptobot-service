package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import io.lifeengine.cryptobot.core.oracle.PriceOracle;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.trading.portfolio.Position;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Hand-built snapshots so the engines can be tested without RPC or prices. */
final class Fixtures {

    static final String ADDRESS = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    private Fixtures() {}

    static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    static final OracleLimits ORACLE_LIMITS = new OracleLimits(2, 60, 100, 1_000, 300);

    static Wallet wallet(SolanaCluster cluster) {
        Instant now = NOW;
        return new Wallet(UUID.randomUUID(), OWNER, ADDRESS, cluster.toNetwork(), "demo", now, now);
    }

    /** an accepted two-source reading for SOL and USDC, priced {@code solPrice} / $1, observed at {@code at}. */
    static OracleReading oracle(String solPrice, Instant at) {
        return new OracleReading(at, ORACLE_LIMITS, List.of(consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, solPrice, solPrice, at),
                consensus("USDC", TokenRegistry.USDC_MINT, "1", "1", at)));
    }

    /** The world as the oracle sees it now: SOL at $100, what every legacy snapshot was priced at. */
    static OracleReading oracle() {
        return oracle("100", NOW);
    }

    static OracleConsensus consensus(String symbol, String mint, String jupiterPrice, String pythPrice, Instant at) {
        return consensus(symbol, mint, jupiterPrice, pythPrice, at, at);
    }

    /** Observed at {@code observedAt}, evaluated at {@code now}: the two differ when the observations are stale. */
    static OracleConsensus consensus(String symbol, String mint, String jupiterPrice, String pythPrice, Instant observedAt, Instant now) {
        return PriceOracle.consensus(symbol, mint, List.of(
                new PriceObservation("jupiter", symbol, mint, new BigDecimal(jupiterPrice), observedAt),
                new PriceObservation("pyth", symbol, mint, new BigDecimal(pythPrice), observedAt)), null, now, ORACLE_LIMITS);
    }

    /** SOL 7 @ $100 = 700, USDC 300 → SOL 70% / USDC 30%. */
    static PortfolioSnapshot solHeavy() {
        return snapshot(new Object[][] {
            {TokenRegistry.NATIVE_SOL_MINT, "SOL", "7", "100", false, true},
            {TokenRegistry.USDC_MINT, "USDC", "300", "1", true, false},
        });
    }

    /** SOL 300 / USDC 350 / JUP 350 → nothing above 40%, stables 35%. */
    static PortfolioSnapshot balanced() {
        return snapshot(new Object[][] {
            {TokenRegistry.NATIVE_SOL_MINT, "SOL", "3", "100", false, true},
            {TokenRegistry.USDC_MINT, "USDC", "350", "1", true, false},
            {"JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "700", "0.5", false, false},
        });
    }

    static PortfolioSnapshot snapshot(Object[][] rows) {
        List<Position> raw = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] r : rows) {
            BigDecimal amount = new BigDecimal((String) r[2]);
            BigDecimal price = r[3] == null ? null : new BigDecimal((String) r[3]);
            BigDecimal value = price == null ? null : amount.multiply(price);
            if (value != null) {
                total = total.add(value);
            }
            raw.add(new Position((String) r[0], (String) r[1], amount, 9, price, value, null, (Boolean) r[4], (Boolean) r[5], "test"));
        }
        final BigDecimal t = total;
        List<Position> weighted = raw.stream().map(p -> new Position(p.mint(), p.symbol(), p.amount(), p.decimals(), p.priceUsd(), p.valueUsd(),
                p.valueUsd() == null ? null : p.valueUsd().multiply(new BigDecimal("100")).divide(t, 4, RoundingMode.HALF_UP),
                p.stable(), p.nativeSol(), p.priceSource())).toList();
        return new PortfolioSnapshot(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-09-14T12:00:00Z"), total, weighted, "test", 3);
    }
}
