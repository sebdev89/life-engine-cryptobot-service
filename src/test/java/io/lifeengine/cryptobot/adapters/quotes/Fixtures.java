package io.lifeengine.cryptobot.adapters.quotes;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Recorded response shapes of each exchange's public feed. Tests never hit the network. */
final class Fixtures {

    private Fixtures() {}

    static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/quotes/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** All three adapters pointed at one base URL (a MockWebServer), no configured fees. */
    static QuotesProperties pointingAt(String baseUrl) {
        return pointingAt(baseUrl, Map.of());
    }

    static QuotesProperties pointingAt(String baseUrl, Map<String, Map<String, Map<String, BigDecimal>>> fees) {
        return new QuotesProperties(
                Duration.ofSeconds(45),
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                Map.of(
                        BitsoQuoteSource.ID, new QuotesProperties.Exchange(true, baseUrl),
                        RipioQuoteSource.ID, new QuotesProperties.Exchange(true, baseUrl),
                        BuenbitQuoteSource.ID, new QuotesProperties.Exchange(true, baseUrl)),
                fees);
    }
}
