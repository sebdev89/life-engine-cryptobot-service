package io.lifeengine.cryptobot.adapters.marketdata;

import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Price oracle configuration (paper §22). Four independent, keyless, read-only sources
 * — Jupiter (DEX aggregator), Pyth Hermes (pull oracle network; since 2026-09 its public endpoint
 * answers 401 without a key, so it counts only where a key-less path exists), CoinGecko (CEX
 * aggregator) and Coinbase spot (one exchange) — and the {@link OracleLimits} their
 * observations must satisfy. {@code fallbackPrices} (keyed by
 * symbol) only keep the <em>portfolio view</em> alive when no consensus exists: they are labelled
 * {@code fallback-static}, never count as a source, and the policy denies on them.
 *
 * <p>{@code pyth.feeds} maps a symbol to its Hermes price-feed id (hex, no {@code 0x});
 * {@code coingecko.ids} maps a symbol to its CoinGecko coin id; {@code coinbase.products} a symbol
 * to its Coinbase product ({@code SOL-USD}). A symbol a source does not know is simply not
 * observed by it.
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
        Coinbase coinbase,
        Oracle oracle) {

    @ConstructorBinding
    public MarketDataProperties {
        jupiterBaseUrl = jupiterBaseUrl == null || jupiterBaseUrl.isBlank() ? "https://lite-api.jup.ag" : jupiterBaseUrl.trim();
        timeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        fallbackPrices = fallbackPrices == null ? Map.of() : Map.copyOf(fallbackPrices);
        pyth = pyth == null ? new Pyth(null, null, null) : pyth;
        coingecko = coingecko == null ? new CoinGecko(null, null, null) : coingecko;
        coinbase = coinbase == null ? new Coinbase(null, null, null) : coinbase;
        oracle = oracle == null ? new Oracle(null, null, null, null, null) : oracle;
    }

    /** Jupiter-only shape kept for the tests that predate the multi-source oracle; Pyth/CoinGecko/Coinbase default to enabled with no ids. */
    public MarketDataProperties(String jupiterBaseUrl, Duration timeout, Map<String, String> aliases, Map<String, BigDecimal> fallbackPrices,
            boolean jupiterEnabled) {
        this(jupiterBaseUrl, timeout, aliases, fallbackPrices, jupiterEnabled, null, null, null, null);
    }

    /** Legacy shape (no Coinbase). */
    public MarketDataProperties(String jupiterBaseUrl, Duration timeout, Map<String, String> aliases, Map<String, BigDecimal> fallbackPrices,
            boolean jupiterEnabled, Pyth pyth, CoinGecko coingecko, Oracle oracle) {
        this(jupiterBaseUrl, timeout, aliases, fallbackPrices, jupiterEnabled, pyth, coingecko, null, oracle);
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

    /** Coinbase spot, keyless; {@code products} maps a symbol to {@code SOL-USD}. */
    public record Coinbase(Boolean enabled, String baseUrl, Map<String, String> products) {
        public Coinbase {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.coinbase.com" : baseUrl.trim();
            products = products == null ? Map.of() : Map.copyOf(products);
        }
    }

    /** Integrity assumptions; converted once into an {@link OracleLimits} whose hash every decision commits to. */
    public record Oracle(Integer minSources, Duration maxAge, Integer maxDeviationBps, Integer maxMoveBps, Duration moveInterval) {
        public Oracle {
            minSources = minSources == null ? 2 : minSources;
            maxAge = maxAge == null ? Duration.ofSeconds(300) : maxAge;
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
