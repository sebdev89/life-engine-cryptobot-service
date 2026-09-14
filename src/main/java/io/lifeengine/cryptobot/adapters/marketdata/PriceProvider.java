package io.lifeengine.cryptobot.adapters.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** USD price per mainnet mint. Implementations must never error: unknown mints are simply absent. */
public interface PriceProvider {

    record PriceQuote(String mint, BigDecimal priceUsd, String source, Instant asOf, BigDecimal change24hPct) {}

    Mono<Map<String, PriceQuote>> prices(Set<String> mainnetMints);
}
