package io.lifeengine.cryptobot.core.oracle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One price, from one independent source, for one asset, observed at one instant (paper
 * §22). {@code asset} is the symbol the policy speaks ({@code SOL}), {@code mint} the mainnet mint
 * the source was asked about. {@code observedAt} is the source's own publish time when it has one
 * (Pyth) and the fetch time otherwise (Jupiter, CoinGecko) — the consensus treats both as the
 * moment the fact was true, so a source without timestamps can never look fresher than its fetch.
 *
 * <p>Nothing is normalised here: a {@code null} or non-positive price is kept and rejected by
 * {@link PriceOracle} with a reason, so the record of what the oracle saw is complete.
 */
public record PriceObservation(String source, String asset, String mint, BigDecimal priceUsd, Instant observedAt) {

    public PriceObservation {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source: missing");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset: missing");
        }
        source = source.trim();
        asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Canonical value tree ({@code oracle-observation/1}); absent fields are omitted, never null. */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("asset", asset);
        if (mint != null) {
            m.put("mint", mint);
        }
        if (priceUsd != null) {
            m.put("priceUsd", priceUsd.stripTrailingZeros().toPlainString());
        }
        if (observedAt != null) {
            m.put("observedAt", observedAt.toString());
        }
        return m;
    }
}
