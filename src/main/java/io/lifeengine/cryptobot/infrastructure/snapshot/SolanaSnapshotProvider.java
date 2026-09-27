package io.lifeengine.cryptobot.infrastructure.snapshot;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.application.MarketSnapshotProvider;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.solana.SolanaMarketProperties;
import io.lifeengine.cryptobot.infrastructure.solana.SolanaPublicClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Solana-backed {@link MarketSnapshotProvider}. Activated with
 * {@code cryptobot.snapshot.provider=solana-public}. Same contract as the Binance provider, so
 * the market-review pipeline ({@code crypto.market-review.v1}) runs on SOL/USDC unchanged.
 *
 * <p>Resolution order for a symbol: configured pool (GeckoTerminal: price, 24h change, 24h
 * volume) → a raw pool address passed as the symbol → Jupiter spot price of the base mint (no
 * volume) → deterministic fallback. The snapshot's {@code source} says which one answered.
 */
@Component
@ConditionalOnProperty(name = "cryptobot.snapshot.provider", havingValue = "solana-public")
public class SolanaSnapshotProvider implements MarketSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(SolanaSnapshotProvider.class);
    public static final String ID = "solana-public";
    public static final String ID_JUPITER_ONLY = "solana-public:jupiter";

    private final SolanaPublicClient client;
    private final SolanaMarketProperties properties;
    private final DeterministicLocalSnapshotProvider fallback;
    private final Clock clock = Clock.systemUTC();

    public SolanaSnapshotProvider(SolanaPublicClient client, SolanaMarketProperties properties) {
        this.client = client;
        this.properties = properties;
        this.fallback = new DeterministicLocalSnapshotProvider();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Mono<MarketSnapshot> snapshot(String symbol) {
        String normalized = SolanaMarketProperties.normalizeSymbol(symbol);
        Optional<String> pool = properties.poolFor(normalized)
                .or(() -> Base58.isPublicKey(symbol == null ? "" : symbol.trim()) ? Optional.of(symbol.trim()) : Optional.empty());
        Mono<MarketSnapshot> fromPool = pool
                .map(p -> client.poolStats(p).map(s -> new MarketSnapshot(
                        normalized, ID, s.priceUsd().doubleValue(), s.change24hPct().doubleValue(), s.volume24hUsd().doubleValue(), Instant.now(clock))))
                .orElse(Mono.empty());
        Mono<MarketSnapshot> fromJupiter = properties.baseMintFor(normalized)
                .map(mint -> client.jupiterPrice(mint).map(t -> new MarketSnapshot(
                        normalized, ID_JUPITER_ONLY, t.priceUsd().doubleValue(), t.change24hPct().doubleValue(), 0d, Instant.now(clock))))
                .orElse(Mono.empty());
        return fromPool
                .switchIfEmpty(fromJupiter)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("solana_snapshot_fallback symbol={} pool={}", normalized, pool.orElse("none"));
                    return fallback.snapshot(normalized);
                }));
    }
}
