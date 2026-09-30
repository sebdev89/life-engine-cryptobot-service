package io.lifeengine.cryptobot.application.quotes;

import io.lifeengine.cryptobot.domain.quotes.QuoteBoard;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Port: ARS quotes of one crypto asset across the Argentine exchanges CryptoBot knows.
 *
 * <p>Contract: never errors. An exchange that is down, slow, or does not list the asset shows up
 * in {@link QuoteBoard#unavailable()} with a reason; the others are returned. Implementations own
 * the caching (30–60 s) so callers — the REST endpoint, and the advisor context builder that
 * feeds {@code crypto.portfolio-advisor.v1} — can call it freely. Runtime never calls exchanges.
 */
public interface ArsQuotesPort {

    Mono<QuoteBoard> board(String asset);

    /** Ids of every configured exchange, enabled or not, in the order boards list them. */
    List<String> exchanges();
}
