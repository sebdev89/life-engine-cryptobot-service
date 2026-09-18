package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.domain.InvalidSymbolException;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketReviewRunStatus;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunResponse;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class MarketReviewServiceUnitTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalize_uppercasesAndValidates() {
        Assertions.assertThat(MarketReviewService.normalize("  btcusdt ")).isEqualTo("BTCUSDT");
        Assertions.assertThat(MarketReviewService.normalize("ETHUSDT")).isEqualTo("ETHUSDT");
    }

    @Test
    void normalize_rejectsBlank() {
        Assertions.assertThatThrownBy(() -> MarketReviewService.normalize(""))
                .isInstanceOf(InvalidSymbolException.class);
        Assertions.assertThatThrownBy(() -> MarketReviewService.normalize(null))
                .isInstanceOf(InvalidSymbolException.class);
    }

    @Test
    void normalize_rejectsNonAlphanumeric() {
        Assertions.assertThatThrownBy(() -> MarketReviewService.normalize("BTC/USDT"))
                .isInstanceOf(InvalidSymbolException.class);
    }

    @Test
    void execute_returnsRelatedRuntimeRunId() {
        UUID expectedRunId = UUID.randomUUID();
        MarketSnapshotProvider snapshotProvider =
                new MarketSnapshotProvider() {
                    @Override
                    public String id() {
                        return "test-stub";
                    }

                    @Override
                    public Mono<MarketSnapshot> snapshot(String symbol) {
                        return Mono.just(
                                new MarketSnapshot(symbol, "test-stub", 50_000.0, 2.5, 1_000.0, Instant.parse("2026-05-20T13:00:00Z")));
                    }
                };
        RuntimeClient runtimeClient =
                new RuntimeClient(
                        org.springframework.web.reactive.function.client.WebClient.builder(),
                        new io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties(
                                "http://localhost:0", "crypto.market-review.v1", null)) {
                    @Override
                    public Mono<RuntimeStartRunResponse> startRun(
                            io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunPayload payload,
                            String bearerToken) {
                        Assertions.assertThat(payload.workflowId()).isEqualTo("crypto.market-review.v1");
                        Assertions.assertThat(bearerToken).isEqualTo("tok-123");
                        Assertions.assertThat(payload.input()).contains("\"symbol\":\"BTCUSDT\"");
                        Assertions.assertThat(payload.input()).contains("\"marketReviewId\"");
                        return Mono.just(
                                new RuntimeStartRunResponse(
                                        expectedRunId,
                                        "crypto.market-review.v1",
                                        payload.correlationId(),
                                        "RUNNING"));
                    }

                    @Override
                    public Mono<RuntimeRunDetail> getRun(UUID runId, String bearerToken) {
                        // Reconciliation polling is best-effort in this unit test — return a never-terminal
                        // RUNNING detail so the background loop just spins until the test ends.
                        return Mono.just(
                                new RuntimeRunDetail(
                                        runId,
                                        "crypto.market-review.v1",
                                        null,
                                        "RUNNING",
                                        Instant.now(),
                                        null,
                                        null,
                                        java.util.List.of(),
                                        java.util.List.of(),
                                        java.util.List.of()));
                    }
                };

        MarketReviewRunRepository repo = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(repo.insert(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));
        Mockito.when(repo.update(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));
        Mockito.when(repo.findById(ArgumentMatchers.any())).thenReturn(Mono.empty());
        Mockito.when(repo.findLatestBySymbol(ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        Mockito.when(repo.findRecentBySymbol(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(Flux.empty());
        MarketReviewRunService runService = new MarketReviewRunService(repo, runtimeClient, json);

        MarketReviewService service = new MarketReviewService(snapshotProvider, runtimeClient, runService, json);

        MarketReviewResponse response =
                service.execute(new MarketReviewRequest("btcusdt", null), "tok-123").block();

        Assertions.assertThat(response).isNotNull();
        Assertions.assertThat(response.symbol()).isEqualTo("BTCUSDT");
        Assertions.assertThat(response.related().runtimeRunId()).isEqualTo(expectedRunId);
        Assertions.assertThat(response.related().runtimeWorkflowId()).isEqualTo("crypto.market-review.v1");
        Assertions.assertThat(response.related().ssePath()).contains(expectedRunId.toString());
        Assertions.assertThat(response.marketReviewRunId()).isNotNull();
        // Drift of +2.5 maps to BUY/MEDIUM in MarketReviewService.computeSignal.
        Assertions.assertThat(response.signal().signal()).isEqualTo("BUY");
        Assertions.assertThat(response.signal().strength()).isEqualTo("MEDIUM");
    }

    @Test
    void reconcile_setsStatusVerdictSummaryFromAnalystAndFinalSummary() {
        UUID localId = UUID.randomUUID();
        UUID runtimeRunId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-05-20T13:00:00Z");

        MarketReviewRun stored =
                new MarketReviewRun(
                        localId,
                        "BTCUSDT",
                        runtimeRunId,
                        "crypto.market-review.v1",
                        MarketReviewRunStatus.RUNNING,
                        "smoke@test",
                        startedAt,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.Map.of("source", "unit"),
                        startedAt,
                        startedAt);

        MarketReviewRunRepository repo = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(repo.findById(localId)).thenReturn(Mono.just(stored));
        Mockito.when(repo.update(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));

        RuntimeClient runtimeClient =
                new RuntimeClient(
                        org.springframework.web.reactive.function.client.WebClient.builder(),
                        new io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties(
                                "http://localhost:0", "crypto.market-review.v1", null)) {
                    @Override
                    public Mono<RuntimeRunDetail> getRun(UUID runId, String bearerToken) {
                        Assertions.assertThat(runId).isEqualTo(runtimeRunId);
                        return Mono.just(
                                new RuntimeRunDetail(
                                        runId,
                                        "crypto.market-review.v1",
                                        "corr-x",
                                        "SUCCEEDED",
                                        startedAt,
                                        startedAt.plusSeconds(45),
                                        null,
                                        java.util.List.of(
                                                new RuntimeRunDetail.AgentStage(
                                                        "market-analyst", "AGENT", "SUCCEEDED",
                                                        "{\"bias\":\"BULLISH\",\"confidence\":0.7,\"summary\":\"x\"}", null),
                                                new RuntimeRunDetail.AgentStage(
                                                        "risk-review", "AGENT", "SUCCEEDED",
                                                        "{\"approved\":true,\"warnings\":[],\"riskLevel\":\"LOW\"}", null),
                                                new RuntimeRunDetail.AgentStage(
                                                        "final-summary", "AGENT", "SUCCEEDED",
                                                        "{\"response\":\"All quiet. This is market analysis, not financial advice.\"}", null)),
                                        java.util.List.of(
                                                new RuntimeRunDetail.LlmCall("market-analyst", "a", "m"),
                                                new RuntimeRunDetail.LlmCall("risk-review", "b", "m"),
                                                new RuntimeRunDetail.LlmCall("final-summary", "c", "m")),
                                        java.util.List.of(
                                                new RuntimeRunDetail.RuntimeEventSlice(
                                                        "TOOL_STARTED", java.util.Map.of("tool", "getCryptoPrice")),
                                                new RuntimeRunDetail.RuntimeEventSlice(
                                                        "TOOL_STARTED", java.util.Map.of("tool", "getCryptoTicker24h")))));
                    }
                };
        MarketReviewRunService service = new MarketReviewRunService(repo, runtimeClient, json);

        MarketReviewRun reconciled = service.reconcile(localId, "tok-123").block();
        Assertions.assertThat(reconciled).isNotNull();
        Assertions.assertThat(reconciled.status()).isEqualTo(MarketReviewRunStatus.SUCCEEDED);
        Assertions.assertThat(reconciled.verdict().name()).isEqualTo("BULLISH");
        Assertions.assertThat(reconciled.summary()).contains("All quiet");
        Assertions.assertThat(reconciled.metadata()).containsEntry("llmCallCount", 3);
        Assertions.assertThat(reconciled.metadata()).containsEntry("toolCallCount", 2);
        Assertions.assertThat(reconciled.metadata()).containsEntry("riskLevel", "LOW");
        Assertions.assertThat(reconciled.metadata()).containsEntry("approved", true);
    }
}
