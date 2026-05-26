package io.lifeengine.cryptobot.domain;

import java.time.Instant;

public record MarketSnapshot(
        String symbol,
        String source,
        double price,
        double priceChangePct24h,
        double volumeBase24h,
        Instant observedAt) {}
