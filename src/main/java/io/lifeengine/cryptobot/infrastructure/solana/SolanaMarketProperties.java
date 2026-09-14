package io.lifeengine.cryptobot.infrastructure.solana;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Solana market-data sources for the market-review pipeline (the Binance twin). Keyless.
 *
 * <p>On Solana a market is a <b>pool</b> with an address, not a symbol. {@code pools} maps the
 * symbols the rest of the service already speaks ({@code SOLUSDC}) to the DEX pool that prices
 * them; the adapter absorbs the difference and nothing downstream changes.
 */
@ConfigurationProperties(prefix = "cryptobot.solana.market")
public record SolanaMarketProperties(
        String geckoTerminalBaseUrl,
        String jupiterBaseUrl,
        Duration timeout,
        Map<String, String> pools,
        Map<String, String> mints) {

    /** Raydium SOL/USDC — the deepest SOL spot pool; verified 2026-09-14. */
    public static final String DEFAULT_SOL_USDC_POOL = "58oQChx4yWmvKdwLLZzBi4ChoCc2fqCUWBkwMihLYQo2";

    public SolanaMarketProperties {
        geckoTerminalBaseUrl = blank(geckoTerminalBaseUrl) ? "https://api.geckoterminal.com" : geckoTerminalBaseUrl.trim();
        jupiterBaseUrl = blank(jupiterBaseUrl) ? "https://lite-api.jup.ag" : jupiterBaseUrl.trim();
        timeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        Map<String, String> p = new java.util.LinkedHashMap<>();
        p.put("SOLUSDC", DEFAULT_SOL_USDC_POOL);
        if (pools != null) {
            pools.forEach((k, v) -> p.put(normalizeSymbol(k), v.trim()));
        }
        pools = Map.copyOf(p);
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SOL", "So11111111111111111111111111111111111111112");
        m.put("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
        m.put("USDT", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB");
        m.put("JUP", "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN");
        if (mints != null) {
            mints.forEach((k, v) -> m.put(k.trim().toUpperCase(Locale.ROOT), v.trim()));
        }
        mints = Map.copyOf(m);
    }

    /** {@code sol/usdc}, {@code SOL-USDC}, {@code SOLUSDC} → {@code SOLUSDC}. */
    public static String normalizeSymbol(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace("/", "").replace("-", "").replace("_", "");
    }

    public Optional<String> poolFor(String symbol) {
        return Optional.ofNullable(pools.get(normalizeSymbol(symbol)));
    }

    /** Base asset of a pair symbol: {@code SOLUSDC} → {@code SOL} (quote is one of the known stables/SOL). */
    public Optional<String> baseMintFor(String symbol) {
        String s = normalizeSymbol(symbol);
        for (String quote : new String[] {"USDC", "USDT", "SOL"}) {
            if (s.endsWith(quote) && s.length() > quote.length()) {
                String base = s.substring(0, s.length() - quote.length());
                if (mints.containsKey(base)) {
                    return Optional.of(mints.get(base));
                }
            }
        }
        return Optional.ofNullable(mints.get(s));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
