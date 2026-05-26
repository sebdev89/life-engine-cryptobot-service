package io.lifeengine.cryptobot.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.domain.InvalidSymbolException;
import io.lifeengine.cryptobot.domain.MarketSnapshot;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunResponse;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
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
        RuntimeClient runtimeClient = new RuntimeClient(org.springframework.web.reactive.function.client.WebClient.builder(),
                new io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties("http://localhost:0", "crypto.market-review.v1")) {
            @Override
            public Mono<RuntimeStartRunResponse> startRun(
                    io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunPayload payload, String bearerToken) {
                Assertions.assertThat(payload.workflowId()).isEqualTo("crypto.market-review.v1");
                Assertions.assertThat(bearerToken).isEqualTo("tok-123");
                Assertions.assertThat(payload.input()).contains("\"symbol\":\"BTCUSDT\"");
                Assertions.assertThat(payload.input()).contains("\"marketReviewId\"");
                return Mono.just(new RuntimeStartRunResponse(expectedRunId, "crypto.market-review.v1", payload.correlationId(), "RUNNING"));
            }
        };
        MarketReviewService service = new MarketReviewService(snapshotProvider, runtimeClient, json);

        MarketReviewResponse response =
                service.execute(new MarketReviewRequest("btcusdt", null), "tok-123").block();

        Assertions.assertThat(response).isNotNull();
        Assertions.assertThat(response.symbol()).isEqualTo("BTCUSDT");
        Assertions.assertThat(response.related().runtimeRunId()).isEqualTo(expectedRunId);
        Assertions.assertThat(response.related().runtimeWorkflowId()).isEqualTo("crypto.market-review.v1");
        Assertions.assertThat(response.related().ssePath()).contains(expectedRunId.toString());
        // Drift of +2.5 maps to BUY/MEDIUM in MarketReviewService.computeSignal.
        Assertions.assertThat(response.signal().signal()).isEqualTo("BUY");
        Assertions.assertThat(response.signal().strength()).isEqualTo("MEDIUM");
    }
}
