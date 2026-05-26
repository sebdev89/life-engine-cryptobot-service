package io.lifeengine.cryptobot.infrastructure.snapshot;

import io.lifeengine.cryptobot.application.MarketSnapshotProvider;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic local provider for Phase 1 — no network, repeatable demo. The same symbol always
 * produces the same numbers within a minute. Mirrors the math in
 * {@code io.lifeengine.runtime.ext.cryptomarketreview.MarketSnapshotTool} on purpose: the runtime
 * stage is what the cryptobot-ui actually displays via SSE; this in-process snapshot is just for
 * the synchronous part of the {@code POST /market-review} response.
 */
@Component
@ConditionalOnProperty(name = "cryptobot.snapshot.provider", havingValue = "deterministic-local", matchIfMissing = true)
public class DeterministicLocalSnapshotProvider implements MarketSnapshotProvider {

    public static final String ID = "deterministic-local";

    private final Clock clock = Clock.systemUTC();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Mono<MarketSnapshot> snapshot(String symbol) {
        int hash = Math.abs(symbol.hashCode());
        double price = 10_000 + (hash % 90_000) + (hash % 1000) / 100.0;
        double change = -5.0 + ((hash % 1001) / 100.0);
        double volume = 1_000 + (hash % 250_000);
        return Mono.just(
                new MarketSnapshot(symbol, ID, price, change, volume, Instant.now(clock)));
    }
}
