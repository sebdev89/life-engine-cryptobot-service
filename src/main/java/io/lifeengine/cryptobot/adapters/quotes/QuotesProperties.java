package io.lifeengine.cryptobot.adapters.quotes;

import io.lifeengine.cryptobot.domain.quotes.NetworkFee;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cryptobot.quotes.*} — ARS quotes across exchanges.
 *
 * <ul>
 *   <li>{@code cache-ttl}: how long one exchange's board is served from memory (30–60 s by design).
 *   <li>{@code stale-max}: if a refresh fails, a cached board younger than this is still served,
 *       flagged as stale; older than this the exchange is reported unavailable.
 *   <li>{@code exchanges.<id>}: enable flag and base URL per adapter (tests point them at a mock).
 *   <li>{@code withdrawal-fees.<exchange>.<asset>.<network>}: hand-maintained withdrawal fees in units
 *       of the asset, for exchanges that do not publish them on a public endpoint. They are labelled
 *       {@link NetworkFee.FeeSource#CONFIGURED} all the way to the API — never passed off as live.
 * </ul>
 */
@ConfigurationProperties(prefix = "cryptobot.quotes")
public record QuotesProperties(
        Duration cacheTtl,
        Duration staleMax,
        Duration timeout,
        Map<String, Exchange> exchanges,
        Map<String, Map<String, Map<String, BigDecimal>>> withdrawalFees) {

    public record Exchange(Boolean enabled, String baseUrl) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    public QuotesProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(45) : cacheTtl;
        staleMax = staleMax == null ? Duration.ofMinutes(5) : staleMax;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        exchanges = exchanges == null ? Map.of() : Map.copyOf(exchanges);
        withdrawalFees = withdrawalFees == null ? Map.of() : Map.copyOf(withdrawalFees);
    }

    public Exchange exchange(String id, String defaultBaseUrl) {
        Exchange e = exchanges.get(id);
        if (e == null) {
            return new Exchange(true, defaultBaseUrl);
        }
        return new Exchange(e.isEnabled(), e.baseUrl() == null || e.baseUrl().isBlank() ? defaultBaseUrl : e.baseUrl().trim());
    }

    /** Configured withdrawal fees of {@code asset} on {@code exchange}; empty when none are set. */
    public List<NetworkFee> configuredFees(String exchange, String asset) {
        Map<String, Map<String, BigDecimal>> byAsset = withdrawalFees.get(exchange);
        if (byAsset == null) {
            return List.of();
        }
        Map<String, BigDecimal> byNetwork = byAsset.get(asset);
        if (byNetwork == null) {
            byNetwork = byAsset.get(asset.toLowerCase());
        }
        if (byNetwork == null) {
            return List.of();
        }
        List<NetworkFee> out = new ArrayList<>();
        byNetwork.forEach((network, amount) -> out.add(new NetworkFee(network, amount, NetworkFee.FeeSource.CONFIGURED)));
        return List.copyOf(out);
    }
}
