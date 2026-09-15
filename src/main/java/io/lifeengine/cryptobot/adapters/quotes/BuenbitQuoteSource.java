package io.lifeengine.cryptobot.adapters.quotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Buenbit — public market tickers, keyless and read-only.
 *
 * <pre>
 * GET /api/market/tickers/
 * {"object":{"btcars":{"currency":"btc","bid_currency":"ars","purchase_price":"…","selling_price":"…",
 *                      "market_identifier":"btcars", …}, "usdtars":{…}, …}}
 * </pre>
 *
 * {@code purchase_price} is what the user pays (ask), {@code selling_price} what the user gets
 * (bid). Markets are keyed {@code <base><quote>} lowercase, e.g. {@code btcars}; the entry's own
 * {@code currency}/{@code bid_currency} fields are preferred when present. No fee feed.
 */
@Component
@Order(30)
public class BuenbitQuoteSource extends PublicJsonQuoteSource {

    public static final String ID = "buenbit";
    static final String DEFAULT_BASE_URL = "https://be.buenbit.com";

    @Autowired
    public BuenbitQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    BuenbitQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper, Clock clock) {
        super(ID, builder, properties, objectMapper, clock, DEFAULT_BASE_URL);
    }

    @Override
    protected String path() {
        return "/api/market/tickers/";
    }

    @Override
    protected List<ArsQuote> parse(JsonNode root, Instant now) {
        JsonNode markets = root.has("object") ? root.get("object") : root;
        List<ArsQuote> out = new ArrayList<>();
        if (!markets.isObject()) {
            return out;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = markets.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            parseMarket(e.getKey(), e.getValue(), now).ifPresent(out::add);
        }
        return out;
    }

    private Optional<ArsQuote> parseMarket(String key, JsonNode market, Instant now) {
        String base = text(market, "currency").orElse("");
        String quoteCcy = text(market, "bid_currency").orElse("");
        if (base.isEmpty() || quoteCcy.isEmpty()) {
            String id = key.toLowerCase(Locale.ROOT);
            if (!id.endsWith("ars") || id.length() <= 3) {
                return Optional.empty();
            }
            base = id.substring(0, id.length() - 3);
            quoteCcy = "ars";
        }
        if (!"ars".equalsIgnoreCase(quoteCcy)) {
            return Optional.empty();
        }
        return quote(base.toUpperCase(Locale.ROOT), decimal(market, "purchase_price"), decimal(market, "selling_price"), now, "buenbit:/api/market/tickers/");
    }
}
