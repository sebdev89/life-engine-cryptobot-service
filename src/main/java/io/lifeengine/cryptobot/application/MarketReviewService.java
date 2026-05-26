package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketSignalResponse;
import io.lifeengine.cryptobot.api.MarketReviewDtos.RelatedRuntimeRunResponse;
import io.lifeengine.cryptobot.domain.InvalidSymbolException;
import io.lifeengine.cryptobot.domain.MarketSignal;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunPayload;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketReviewService(
            MarketSnapshotProvider snapshotProvider, RuntimeClient runtimeClient, ObjectMapper objectMapper) {
        this.snapshotProvider = snapshotProvider;
        this.runtimeClient = runtimeClient;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public Mono<MarketReviewResponse> execute(MarketReviewRequest request, String bearerToken) {
        String symbol = normalize(request.symbol());
        String marketReviewId = UUID.randomUUID().toString();
        String correlationId =
                request.correlationId() == null || request.correlationId().isBlank()
                        ? "cryptobot-mr-" + marketReviewId
                        : request.correlationId();

        return snapshotProvider
                .snapshot(symbol)
                .flatMap(snapshot -> triggerRuntime(snapshot, marketReviewId, correlationId, bearerToken)
                        .map(runtimeResponse -> assemble(snapshot, marketReviewId, correlationId, runtimeResponse)));
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
        return runtimeClient.startRun(payload, bearerToken);
    }

    private MarketReviewResponse assemble(
            MarketSnapshot snapshot, String marketReviewId, String correlationId, RuntimeStartRunResponse runtimeResponse) {
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
                Instant.now(clock));
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
