package io.lifeengine.cryptobot.adapters.quotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Bitso — public ticker, documented at {@code https://docs.bitso.com} ("Ticker", no auth).
 *
 * <pre>
 * GET /v3/ticker/
 * {"success":true,"payload":[{"book":"btc_ars","ask":"...","bid":"...","last":"...","created_at":"..."}, …]}
 * </pre>
 *
 * Books are {@code <base>_<quote>} lowercase; only {@code *_ars} books are kept. Withdrawal fees
 * live behind the authenticated {@code /v3/fees/} endpoint, so Bitso never reports
 * {@code EXCHANGE_PUBLISHED} fees here — only configured ones, if any.
 */
@Component
@Order(10)
public class BitsoQuoteSource extends PublicJsonQuoteSource {

    public static final String ID = "bitso";
    static final String DEFAULT_BASE_URL = "https://api.bitso.com";

    @Autowired
    public BitsoQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    BitsoQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper, Clock clock) {
        super(ID, builder, properties, objectMapper, clock, DEFAULT_BASE_URL);
    }

    @Override
    protected String path() {
        return "/v3/ticker/";
    }

    @Override
    protected List<ArsQuote> parse(JsonNode root, Instant now) {
        if (root.has("success") && !root.get("success").asBoolean(true)) {
            throw new IllegalStateException("bitso: success=false " + root.path("error").path("message").asText(""));
        }
        JsonNode payload = root.path("payload");
        List<ArsQuote> out = new ArrayList<>();
        if (payload.isObject()) {
            parseBook(payload, now).ifPresent(out::add);
        } else if (payload.isArray()) {
            for (JsonNode book : payload) {
                parseBook(book, now).ifPresent(out::add);
            }
        }
        return out;
    }

    private java.util.Optional<ArsQuote> parseBook(JsonNode book, Instant now) {
        String name = text(book, "book").orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith("_ars")) {
            return java.util.Optional.empty();
        }
        String asset = name.substring(0, name.length() - "_ars".length()).toUpperCase(Locale.ROOT);
        Instant asOf = text(book, "created_at").map(BitsoQuoteSource::instantOrNull).orElse(now);
        return quote(asset, decimal(book, "ask"), decimal(book, "bid"), asOf == null ? now : asOf, "bitso:/v3/ticker/");
    }

    private static Instant instantOrNull(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
