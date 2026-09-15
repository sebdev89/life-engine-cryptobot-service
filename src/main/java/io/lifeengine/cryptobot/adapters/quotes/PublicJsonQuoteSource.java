package io.lifeengine.cryptobot.adapters.quotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import io.lifeengine.cryptobot.domain.quotes.NetworkFee;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Shared plumbing for keyless JSON price feeds: one GET, one parse, configured fees merged in.
 * Subclasses only know their exchange's URL path and JSON shape.
 */
abstract class PublicJsonQuoteSource implements ExchangeQuoteSource {

    private final String id;
    private final WebClient webClient;
    private final QuotesProperties properties;
    private final QuotesProperties.Exchange config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    protected PublicJsonQuoteSource(
            String id, WebClient.Builder builder, QuotesProperties properties, ObjectMapper objectMapper, Clock clock, String defaultBaseUrl) {
        this.id = id;
        this.properties = properties;
        this.config = properties.exchange(id, defaultBaseUrl);
        this.webClient = builder.baseUrl(config.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Path (and query) of the public "all tickers" endpoint, relative to the base URL. */
    protected abstract String path();

    /** Turn the raw document into normalised quotes. Unknown or non-ARS pairs are skipped. */
    protected abstract List<ArsQuote> parse(JsonNode root, Instant now);

    @Override
    public final String exchange() {
        return id;
    }

    @Override
    public boolean enabled() {
        return config.isEnabled();
    }

    @Override
    public Mono<List<ArsQuote>> fetchArsQuotes() {
        return webClient
                .get()
                .uri(path())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parseDocument(raw, clock.instant()))
                .map(quotes -> quotes.stream().map(this::mergeConfiguredFees).toList());
    }

    /** Package-visible so fixture tests can exercise the parser without a server. */
    List<ArsQuote> parseDocument(String raw, Instant now) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(exchange() + ": unparseable response", ex);
        }
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException(exchange() + ": empty response");
        }
        return parse(root, now);
    }

    private ArsQuote mergeConfiguredFees(ArsQuote quote) {
        if (!quote.withdrawalFees().isEmpty()) {
            return quote; // exchange-published wins; configuration only fills the gap
        }
        List<NetworkFee> configured = properties.configuredFees(exchange(), quote.asset());
        return configured.isEmpty() ? quote : quote.withFees(configured);
    }

    // ---- small parsing helpers shared by the adapters ----

    protected static Optional<BigDecimal> decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return Optional.empty();
        }
        JsonNode v = node.get(field);
        try {
            BigDecimal d = v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
            return d.signum() > 0 ? Optional.of(d) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    protected static Optional<String> text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return Optional.empty();
        }
        String s = node.get(field).asText();
        return s == null || s.isBlank() ? Optional.empty() : Optional.of(s.trim());
    }

    /** Builds a quote only when both sides are positive numbers; otherwise empty (illiquid book). */
    protected Optional<ArsQuote> quote(String asset, Optional<BigDecimal> ask, Optional<BigDecimal> bid, Instant asOf, String source) {
        if (ask.isEmpty() || bid.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ArsQuote(exchange(), asset, ask.get(), bid.get(), List.of(), asOf, source));
    }
}
