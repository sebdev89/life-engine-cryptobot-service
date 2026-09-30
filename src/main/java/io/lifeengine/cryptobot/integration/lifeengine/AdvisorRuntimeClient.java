package io.lifeengine.cryptobot.integration.lifeengine;

import io.lifeengine.cryptobot.observability.LogContext;
import io.lifeengine.cryptobot.observability.LogFields;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunPayload;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeStartRunResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Starts a Runtime run and waits for it to reach a terminal state, polling
 * {@code GET /api/runtime/runs/{id}}. Wraps the existing {@link RuntimeClient} — the Runtime API
 * is consumed as-is; nothing new is asked of the platform.
 */
@Component
public class AdvisorRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(AdvisorRuntimeClient.class);
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    public record Completed(UUID runId, RuntimeRunDetail detail) {}

    private final RuntimeClient runtimeClient;
    private final AdvisorProperties props;

    public AdvisorRuntimeClient(RuntimeClient runtimeClient, AdvisorProperties props) {
        this.runtimeClient = runtimeClient;
        this.props = props;
    }

    public String workflowId() {
        return props.workflowId();
    }

    public String runtimeBaseUrl() {
        return runtimeClient.runtimeBaseUrl();
    }

    public Mono<RuntimeStartRunResponse> start(String inputJson, String correlationId, String bearer) {
        RuntimeStartRunPayload payload = new RuntimeStartRunPayload(props.workflowId(), inputJson, correlationId, Map.of("source", "cryptobot-advisor"));
        return runtimeClient.startRun(payload, bearer);
    }

    public Mono<Completed> awaitTerminal(UUID runId, String bearer) {
        return runtimeClient
                .getRun(runId, bearer)
                .flatMap(
                        detail -> {
                            if (detail.status() != null && TERMINAL.contains(detail.status())) {
                                return Mono.just(new Completed(runId, detail));
                            }
                            return Mono.error(new NotYetTerminal());
                        })
                .retryWhen(
                        reactor.util.retry.Retry.fixedDelay(maxAttempts(), props.pollInterval())
                                .filter(NotYetTerminal.class::isInstance)
                                .onRetryExhaustedThrow((spec, signal) -> new java.util.concurrent.TimeoutException(
                                        "Runtime run " + runId + " did not finish within " + props.timeout())))
                .doOnSuccess(c -> log.info("advisor_run_terminal runId={} status={}", runId, c.detail().status(),
                        LogFields.event("advisor_run"), LogFields.status(String.valueOf(c.detail().status()))))
                // la corrida del Runtime en el MDC de toda la espera (LogContext.RUNTIME_RUN_ID).
                .contextWrite(ctx -> LogContext.write(ctx, LogContext.RUNTIME_RUN_ID, runId));
    }

    private long maxAttempts() {
        return Math.max(1, props.timeout().toMillis() / Math.max(1, props.pollInterval().toMillis()));
    }

    private static final class NotYetTerminal extends RuntimeException {
        NotYetTerminal() {
            super("not terminal", null, false, false);
        }
    }

    public static Duration defaultTimeout() {
        return Duration.ofSeconds(90);
    }
}
