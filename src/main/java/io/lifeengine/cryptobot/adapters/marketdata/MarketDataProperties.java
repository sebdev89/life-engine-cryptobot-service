package io.lifeengine.cryptobot.adapters.marketdata;

import io.lifeengine.cryptobot.domain.oracle.OracleLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Price oracle configuration (KAN-439, paper §22). Three independent, keyless, read-only sources
 * — Jupiter (DEX aggregator), Pyth Hermes (pull oracle network) and CoinGecko (CEX aggregator) —
 * and the {@link OracleLimits} their observations must satisfy. {@code fallbackPrices} (keyed by
 * symbol) only keep the <em>portfolio view</em> alive when no consensus exists: they are labelled
 * {@code fallback-static}, never count as a source, and the policy denies on them.
 *
 * <p>{@code pyth.feeds} maps a symbol to its Hermes price-feed id (hex, no {@code 0x});
 * {@code coingecko.ids} maps a symbol to its CoinGecko coin id. A symbol a source does not know
 * is simply not observed by it.
 */
@ConfigurationProperties(prefix = "cryptobot.marketdata")
public record MarketDataProperties(
        String jupiterBaseUrl,
        Duration timeout,
        Map<String, String> aliases,
        Map<String, BigDecimal> fallbackPrices,
        boolean jupiterEnabled,
        Pyth pyth,
        CoinGecko coingecko,
        Oracle oracle) {

    @ConstructorBinding
    public MarketDataProperties {
        jupiterBaseUrl = jupiterBaseUrl == null || jupiterBaseUrl.isBlank() ? "https://lite-api.jup.ag" : jupiterBaseUrl.trim();
        timeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        fallbackPrices = fallbackPrices == null ? Map.of() : Map.copyOf(fallbackPrices);
        pyth = pyth == null ? new Pyth(null, null, null) : pyth;
        coingecko = coingecko == null ? new CoinGecko(null, null, null) : coingecko;
        oracle = oracle == null ? new Oracle(null, null, null, null, null) : oracle;
    }

    /** Jupiter-only shape kept for the tests that predate the multi-source oracle; Pyth/CoinGecko default to enabled with no ids. */
    public MarketDataProperties(String jupiterBaseUrl, Duration timeout, Map<String, String> aliases, Map<String, BigDecimal> fallbackPrices,
            boolean jupiterEnabled) {
        this(jupiterBaseUrl, timeout, aliases, fallbackPrices, jupiterEnabled, null, null, null);
    }

    public record Pyth(Boolean enabled, String baseUrl, Map<String, String> feeds) {
        public Pyth {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://hermes.pyth.network" : baseUrl.trim();
            feeds = feeds == null ? Map.of() : Map.copyOf(feeds);
        }
    }

    public record CoinGecko(Boolean enabled, String baseUrl, Map<String, String> ids) {
        public CoinGecko {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.coingecko.com" : baseUrl.trim();
            ids = ids == null ? Map.of() : Map.copyOf(ids);
        }
    }

    /** Integrity assumptions; converted once into an {@link OracleLimits} whose hash every decision commits to. */
    public record Oracle(Integer minSources, Duration maxAge, Integer maxDeviationBps, Integer maxMoveBps, Duration moveInterval) {
        public Oracle {
            minSources = minSources == null ? 2 : minSources;
            maxAge = maxAge == null ? Duration.ofSeconds(60) : maxAge;
            maxDeviationBps = maxDeviationBps == null ? 100 : maxDeviationBps;
            maxMoveBps = maxMoveBps == null ? 1_000 : maxMoveBps;
            moveInterval = moveInterval == null ? Duration.ofMinutes(5) : moveInterval;
        }

        /** Throws at startup if the limits are not a valid envelope (fail-closed: no limits, no service). */
        public OracleLimits limits() {
            return new OracleLimits(minSources, maxAge.toSeconds(), maxDeviationBps, maxMoveBps, moveInterval.toSeconds());
        }
    }
}
