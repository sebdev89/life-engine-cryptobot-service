package io.lifeengine.cryptobot.adapters.quotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Ripio (Argentina) — public rates feed the Ripio app itself reads, keyless and read-only.
 *
 * <pre>
 * GET /api/v3/rates/?country=AR
 * [{"ticker":"BTC_ARS","buy_rate":"…","sell_rate":"…","variation":"…","base":"BTC","quote":"ARS"}, …]
 * </pre>
 *
 * {@code buy_rate} is the price the user pays (our ask); {@code sell_rate} what the user gets
 * (our bid). Some deployments wrap the list in {@code {"results":[…]}}; both shapes are accepted.
 * Ripio does not expose withdrawal fees on this feed.
 */
@Component
@Order(20)
public class RipioQuoteSource extends PublicJsonQuoteSource {

    public static final String ID = "ripio";
    static final String DEFAULT_BASE_URL = "https://app.ripio.com";

    @Autowired
    public RipioQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    RipioQuoteSource(WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper, Clock clock) {
        super(ID, builder, properties, objectMapper, clock, DEFAULT_BASE_URL);
    }

    @Override
    protected String path() {
        return "/api/v3/rates/?country=AR";
    }

    @Override
    protected List<ArsQuote> parse(JsonNode root, Instant now) {
        JsonNode list = root.isArray() ? root : root.path("results");
        List<ArsQuote> out = new ArrayList<>();
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode rate : list) {
            parseRate(rate, now).ifPresent(out::add);
        }
        return out;
    }

    private Optional<ArsQuote> parseRate(JsonNode rate, Instant now) {
        String quoteCcy = text(rate, "quote").orElse("");
        String base = text(rate, "base").orElse("");
        if (base.isEmpty() || quoteCcy.isEmpty()) {
            // Fall back to the ticker "BTC_ARS" when base/quote are absent.
            String ticker = text(rate, "ticker").orElse("").toUpperCase(Locale.ROOT);
            int sep = ticker.indexOf('_');
            if (sep <= 0) {
                return Optional.empty();
            }
            base = ticker.substring(0, sep);
            quoteCcy = ticker.substring(sep + 1);
        }
        if (!"ARS".equalsIgnoreCase(quoteCcy)) {
            return Optional.empty();
        }
        return quote(base.toUpperCase(Locale.ROOT), decimal(rate, "buy_rate"), decimal(rate, "sell_rate"), now, "ripio:/api/v3/rates/?country=AR");
    }
}
