package io.lifeengine.cryptobot.adapters.marketdata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Static knowledge about mints: symbol, decimals, whether it is a stablecoin, and — for devnet —
 * which mainnet mint it stands for (so a devnet USDC balance is valued at mainnet USDC's price).
 *
 * <p>Deliberately small and in-code: the demo needs a dozen well-known assets, and anything
 * unknown is still shown (as {@code UNKNOWN-<mint prefix>}) with no price and a risk finding.
 */
@Component
public class TokenRegistry {

    public static final String NATIVE_SOL_MINT = "So11111111111111111111111111111111111111112";
    public static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    public record TokenInfo(String mint, String symbol, String name, int decimals, boolean stable, String priceMint) {}

    private final Map<String, TokenInfo> byMint = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public TokenRegistry(MarketDataProperties properties) {
        register(NATIVE_SOL_MINT, "SOL", "Solana", 9, false);
        register(USDC_MINT, "USDC", "USD Coin", 6, true);
        register("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT", "Tether USD", 6, true);
        register("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "Jupiter", 6, false);
        register("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", "Bonk", 5, false);
        register("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", "mSOL", "Marinade staked SOL", 9, false);
        register("J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", "JitoSOL", "Jito staked SOL", 9, false);
        register("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", "RAY", "Raydium", 6, false);
        register("EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", "WIF", "dogwifhat", 6, false);
        register("HZ1JovNiVvGrGNiiYvEozEVjZ58xaU3RKwX8eACQBCt3", "PYTH", "Pyth Network", 6, false);
        register("jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL", "JTO", "Jito", 9, false);
        // Circle's devnet USDC faucet mint — valued as mainnet USDC.
        alias("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", USDC_MINT);
        if (properties.aliases() != null) {
            properties.aliases().forEach(this::alias);
        }
    }

    private void register(String mint, String symbol, String name, int decimals, boolean stable) {
        byMint.put(mint, new TokenInfo(mint, symbol, name, decimals, stable, mint));
    }

    /** Declares that {@code mint} (typically devnet) is priced as {@code priceMint} (mainnet). */
    public void alias(String mint, String priceMint) {
        aliases.put(mint, priceMint);
    }

    public Optional<TokenInfo> lookup(String mint) {
        TokenInfo direct = byMint.get(mint);
        if (direct != null) {
            return Optional.of(direct);
        }
        String target = aliases.get(mint);
        if (target != null && byMint.containsKey(target)) {
            TokenInfo t = byMint.get(target);
            return Optional.of(new TokenInfo(mint, t.symbol(), t.name() + " (devnet)", t.decimals(), t.stable(), target));
        }
        return Optional.empty();
    }

    /** Symbol for display; unknown mints get a stable, recognisable placeholder. */
    public String symbolOf(String mint) {
        return lookup(mint).map(TokenInfo::symbol).orElse("UNKNOWN-" + mint.substring(0, Math.min(4, mint.length())));
    }

    public boolean isStable(String mint) {
        return lookup(mint).map(TokenInfo::stable).orElse(false);
    }

    /** Which mainnet mint to ask the price oracle about for {@code mint}; empty if unknown. */
    public Optional<String> priceMintOf(String mint) {
        return lookup(mint).map(TokenInfo::priceMint);
    }

    /** Resolves a user-facing symbol (e.g. {@code SOL}, {@code usdc}) to its mainnet mint. */
    public Optional<String> mintOfSymbol(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        String wanted = symbol.trim();
        return byMint.values().stream()
                .filter(t -> t.symbol().equalsIgnoreCase(wanted))
                .map(TokenInfo::mint)
                .findFirst();
    }
}
