package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Drives the read-only monitoring loop: triggers a {@code crypto.market-review.v1} run for each
 * configured symbol. Two entry points:
 *
 * <ol>
 *   <li>{@link #runOnce(String, String)} — fired by {@code POST /api/cryptobot/monitoring/run-once}.
 *       Always available regardless of the {@code cryptobot.monitoring.enabled} flag.
 *   <li>A background timer started in {@link #start()} when (and only when) {@link MonitoringProperties#enabledFlag()}
 *       is {@code true}. Uses {@link MonitoringProperties#interval()} as the period.
 * </ol>
 *
 * <p>The scheduled path has no user behind it. Since an internal ticket it runs with the service's own S2S
 * credential (Auth client-credentials, {@code aud=runtime}) when {@code cryptobot.s2s.*} is
 * configured; otherwise the scheduler logs that it has nothing to authenticate with and skips the
 * tick. Manual triggers via the controller keep using the caller's principal token (pass-through).
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final MarketReviewService marketReviewService;
    private final MonitoringProperties properties;
    private final RuntimeClient runtimeClient;

    private volatile Disposable scheduledLoop;

    public MonitoringService(
            MarketReviewService marketReviewService,
            MonitoringProperties properties,
            RuntimeClient runtimeClient) {
        this.marketReviewService = marketReviewService;
        this.properties = properties;
        this.runtimeClient = runtimeClient;
    }

    @PostConstruct
    void start() {
        if (!properties.enabledFlag()) {
            log.info(
                    "monitoring_loop_disabled symbols={} interval={} — use POST /api/cryptobot/monitoring/run-once for manual triggers",
                    properties.symbolList(),
                    properties.interval());
            return;
        }
        Duration interval = properties.interval();
        log.info(
                "monitoring_loop_starting symbols={} interval={}",
                properties.symbolList(),
                interval);
        scheduledLoop =
                Flux.interval(interval, interval)
                        .onBackpressureDrop()
                        .flatMap(
                                tick ->
                                        runOnce(null, "scheduler")
                                                .onErrorResume(
                                                        err -> {
                                                            log.warn(
                                                                    "monitoring_tick_failed error={}", err.toString());
                                                            return Flux.empty();
                                                        }))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();
    }

    @PreDestroy
    void stop() {
        if (scheduledLoop != null && !scheduledLoop.isDisposed()) {
            scheduledLoop.dispose();
        }
    }

    /**
     * Triggers a market-review run for each configured symbol concurrently. The flux completes when
     * all per-symbol responses are returned. Errors per symbol are swallowed (logged) so one bad
     * symbol does not poison the others.
     *
     * @param bearerToken optional caller token. When {@code null} the loop is skipped (see class
     *     javadoc).
     * @param requestedBy free-form identifier persisted into {@code market_review_run.requested_by}.
     */
    public Flux<MarketReviewResponse> runOnce(String bearerToken, String requestedBy) {
        List<String> symbols = properties.symbolList();
        if (symbols.isEmpty()) {
            log.warn("monitoring_run_skipped reason=no_symbols_configured");
            return Flux.empty();
        }
        if ((bearerToken == null || bearerToken.isBlank()) && !runtimeClient.serviceIdentityAvailable()) {
            // Sin usuario y sin credencial propia: no hay con qué autenticarse. RuntimeClient
            // resuelve el token S2S cuando el bearer viene vacío y la credencial existe.
            log.warn(
                    "monitoring_run_skipped reason=no_bearer_token_and_no_s2s_credential requestedBy={} symbols={}",
                    requestedBy,
                    symbols);
            return Flux.empty();
        }
        log.info("monitoring_run_once requestedBy={} symbols={}", requestedBy, symbols);
        String correlationPrefix = "monitor-" + UUID.randomUUID();
        return Flux.fromIterable(symbols)
                .flatMap(
                        symbol ->
                                marketReviewService
                                        .execute(
                                                new MarketReviewRequest(
                                                        symbol, correlationPrefix + "-" + symbol),
                                                bearerToken,
                                                requestedBy)
                                        .doOnError(
                                                err ->
                                                        log.warn(
                                                                "monitoring_symbol_failed symbol={} error={}",
                                                                symbol,
                                                                err.toString()))
                                        .onErrorResume(err -> reactor.core.publisher.Mono.empty()));
    }

    public List<String> configuredSymbols() {
        return properties.symbolList();
    }
}
