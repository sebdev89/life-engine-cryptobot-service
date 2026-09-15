package io.lifeengine.cryptobot.domain.quotes;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Withdrawal fee of one asset over one network (BTC, LN, BSC, ETH, TRON, SOL, POLYGON, RSK…),
 * expressed in units of the asset. {@link FeeSource} says where the number came from so the UI
 * and the advisor never present a hand-maintained value as if the exchange had published it.
 */
public record NetworkFee(String network, BigDecimal amount, FeeSource source) {

    public enum FeeSource {
        /** Read from a public endpoint of the exchange during the fetch. */
        EXCHANGE_PUBLISHED,
        /** Configured by hand in {@code cryptobot.quotes.withdrawal-fees}; may be outdated. */
        CONFIGURED
    }

    public NetworkFee {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(source, "source");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("fee must be >= 0 on " + network);
        }
        network = network.trim().toUpperCase();
    }
}
