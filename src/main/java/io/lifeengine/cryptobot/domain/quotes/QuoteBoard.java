package io.lifeengine.cryptobot.domain.quotes;

import java.time.Instant;
import java.util.List;

/**
 * Everything the port knows about one asset right now: the quotes that came back plus, per
 * exchange that did not, the reason. Never hides a missing exchange behind an empty list.
 */
public record QuoteBoard(String asset, List<ArsQuote> quotes, List<Unavailable> unavailable, Instant generatedAt) {

    public QuoteBoard {
        asset = asset.toUpperCase();
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    /** Why an exchange has no quote for this asset in this board. */
    public record Unavailable(String exchange, Reason reason, String detail) {
        public enum Reason {
            /** The exchange answered but does not list {@code asset}/ARS. */
            NOT_LISTED,
            /** The fetch failed and there was no fresh-enough cached value to fall back to. */
            FETCH_FAILED,
            /** The adapter is switched off by configuration. */
            DISABLED
        }
    }
}
