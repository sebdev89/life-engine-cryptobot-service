package io.lifeengine.cryptobot.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.domain.RuntimeUnreachableException;
import io.lifeengine.cryptobot.security.FakeAuthServer;
import io.lifeengine.cryptobot.security.ServiceTokenClient;
import io.lifeengine.cryptobot.security.ServiceTokenClientProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * KAN-69 — {@link RuntimeClient} SIEMPRE manda {@code Authorization: Bearer}, y con la credencial
 * correcta según quién llama: el JWT del usuario cuando hay uno (pass-through, intencional), el
 * token S2S de Auth cuando no lo hay (loop de monitoreo) o cuando {@code auth-mode=service}. Si
 * no hay ninguna credencial, la llamada falla antes de salir: Runtime no recibe nada.
 */
class RuntimeClientAuthTest {

    private static final UUID RUN_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private FakeAuthServer auth;
    private MockWebServer runtime;

    @BeforeEach
    void start() throws Exception {
        auth = new FakeAuthServer().start();
        runtime = new MockWebServer();
        runtime.start();
    }

    @AfterEach
    void stop() throws Exception {
        auth.shutdown();
        runtime.shutdown();
    }

    private RuntimeClient client(String authMode, boolean s2sConfigured) {
        ServiceTokenClientProperties s2s =
                s2sConfigured
                        ? new ServiceTokenClientProperties(auth.baseUrl(), "cryptobot", "test-secret", 30, 5)
                        : new ServiceTokenClientProperties("", "", "", 30, 5);
        return new RuntimeClient(
                WebClient.builder(),
                new RuntimeClientProperties(runtime.url("/").toString(), "crypto.market-review.v1", authMode),
                new ServiceTokenClient(s2s, WebClient.builder()));
    }

    private static RuntimeStartRunPayload payload() {
        return new RuntimeStartRunPayload(
                "crypto.market-review.v1", "{\"symbol\":\"BTCUSDT\"}", "corr-1", Map.of());
    }

    private void enqueueStarted() {
        runtime.enqueue(
                json(
                        "{\"runId\":\"" + RUN_ID + "\",\"workflowId\":\"crypto.market-review.v1\","
                                + "\"correlationId\":\"corr-1\",\"status\":\"RUNNING\"}"));
    }

    private void enqueueDetail() {
        runtime.enqueue(
                json(
                        "{\"runId\":\"" + RUN_ID + "\",\"workflowId\":\"crypto.market-review.v1\","
                                + "\"correlationId\":\"corr-1\",\"status\":\"SUCCEEDED\"}"));
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private String runtimeAuthorization() throws Exception {
        RecordedRequest request = runtime.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).as("Runtime recibió la request").isNotNull();
        return request.getHeader("Authorization");
    }

    @Test
    @DisplayName("passthrough con usuario: viaja el JWT del usuario verbatim y Auth no se consulta")
    void passthroughForwardsTheCallerJwt() throws Exception {
        RuntimeClient client = client("passthrough", true);
        enqueueStarted();
        enqueueDetail();

        StepVerifier.create(client.startRun(payload(), "user-jwt")).expectNextCount(1).verifyComplete();
        StepVerifier.create(client.getRun(RUN_ID, "user-jwt")).expectNextCount(1).verifyComplete();

        assertThat(runtimeAuthorization()).isEqualTo("Bearer user-jwt");
        assertThat(runtimeAuthorization()).isEqualTo("Bearer user-jwt");
        assertThat(auth.tokenRequestCount()).isZero();
    }

    @Test
    @DisplayName("sin usuario (loop de monitoreo): viaja el token S2S de Auth con aud=runtime")
    void headlessCallerUsesTheServiceToken() throws Exception {
        RuntimeClient client = client("passthrough", true);
        enqueueStarted();
        enqueueDetail();

        StepVerifier.create(client.startRun(payload(), null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(client.getRun(RUN_ID, "")).expectNextCount(1).verifyComplete();

        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-1");
        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-1");
        assertThat(auth.tokenRequestCount()).as("un token, cacheado para la segunda llamada").isEqualTo(1);
        assertThat(auth.tokenRequests().get(0).audience()).isEqualTo(ServiceTokenClient.AUDIENCE_RUNTIME);
        assertThat(auth.tokenRequests().get(0).body()).contains("\"clientId\":\"cryptobot\"");
    }

    @Test
    @DisplayName("auth-mode=service: TODAS las llamadas con el token S2S, aunque haya usuario")
    void serviceModeAlwaysUsesTheServiceToken() throws Exception {
        RuntimeClient client = client("service", true);
        enqueueStarted();

        StepVerifier.create(client.startRun(payload(), "user-jwt")).expectNextCount(1).verifyComplete();

        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-1");
        assertThat(auth.tokenRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sin usuario y sin credencial S2S: falla ANTES de salir, Runtime no recibe nada")
    void noCredentialAtAllFailsBeforeCalling() {
        RuntimeClient client = client("passthrough", false);
        enqueueStarted();

        StepVerifier.create(client.startRun(payload(), null))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(ex)
                                        .isInstanceOf(RuntimeUnreachableException.class)
                                        .hasMessageContaining("CRYPTOBOT_S2S_CLIENT_ID/SECRET"))
                .verify();

        assertThat(runtime.getRequestCount()).isZero();
        assertThat(auth.tokenRequestCount()).isZero();
    }

    @Test
    @DisplayName("si Auth rechaza la credencial, NO se llama a Runtime sin Authorization")
    void authRejectionNeverFallsBackToUnauthenticated() {
        auth.failWith(401);
        RuntimeClient client = client("service", true);
        enqueueStarted();

        StepVerifier.create(client.startRun(payload(), "user-jwt"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(ex)
                                        .isInstanceOf(RuntimeUnreachableException.class)
                                        .hasMessageContaining("status 401")
                                        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("test-secret")))
                .verify();

        assertThat(runtime.getRequestCount()).as("ninguna request a Runtime").isZero();
    }

    @Test
    @DisplayName("401 de Runtime con token S2S invalida el cacheado: la siguiente llamada pide otro")
    void runtimeUnauthorizedInvalidatesCachedServiceToken() throws Exception {
        RuntimeClient client = client("service", true);
        enqueueStarted();
        runtime.enqueue(new MockResponse().setResponseCode(401));
        enqueueStarted();

        StepVerifier.create(client.startRun(payload(), null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(client.startRun(payload(), null)).expectError(RuntimeUnreachableException.class).verify();
        StepVerifier.create(client.startRun(payload(), null)).expectNextCount(1).verifyComplete();

        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-1");
        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-1");
        assertThat(runtimeAuthorization()).isEqualTo("Bearer s2s-runtime-2");
        assertThat(auth.tokenRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("auth-mode inválido falla con mensaje claro")
    void invalidAuthModeIsRejected() {
        assertThatThrownBy(() -> new RuntimeClientProperties("http://x", "w", "user").authModeNormalized())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRYPTOBOT_RUNTIME_AUTH_MODE");
    }
}
