package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.MarketSnapshot;
import reactor.core.publisher.Mono;

/**
 * Provider abstraction so we can swap deterministic-local (default) for Binance public REST in a
 * Phase 2 follow-up without rewiring the controller. No private API keys at any provider.
 */
public interface MarketSnapshotProvider {

    String id();

    Mono<MarketSnapshot> snapshot(String symbol);
}
