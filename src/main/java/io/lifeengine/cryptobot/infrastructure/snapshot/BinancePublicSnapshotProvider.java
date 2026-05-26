package io.lifeengine.cryptobot.infrastructure.snapshot;

import io.lifeengine.cryptobot.application.MarketSnapshotProvider;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.binance.BinancePublicClient;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Phase-1 Binance-backed provider. Activated by setting
 * {@code cryptobot.snapshot.provider=binance-public}. Falls back to the deterministic provider
 * on any Binance failure (the upstream client returns {@code Mono.empty()} for errors).
 */
@Component
@ConditionalOnProperty(name = "cryptobot.snapshot.provider", havingValue = "binance-public")
public class BinancePublicSnapshotProvider implements MarketSnapshotProvider {

    public static final String ID = "binance-public";

    private final BinancePublicClient binance;
    private final DeterministicLocalSnapshotProvider fallback;
    private final Clock clock = Clock.systemUTC();

    public BinancePublicSnapshotProvider(BinancePublicClient binance) {
        this.binance = binance;
        this.fallback = new DeterministicLocalSnapshotProvider();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Mono<MarketSnapshot> snapshot(String symbol) {
        return binance
                .ticker24h(symbol)
                .map(t -> new MarketSnapshot(
                        symbol,
                        ID,
                        t.lastPrice().doubleValue(),
                        t.change24hPct().doubleValue(),
                        t.quoteVolume24h().doubleValue(),
                        Instant.now(clock)))
                .switchIfEmpty(fallback.snapshot(symbol));
    }
}
