package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.application.MarketSnapshotProvider;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import java.time.Instant;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read-only endpoint that the runtime tools ({@code getCryptoPrice}, {@code getCryptoTicker24h})
 * call to source live market data. Backed by the active {@link MarketSnapshotProvider} (defaults
 * to deterministic-local; switch via {@code cryptobot.snapshot.provider=binance-public}).
 */
@RestController
@RequestMapping("/api/cryptobot/snapshots")
public class SnapshotsController {

    private final MarketSnapshotProvider provider;

    public SnapshotsController(MarketSnapshotProvider provider) {
        this.provider = provider;
    }

    @GetMapping(path = "/{symbol}", produces = "application/json")
    public Mono<MarketSnapshotResponse> snapshot(@PathVariable("symbol") String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return provider.snapshot(normalized).map(MarketSnapshotResponse::from);
    }

    public record MarketSnapshotResponse(
            String symbol,
            String source,
            double price,
            double priceChangePct24h,
            double volumeBase24h,
            Instant observedAt) {
        public static MarketSnapshotResponse from(MarketSnapshot s) {
            return new MarketSnapshotResponse(
                    s.symbol(), s.source(), s.price(), s.priceChangePct24h(), s.volumeBase24h(), s.observedAt());
        }
    }
}
