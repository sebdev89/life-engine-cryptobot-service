package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketReviewRunStatus;
import io.lifeengine.cryptobot.domain.MarketReviewVerdict;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Owns the lifecycle of {@code market_review_run} rows.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li>Persist the local linkage row when a runtime run is kicked off (see
 *       {@link #recordStart}).
 *   <li>Reconcile that row against {@code GET /api/runtime/runs/{runId}} once the run reaches a
 *       terminal state — populating verdict / summary / metadata from the runtime's
 *       analyst + risk-review + final-summary stages (see {@link #reconcile}).
 * </ol>
 */
@Service
public class MarketReviewRunService {

    private static final Logger log = LoggerFactory.getLogger(MarketReviewRunService.class);

    private static final String STAGE_ANALYST = "market-analyst";
    private static final String STAGE_RISK_REVIEW = "risk-review";
    private static final String STAGE_FINAL_SUMMARY = "final-summary";

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 200;

    private static final Duration RECONCILE_FIRST_DELAY = Duration.ofSeconds(3);
    private static final Duration RECONCILE_BETWEEN = Duration.ofSeconds(3);
    private static final Duration RECONCILE_TIMEOUT = Duration.ofMinutes(3);

    private final MarketReviewRunRepository repository;
    private final RuntimeClient runtimeClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MarketReviewRunService(
            MarketReviewRunRepository repository,
            RuntimeClient runtimeClient,
            ObjectMapper objectMapper) {
        this(repository, runtimeClient, objectMapper, Clock.systemUTC());
    }

    /** Test-friendly constructor; lets unit tests inject a fixed {@link Clock}. */
    MarketReviewRunService(
            MarketReviewRunRepository repository,
            RuntimeClient runtimeClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.runtimeClient = runtimeClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Persists a brand-new local linkage row in {@code RUNNING} status. */
    public Mono<MarketReviewRun> recordStart(
            String symbol,
            UUID runtimeRunId,
            String workflowId,
            String requestedBy,
            Map<String, Object> metadata) {
        Instant now = Instant.now(clock);
        MarketReviewRun row =
                new MarketReviewRun(
                        UUID.randomUUID(),
                        symbol,
                        runtimeRunId,
                        workflowId,
                        MarketReviewRunStatus.RUNNING,
                        truncate(requestedBy, 128),
                        now,
                        null,
                        null,
                        null,
                        null,
                        null,
                        metadata == null ? Map.of() : Map.copyOf(metadata),
                        now,
                        now);
        return repository
                .insert(row)
                // Defensive: stub repos in tests can return Mono.empty() on save.
                .switchIfEmpty(Mono.just(row))
                .doOnNext(
                        saved ->
                                log.info(
                                        "market_review_run_started id={} symbol={} runtimeRunId={} workflowId={}",
                                        saved.id(),
                                        saved.symbol(),
                                        saved.runtimeRunId(),
                                        saved.workflowId()))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "market_review_run_persist_failed runtimeRunId={} symbol={} error={}",
                                    runtimeRunId,
                                    symbol,
                                    ex.toString());
                            return Mono.just(row);
                        });
    }

    /**
     * Calls {@code GET /api/runtime/runs/{runId}} and updates the local row with status, verdict,
     * summary and a compact metadata blob (llmCallCount, toolCallCount, riskLevel, approved,
     * fallback). Safe to call repeatedly — terminal rows are left alone.
     */
    public Mono<MarketReviewRun> reconcile(UUID marketReviewRunId, String bearerToken) {
        return repository
                .findById(marketReviewRunId)
                .switchIfEmpty(
                        Mono.error(
                                new IllegalArgumentException(
                                        "Unknown market_review_run id: " + marketReviewRunId)))
                .flatMap(local -> reconcileExisting(local, bearerToken));
    }

    private Mono<MarketReviewRun> reconcileExisting(MarketReviewRun local, String bearerToken) {
        if (local.status().isTerminal()) {
            return Mono.just(local);
        }
        return runtimeClient
                .getRun(local.runtimeRunId(), bearerToken)
                .flatMap(detail -> applyRuntimeDetail(local, detail))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "reconcile_failed marketReviewRunId={} runtimeRunId={} error={}",
                                    local.id(),
                                    local.runtimeRunId(),
                                    ex.toString());
                            return Mono.just(local);
                        });
    }

    private Mono<MarketReviewRun> applyRuntimeDetail(MarketReviewRun local, RuntimeRunDetail detail) {
        MarketReviewRunStatus runtimeStatus = MarketReviewRunStatus.fromRuntime(detail.status());
        if (!runtimeStatus.isTerminal() && runtimeStatus == local.status()) {
            return Mono.just(local);
        }

        MarketReviewVerdict verdict = extractVerdict(detail);
        String summary = extractFinalSummary(detail);
        Map<String, Object> metadataPatch = buildMetadataPatch(detail);
        Instant finishedAt = detail.finishedAt() != null ? detail.finishedAt() : Instant.now(clock);

        MarketReviewRun reconciled =
                local.withReconciledTerminalState(
                        runtimeStatus, finishedAt, verdict, summary, metadataPatch);
        return repository
                .update(reconciled)
                .switchIfEmpty(Mono.just(reconciled))
                .doOnNext(
                        saved ->
                                log.info(
                                        "market_review_run_reconciled id={} status={} verdict={}",
                                        saved.id(),
                                        saved.status(),
                                        saved.verdict()));
    }

    /**
     * Schedules a fire-and-forget reconciliation loop on the bounded-elastic scheduler. Polls until
     * the local row reaches a terminal state or {@link #RECONCILE_TIMEOUT} elapses. Returns
     * immediately — caller is expected to subscribe in a non-blocking context.
     */
    public Mono<MarketReviewRun> reconcileWithRetry(UUID marketReviewRunId, String bearerToken) {
        Instant deadline = Instant.now(clock).plus(RECONCILE_TIMEOUT);
        return Mono.delay(RECONCILE_FIRST_DELAY)
                .then(reconcile(marketReviewRunId, bearerToken))
                .flatMap(
                        first -> {
                            if (first.status().isTerminal()) {
                                return Mono.just(first);
                            }
                            return pollUntilTerminal(marketReviewRunId, bearerToken, deadline);
                        });
    }

    private Mono<MarketReviewRun> pollUntilTerminal(
            UUID id, String bearerToken, Instant deadline) {
        return Mono.delay(RECONCILE_BETWEEN)
                .then(reconcile(id, bearerToken))
                .flatMap(
                        current -> {
                            if (current.status().isTerminal()) {
                                return Mono.just(current);
                            }
                            if (Instant.now(clock).isAfter(deadline)) {
                                log.warn(
                                        "reconcile_timeout marketReviewRunId={} lastStatus={}",
                                        id,
                                        current.status());
                                return Mono.just(current);
                            }
                            return pollUntilTerminal(id, bearerToken, deadline);
                        });
    }

    public Mono<MarketReviewRun> findLatestBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Mono.empty();
        }
        return repository.findLatestBySymbol(symbol);
    }

    public Flux<MarketReviewRun> findHistory(String symbol, Integer requestedLimit) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        int limit = clampLimit(requestedLimit);
        return repository.findRecentBySymbol(symbol, limit);
    }

    static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(requested, MAX_HISTORY_LIMIT);
    }

    // ------------------------------------------------------------ helpers ----

    private MarketReviewVerdict extractVerdict(RuntimeRunDetail detail) {
        String analystOutput = stageOutput(detail, STAGE_ANALYST);
        if (analystOutput == null) {
            return MarketReviewVerdict.UNKNOWN;
        }
        try {
            JsonNode node = objectMapper.readTree(analystOutput);
            return MarketReviewVerdict.fromAnalystBias(node.path("bias").asText(""));
        } catch (Exception e) {
            return MarketReviewVerdict.UNKNOWN;
        }
    }

    private String extractFinalSummary(RuntimeRunDetail detail) {
        String finalOutput = stageOutput(detail, STAGE_FINAL_SUMMARY);
        if (finalOutput == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(finalOutput);
            String response = node.path("response").asText("");
            return response.isBlank() ? null : truncate(response, 4_000);
        } catch (Exception e) {
            return truncate(finalOutput, 4_000);
        }
    }

    private Map<String, Object> buildMetadataPatch(RuntimeRunDetail detail) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("llmCallCount", detail.llmCalls() == null ? 0 : detail.llmCalls().size());
        patch.put("toolCallCount", countToolStarted(detail.events()));

        String riskOutput = stageOutput(detail, STAGE_RISK_REVIEW);
        if (riskOutput != null) {
            try {
                JsonNode node = objectMapper.readTree(riskOutput);
                if (node.has("riskLevel")) {
                    patch.put("riskLevel", node.get("riskLevel").asText());
                }
                if (node.has("approved")) {
                    patch.put("approved", node.get("approved").asBoolean());
                }
            } catch (Exception ignored) {
                // ignore — leave keys absent
            }
        }

        if (anyFallback(detail.events())) {
            patch.put("fallback", true);
        }
        if (detail.terminalError() != null && !detail.terminalError().isBlank()) {
            patch.put("terminalError", truncate(detail.terminalError(), 500));
        }
        patch.put("reconciledAt", Instant.now(clock).toString());
        return patch;
    }

    private static String stageOutput(RuntimeRunDetail detail, String stageId) {
        if (detail.agentStages() == null) {
            return null;
        }
        for (RuntimeRunDetail.AgentStage stage : detail.agentStages()) {
            if (stageId.equals(stage.stageId())) {
                return stage.output();
            }
        }
        return null;
    }

    private static int countToolStarted(List<RuntimeRunDetail.RuntimeEventSlice> events) {
        if (events == null) {
            return 0;
        }
        int n = 0;
        for (Iterator<RuntimeRunDetail.RuntimeEventSlice> it = events.iterator(); it.hasNext(); ) {
            if ("TOOL_STARTED".equals(it.next().type())) {
                n++;
            }
        }
        return n;
    }

    private static boolean anyFallback(List<RuntimeRunDetail.RuntimeEventSlice> events) {
        if (events == null) {
            return false;
        }
        for (RuntimeRunDetail.RuntimeEventSlice event : events) {
            if ("true".equalsIgnoreCase(event.attributes().get("fallback"))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
