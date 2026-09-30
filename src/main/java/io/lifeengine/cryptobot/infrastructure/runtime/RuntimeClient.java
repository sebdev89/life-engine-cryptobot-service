package io.lifeengine.cryptobot.infrastructure.runtime;

import io.lifeengine.cryptobot.domain.RuntimeUnreachableException;
import io.lifeengine.cryptobot.security.ServiceTokenClient;
import io.lifeengine.cryptobot.security.ServiceTokenClientProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Thin HTTP client around {@code POST /api/runtime/runs}. Fails fast with
 * {@link RuntimeUnreachableException} so the controller can return a clean 502.
 *
 * <p><b>Credencial.</b> Cada llamada lleva SIEMPRE {@code Authorization: Bearer}:
 *
 * <ul>
 *   <li>Con un {@code bearerToken} del llamador y {@code auth-mode=passthrough} (default), viaja
 *       ese JWT verbatim — modelo de confianza de fase 1 (service-boundaries.md §3): Runtime ve la
 *       identidad y el tenant del usuario real. Es intencional; se mantiene.
 *   <li>Sin {@code bearerToken} (llamador headless: el loop de monitoreo) o con
 *       {@code auth-mode=service}, viaja el token S2S del servicio que emite Auth por
 *       client-credentials ({@code aud=runtime}).
 *   <li>Sin ninguna de las dos cosas la llamada falla ANTES de salir: nunca se manda una request
 *       sin credencial ni con una cadena vacía.
 * </ul>
 */
@Component
public class RuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(RuntimeClient.class);

    private final WebClient webClient;
    private final RuntimeClientProperties properties;
    private final ServiceTokenClient serviceTokenClient;

    @Autowired
    public RuntimeClient(
            WebClient.Builder builder,
            RuntimeClientProperties properties,
            ServiceTokenClient serviceTokenClient) {
        this.properties = properties;
        this.serviceTokenClient = serviceTokenClient;
        this.webClient = builder.baseUrl(properties.baseUrlNormalized()).build();
    }

    /** Sólo pass-through, sin credencial S2S: para tests y llamadores que siempre traen bearer. */
    public RuntimeClient(WebClient.Builder builder, RuntimeClientProperties properties) {
        this(
                builder,
                properties,
                new ServiceTokenClient(new ServiceTokenClientProperties("", "", "", 30, 5), builder));
    }

    public Mono<RuntimeStartRunResponse> startRun(RuntimeStartRunPayload payload, String bearerToken) {
        return bearer(bearerToken)
                .flatMap(
                        token ->
                                webClient
                                        .post()
                                        .uri("/api/runtime/runs")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .bodyValue(payload)
                                        .retrieve()
                                        .bodyToMono(RuntimeStartRunResponse.class)
                                        .doOnError(
                                                WebClientResponseException.Unauthorized.class,
                                                ex -> invalidateIfService(bearerToken)))
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

    /**
     * Fetches the full run-detail snapshot for {@code runId} via
     * {@code GET /api/runtime/runs/{runId}}. Used by reconciliation to pull
     * status/verdict/summary into {@code market_review_run}.
     */
    public Mono<RuntimeRunDetail> getRun(UUID runId, String bearerToken) {
        return bearer(bearerToken)
                .flatMap(
                        token ->
                                webClient
                                        .get()
                                        .uri("/api/runtime/runs/{runId}", runId)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .retrieve()
                                        .bodyToMono(RuntimeRunDetail.class)
                                        .doOnError(
                                                WebClientResponseException.Unauthorized.class,
                                                ex -> invalidateIfService(bearerToken)))
                .onErrorMap(
                        WebClientResponseException.class,
                        ex ->
                                new RuntimeUnreachableException(
                                        "Runtime GET /runs/" + runId + " returned HTTP "
                                                + ex.getStatusCode().value()
                                                + ": "
                                                + ex.getResponseBodyAsString(),
                                        ex))
                .onErrorMap(
                        java.util.function.Predicate.not(RuntimeUnreachableException.class::isInstance),
                        ex ->
                                new RuntimeUnreachableException(
                                        "Runtime GET /runs/" + runId + " failed: " + ex.getMessage(),
                                        ex));
    }

    /** Si este cliente puede llamar a Runtime sin un usuario detrás (credencial S2S completa). */
    public boolean serviceIdentityAvailable() {
        return serviceTokenClient.configured();
    }

    private boolean usesServiceToken(String callerToken) {
        return properties.authModeNormalized() == RuntimeClientProperties.AuthMode.SERVICE
                || callerToken == null
                || callerToken.isBlank();
    }

    private Mono<String> bearer(String callerToken) {
        return Mono.defer(
                () -> {
                    if (!usesServiceToken(callerToken)) {
                        return Mono.just(callerToken);
                    }
                    if (!serviceTokenClient.configured()) {
                        // Ni token del llamador ni credencial propia: se corta acá, con nombre.
                        return Mono.error(
                                new RuntimeUnreachableException(
                                        "Runtime call without credential: no caller bearer and no"
                                                + " S2S credential (CRYPTOBOT_S2S_CLIENT_ID/SECRET,"
                                                + " AUTH_INTERNAL_BASE_URL)",
                                        null));
                    }
                    return serviceTokenClient.token(ServiceTokenClient.AUDIENCE_RUNTIME);
                });
    }

    /** Un 401 de Runtime con token S2S vuelve inservible el cacheado; se descarta antes del próximo. */
    private void invalidateIfService(String callerToken) {
        if (usesServiceToken(callerToken)) {
            serviceTokenClient.invalidate(ServiceTokenClient.AUDIENCE_RUNTIME);
        }
    }

    public String runtimeBaseUrl() {
        return properties.baseUrlNormalized();
    }

    public String runtimeWorkflowId() {
        return properties.workflowIdNormalized();
    }
}
