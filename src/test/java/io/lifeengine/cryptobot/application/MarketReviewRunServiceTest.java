package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketReviewRunStatus;
import io.lifeengine.cryptobot.domain.MarketReviewVerdict;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class MarketReviewRunServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-20T13:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordStart_persistsRunningRow() {
        MarketReviewRunRepository repo = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(repo.insert(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));

        MarketReviewRunService service =
                new MarketReviewRunService(repo, stubRuntime(), json, clock);

        UUID runtimeRunId = UUID.randomUUID();
        MarketReviewRun row =
                service.recordStart(
                                "BTCUSDT",
                                runtimeRunId,
                                "crypto.market-review.v1",
                                "operator@local",
                                Map.of("correlationId", "abc"))
                        .block();

        Assertions.assertThat(row).isNotNull();
        Assertions.assertThat(row.symbol()).isEqualTo("BTCUSDT");
        Assertions.assertThat(row.runtimeRunId()).isEqualTo(runtimeRunId);
        Assertions.assertThat(row.status()).isEqualTo(MarketReviewRunStatus.RUNNING);
        Assertions.assertThat(row.startedAt()).isEqualTo(Instant.parse("2026-05-20T13:00:00Z"));
        Assertions.assertThat(row.metadata()).containsEntry("correlationId", "abc");

        ArgumentCaptor<MarketReviewRun> captor = ArgumentCaptor.forClass(MarketReviewRun.class);
        Mockito.verify(repo).insert(captor.capture());
        Assertions.assertThat(captor.getValue().status()).isEqualTo(MarketReviewRunStatus.RUNNING);
    }

    @Test
    void reconcile_failedRun_setsFailedStatusAndCapturesTerminalError() {
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
                        Map.of(),
                        startedAt,
                        startedAt);

        MarketReviewRunRepository repo = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(repo.findById(localId)).thenReturn(Mono.just(stored));
        Mockito.when(repo.update(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));

        RuntimeClient runtimeClient =
                new RuntimeClient(
                        WebClient.builder(),
                        new RuntimeClientProperties("http://localhost:0", "crypto.market-review.v1")) {
                    @Override
                    public Mono<RuntimeRunDetail> getRun(UUID runId, String bearerToken) {
                        return Mono.just(
                                new RuntimeRunDetail(
                                        runId,
                                        "crypto.market-review.v1",
                                        "corr-x",
                                        "FAILED",
                                        startedAt,
                                        startedAt.plusSeconds(12),
                                        "llm timeout",
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new RuntimeRunDetail.RuntimeEventSlice(
                                                        "RUN_FAILED", Map.of("error", "llm timeout")))));
                    }
                };

        MarketReviewRunService service = new MarketReviewRunService(repo, runtimeClient, json, clock);
        MarketReviewRun reconciled = service.reconcile(localId, "tok-1").block();

        Assertions.assertThat(reconciled).isNotNull();
        Assertions.assertThat(reconciled.status()).isEqualTo(MarketReviewRunStatus.FAILED);
        Assertions.assertThat(reconciled.verdict()).isEqualTo(MarketReviewVerdict.UNKNOWN);
        Assertions.assertThat(reconciled.metadata()).containsEntry("terminalError", "llm timeout");
        Assertions.assertThat(reconciled.metadata()).containsEntry("llmCallCount", 0);
        Assertions.assertThat(reconciled.metadata()).containsEntry("toolCallCount", 0);
    }

    @Test
    void reconcile_alreadyTerminal_isNoOp() {
        UUID localId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-05-20T13:00:00Z");
        MarketReviewRun terminal =
                new MarketReviewRun(
                        localId,
                        "SOLUSDT",
                        UUID.randomUUID(),
                        "crypto.market-review.v1",
                        MarketReviewRunStatus.SUCCEEDED,
                        null,
                        startedAt,
                        startedAt.plusSeconds(5),
                        MarketReviewVerdict.NEUTRAL,
                        "ok",
                        null,
                        null,
                        Map.of(),
                        startedAt,
                        startedAt);
        MarketReviewRunRepository repo = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(repo.findById(localId)).thenReturn(Mono.just(terminal));

        MarketReviewRunService service =
                new MarketReviewRunService(repo, stubRuntime(), json, clock);
        MarketReviewRun result = service.reconcile(localId, "tok-1").block();

        Assertions.assertThat(result).isSameAs(terminal);
        Mockito.verify(repo, Mockito.never()).update(ArgumentMatchers.any());
    }

    @Test
    void clampLimit_appliesBounds() {
        Assertions.assertThat(MarketReviewRunService.clampLimit(null)).isEqualTo(20);
        Assertions.assertThat(MarketReviewRunService.clampLimit(0)).isEqualTo(20);
        Assertions.assertThat(MarketReviewRunService.clampLimit(7)).isEqualTo(7);
        Assertions.assertThat(MarketReviewRunService.clampLimit(10_000)).isEqualTo(200);
    }

    private RuntimeClient stubRuntime() {
        return new RuntimeClient(
                WebClient.builder(),
                new RuntimeClientProperties("http://localhost:0", "crypto.market-review.v1")) {
            @Override
            public Mono<RuntimeRunDetail> getRun(UUID runId, String bearerToken) {
                return Mono.empty();
            }
        };
    }
}
