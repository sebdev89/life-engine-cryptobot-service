package io.lifeengine.cryptobot.adapters.quotes;

import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * One exchange's public price feed, normalised. Each adapter fetches <b>every</b> ARS pair the
 * exchange lists in one round-trip; the caching service slices per asset. Only public, keyless,
 * read-only endpoints — no adapter ever holds an exchange credential.
 *
 * <p>Errors propagate: the caching layer decides between stale-and-labelled and unavailable.
 */
public interface ExchangeQuoteSource {

    /** Stable lowercase id used in the API, metrics and configuration: {@code bitso}, {@code ripio}… */
    String exchange();

    boolean enabled();

    Mono<List<ArsQuote>> fetchArsQuotes();
}
