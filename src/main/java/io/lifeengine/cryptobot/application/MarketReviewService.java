package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketSignalResponse;
import io.lifeengine.cryptobot.api.MarketReviewDtos.RelatedRuntimeRunResponse;
import io.lifeengine.cryptobot.domain.InvalidSymbolException;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketSignal;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunPayload;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunResponse;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Orchestrates a single market review:
 *
 * <ol>
 *   <li>Validate + normalize the symbol.
 *   <li>Compute the inline snapshot via the active {@link MarketSnapshotProvider}.
 *   <li>Compute a deterministic signal (in-process; no LLM, no exchange).
 *   <li>Build the runtime input JSON (matches {@code cryptobot.market-review-input.v1}).
 *   <li>POST {@code /api/runtime/runs} on life-engine-runtime, propagating the caller's JWT.
 *   <li>Return the synchronous review + the linked runtime run id.
 * </ol>
 */
@Service
public class MarketReviewService {

    private static final Logger log = LoggerFactory.getLogger(MarketReviewService.class);
    private static final String INPUT_CONTRACT_ID = "cryptobot.market-review-input.v1";

    private final MarketSnapshotProvider snapshotProvider;
    private final RuntimeClient runtimeClient;
    private final MarketReviewRunService marketReviewRunService;
    private final ObjectMapper objectMapper;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    /** Test-friendly: no metrics exported. */
    public MarketReviewService(
            MarketSnapshotProvider snapshotProvider,
            RuntimeClient runtimeClient,
            MarketReviewRunService marketReviewRunService,
            ObjectMapper objectMapper) {
        this(snapshotProvider, runtimeClient, marketReviewRunService, objectMapper, CryptobotMetrics.noop());
    }

    @Autowired
    public MarketReviewService(
            MarketSnapshotProvider snapshotProvider,
            RuntimeClient runtimeClient,
            MarketReviewRunService marketReviewRunService,
            ObjectMapper objectMapper,
            CryptobotMetrics metrics) {
        this.snapshotProvider = snapshotProvider;
        this.runtimeClient = runtimeClient;
        this.marketReviewRunService = marketReviewRunService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = Clock.systemUTC();
    }

    public Mono<MarketReviewResponse> execute(MarketReviewRequest request, String bearerToken) {
        return execute(request, bearerToken, null);
    }

    /**
     * {@code requestedBy} is the authenticated principal's email (or user id, fallback) — captured
     * into the {@code market_review_run.requested_by} column for traceability.
     */
    public Mono<MarketReviewResponse> execute(
            MarketReviewRequest request, String bearerToken, String requestedBy) {
        String symbol = normalize(request.symbol());
        String marketReviewId = UUID.randomUUID().toString();
        String correlationId =
                request.correlationId() == null || request.correlationId().isBlank()
                        ? "cryptobot-mr-" + marketReviewId
                        : request.correlationId();

        return snapshotProvider
                .snapshot(symbol)
                .flatMap(
                        snapshot ->
                                triggerRuntime(snapshot, marketReviewId, correlationId, bearerToken)
                                        .flatMap(
                                                runtimeResponse ->
                                                        persistAndReconcile(
                                                                        snapshot,
                                                                        runtimeResponse,
                                                                        marketReviewId,
                                                                        correlationId,
                                                                        bearerToken,
                                                                        requestedBy)
                                                                .map(
                                                                        savedRunId ->
                                                                                assemble(
                                                                                        snapshot,
                                                                                        marketReviewId,
                                                                                        correlationId,
                                                                                        runtimeResponse,
                                                                                        savedRunId))));
    }

    private Mono<UUID> persistAndReconcile(
            MarketSnapshot snapshot,
            RuntimeStartRunResponse runtimeResponse,
            String marketReviewId,
            String correlationId,
            String bearerToken,
            String requestedBy) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "cryptobot-market-review");
        metadata.put("marketReviewId", marketReviewId);
        metadata.put("correlationId", correlationId);
        return marketReviewRunService
                .recordStart(
                        snapshot.symbol(),
                        runtimeResponse.runId(),
                        runtimeClient.runtimeWorkflowId(),
                        requestedBy,
                        metadata)
                .doOnSuccess(
                        saved -> {
                            if (saved == null || saved.id() == null) {
                                return;
                            }
                            // Fire-and-forget reconciliation loop. Subscribed off the request thread so
                            // it survives the controller returning. Errors are swallowed inside the
                            // service.
                            marketReviewRunService
                                    .reconcileWithRetry(saved.id(), bearerToken)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe(
                                            ignored -> {
                                                /* terminal — already logged inside service */
                                            },
                                            err ->
                                                    log.warn(
                                                            "background_reconcile_failed marketReviewRunId={} runtimeRunId={} error={}",
                                                            saved.id(),
                                                            saved.runtimeRunId(),
                                                            err.toString()));
                        })
                // recordStart() guarantees a non-empty Mono (it falls back to the
                // in-memory row if the insert returns empty or errors), so .map() is
                // safe here without further empty-guards.
                .map(MarketReviewRun::id);
    }

    private Mono<RuntimeStartRunResponse> triggerRuntime(
            MarketSnapshot snapshot, String marketReviewId, String correlationId, String bearerToken) {
        Map<String, Object> inputObject = new LinkedHashMap<>();
        inputObject.put("contractId", INPUT_CONTRACT_ID);
        inputObject.put("symbol", snapshot.symbol());
        inputObject.put("snapshotSource", snapshot.source());
        inputObject.put("observedAt", snapshot.observedAt().toString());
        inputObject.put("marketReviewId", marketReviewId);

        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(inputObject);
        } catch (JsonProcessingException ex) {
            return Mono.error(ex);
        }

        log.info(
                "runtime_start_run_request marketReviewId={} symbol={} correlationId={} workflowId={}",
                marketReviewId,
                snapshot.symbol(),
                correlationId,
                runtimeClient.runtimeWorkflowId());

        RuntimeStartRunPayload payload =
                new RuntimeStartRunPayload(
                        runtimeClient.runtimeWorkflowId(), inputJson, correlationId, Map.of("source", "cryptobot-service"));
        return runtimeClient
                .startRun(payload, bearerToken)
                // market_analysis_total{result="started"}: the Runtime accepted the run. The terminal
                // outcome is counted by MarketReviewRunService when it reconciles. "start_failed" is
                // the Runtime refusing or being unreachable — the analysis never existed.
                .doOnSuccess(r -> metrics.marketAnalysis("started", snapshot.symbol()))
                .doOnError(ex -> metrics.marketAnalysis("start_failed", snapshot.symbol()));
    }

    private MarketReviewResponse assemble(
            MarketSnapshot snapshot,
            String marketReviewId,
            String correlationId,
            RuntimeStartRunResponse runtimeResponse,
            UUID marketReviewRunId) {
        MarketSignal signal = computeSignal(snapshot);

        RelatedRuntimeRunResponse related =
                new RelatedRuntimeRunResponse(
                        runtimeResponse.runId(),
                        runtimeClient.runtimeWorkflowId(),
                        correlationId,
                        runtimeClient.runtimeBaseUrl(),
                        "/api/runtime/runs/" + runtimeResponse.runId() + "/stream");

        return new MarketReviewResponse(
                marketReviewId,
                snapshot.symbol(),
                snapshot,
                new MarketSignalResponse(
                        signal.signal(), signal.strength(), signal.reason(), signal.indicators()),
                related,
                Instant.now(clock),
                marketReviewRunId);
    }

    /**
     * Same decision tree as the runtime's {@code SignalEngineTool} so the synchronous response and
     * the SSE-driven workflow agree. Pure function; no I/O.
     */
    static MarketSignal computeSignal(MarketSnapshot snapshot) {
        double drift = snapshot.priceChangePct24h();
        double rsi14 = Math.max(5.0, Math.min(95.0, 50.0 + drift * 5.0));
        String dir;
        String strength;
        String reason;
        if (drift >= 1.5) {
            dir = "BUY";
            strength = drift >= 3.0 ? "STRONG" : "MEDIUM";
            reason = "Positive 24h drift with rsi14=" + round(rsi14, 1);
        } else if (drift <= -1.5) {
            dir = "SELL";
            strength = drift <= -3.0 ? "STRONG" : "MEDIUM";
            reason = "Negative 24h drift with rsi14=" + round(rsi14, 1);
        } else {
            dir = "HOLD";
            strength = "WEAK";
            reason = "Range-bound 24h movement (|drift|<1.5%)";
        }
        Map<String, Double> indicators = new LinkedHashMap<>();
        indicators.put("rsi14", round(rsi14, 2));
        indicators.put("ema9", round(snapshot.price() * 0.99, 4));
        indicators.put("ema21", round(snapshot.price() * 0.97, 4));
        return new MarketSignal(dir, strength, reason, indicators);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidSymbolException("symbol is required");
        }
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z0-9]{4,16}")) {
            throw new InvalidSymbolException(
                    "symbol must be 4-16 alphanumeric chars (got '" + symbol.trim() + "')");
        }
        return upper;
    }
}
