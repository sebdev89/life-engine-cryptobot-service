package io.lifeengine.cryptobot.adapters.marketdata;

import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * One independent USD price feed. The oracle asks every enabled source for the same
 * mainnet mints and builds a consensus from what comes back; a source is <em>independent</em>
 * when its price is produced by a different mechanism (a DEX aggregator, a pull oracle network, a
 * CEX aggregator), not merely a different URL of the same data.
 *
 * <p>Contract: {@link #observe} must never error — a failure, a timeout or an unknown mint is an
 * absent observation, which the oracle counts against the quorum. A source never invents a price
 * (no static fallback here: that lives in the oracle service, labelled, and never counts as a
 * source). Same failure policy as {@code ExchangeQuoteSource} of an internal ticket.
 *
 * <p>TODO-PLATFORM an internal ticket: the HTTP-feed client underneath (rate limit, cache, timeouts,
 * circuit breaker, fixtures) is generic and belongs to the platform; this is the local port.
 */
public interface PriceSource {

    /** Stable identifier, used in observations, metrics and the quotes hash (e.g. {@code jupiter-price-v3}). */
    String id();

    boolean enabled();

    /** @param symbolByMint mainnet mint → symbol the policy speaks; the source answers only what it knows. */
    Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint);
}
