package io.lifeengine.cryptobot.infrastructure.runtime;

import io.lifeengine.cryptobot.domain.RuntimeUnreachableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Thin HTTP client around {@code POST /api/runtime/runs}. Propagates the caller's JWT verbatim
 * (Phase 1 trust model — see service-boundaries.md §3). Fails fast with
 * {@link RuntimeUnreachableException} so the controller can return a clean 502.
 */
@Component
public class RuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(RuntimeClient.class);

    private final WebClient webClient;
    private final RuntimeClientProperties properties;

    public RuntimeClient(WebClient.Builder builder, RuntimeClientProperties properties) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.baseUrlNormalized()).build();
    }

    public Mono<RuntimeStartRunResponse> startRun(RuntimeStartRunPayload payload, String bearerToken) {
        return webClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(RuntimeStartRunResponse.class)
                .doOnNext(
                        r ->
                                log.info(
                                        "runtime_run_started runId={} workflowId={} correlationId={}",
                                        r.runId(),
                                        r.workflowId(),
                                        r.correlationId()))
                .onErrorMap(
                        WebClientResponseException.class,
                        ex ->
                                new RuntimeUnreachableException(
                                        "Runtime returned HTTP "
                                                + ex.getStatusCode().value()
                                                + ": "
                                                + ex.getResponseBodyAsString(),
                                        ex))
                .onErrorMap(
                        java.util.function.Predicate.not(RuntimeUnreachableException.class::isInstance),
                        ex -> new RuntimeUnreachableException("Runtime call failed: " + ex.getMessage(), ex));
    }

    public String runtimeBaseUrl() {
        return properties.baseUrlNormalized();
    }

    public String runtimeWorkflowId() {
        return properties.workflowIdNormalized();
    }
}
