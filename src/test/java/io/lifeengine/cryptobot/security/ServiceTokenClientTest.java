package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * el cliente de client-credentials de CryptoBot contra un Auth falso.
 *
 * <p>Cubre el contrato ({@code clientId, clientSecret, audience} → {@code access_token,
 * expires_in}), la caché POR audiencia, la renovación antes del vencimiento, la invalidación tras
 * un 401 del servicio destino y —lo que importa— que ante cualquier fallo el error se propaga:
 * nunca se devuelve un token inventado ni una cadena vacía que termine en una request sin
 * {@code Authorization}.
 */
class ServiceTokenClientTest {

    private static final String SECRET = "test-only-secret-never-logged";

    private FakeAuthServer auth;

    @BeforeEach
    void start() throws Exception {
        auth = new FakeAuthServer().start();
    }

    @AfterEach
    void stop() throws Exception {
        auth.shutdown();
    }

    private ServiceTokenClient client(int refreshMarginSeconds) {
        return new ServiceTokenClient(
                new ServiceTokenClientProperties(
                        auth.baseUrl(), "cryptobot", SECRET, refreshMarginSeconds, 5),
                WebClient.builder());
    }

    @Test
    @DisplayName("pide el token con el contrato de Auth: clientId, clientSecret y audience exacta")
    void requestsTokenWithTheAuthContract() throws Exception {
        ServiceTokenClient client = client(30);

        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();

        FakeAuthServer.TokenRequest recorded = auth.tokenRequests().get(0);
        RecordedRequest request = recorded.request();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(FakeAuthServer.SERVICE_TOKEN_PATH);
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        String body = recorded.body();
        assertThat(body).contains("\"clientId\":\"cryptobot\"");
        assertThat(body).contains("\"clientSecret\":\"" + SECRET + "\"");
        assertThat(body).contains("\"audience\":\"runtime\"");
    }

    @Test
    @DisplayName("caché por audiencia: una credencial por audiencia, nunca compartida")
    void cachesPerAudience() {
        ServiceTokenClient client = client(30);

        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();
        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();
        StepVerifier.create(client.token("other-audience"))
                .expectNext("s2s-other-audience-2")
                .verifyComplete();
        StepVerifier.create(client.token("other-audience"))
                .expectNext("s2s-other-audience-2")
                .verifyComplete();
        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();

        assertThat(auth.tokenRequestCount())
                .as("un pedido por audiencia; los repetidos salen de la caché")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("renueva antes del vencimiento: el margen se descuenta del expires_in que informa Auth")
    void refreshesBeforeExpiry() throws Exception {
        // expires_in=31 con margen 30 → usable 1 s. Al segundo pedido, pasado ese segundo, se pide
        // un token nuevo aunque el anterior todavía sea técnicamente válido 30 s más.
        auth.expiresIn(31);
        ServiceTokenClient client = client(30);

        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();
        Thread.sleep(Duration.ofMillis(1_200).toMillis());
        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-2").verifyComplete();

        assertThat(auth.tokenRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("401 de Auth → ServiceTokenUnavailableException con el status, sin el secreto, sin fallback")
    void authRejectionIsAnExplicitError() {
        auth.failWith(401);
        ServiceTokenClient client = client(30);

        StepVerifier.create(client.token("runtime"))
                .expectErrorSatisfies(
                        ex -> {
                            assertThat(ex)
                                    .isInstanceOf(ServiceTokenClient.ServiceTokenUnavailableException.class)
                                    .hasMessageContaining("status 401")
                                    .hasMessageContaining("audience=runtime");
                            assertThat(ex.getMessage()).doesNotContain(SECRET);
                        })
                .verify();

        // Nada quedó cacheado: el próximo pedido vuelve a Auth (y falla de nuevo, explícito).
        StepVerifier.create(client.token("runtime"))
                .expectError(ServiceTokenClient.ServiceTokenUnavailableException.class)
                .verify();
        assertThat(auth.tokenRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("sin configuración no se llama a Auth: falla antes, nombrando la variable, no el valor")
    void unconfiguredFailsBeforeCallingAuth() {
        ServiceTokenClient sinSecreto =
                new ServiceTokenClient(
                        new ServiceTokenClientProperties(auth.baseUrl(), "cryptobot", "", 30, 5),
                        WebClient.builder());
        StepVerifier.create(sinSecreto.token("runtime"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(ex)
                                        .isInstanceOf(
                                                ServiceTokenClient.ServiceTokenUnavailableException.class)
                                        .hasMessageContaining("CRYPTOBOT_S2S_CLIENT_SECRET"))
                .verify();

        ServiceTokenClient sinClientId =
                new ServiceTokenClient(
                        new ServiceTokenClientProperties(auth.baseUrl(), "", SECRET, 30, 5),
                        WebClient.builder());
        StepVerifier.create(sinClientId.token("runtime"))
                .expectErrorMatches(ex -> ex.getMessage().contains("CRYPTOBOT_S2S_CLIENT_ID"))
                .verify();

        ServiceTokenClient sinAuth =
                new ServiceTokenClient(
                        new ServiceTokenClientProperties("", "cryptobot", SECRET, 30, 5),
                        WebClient.builder());
        StepVerifier.create(sinAuth.token("runtime"))
                .expectErrorMatches(ex -> ex.getMessage().contains("AUTH_INTERNAL_BASE_URL"))
                .verify();

        assertThat(auth.tokenRequestCount()).isZero();
    }

    @Test
    @DisplayName("invalidate() descarta el token cacheado: el próximo pedido vuelve a Auth")
    void invalidateForcesANewToken() {
        ServiceTokenClient client = client(30);

        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-1").verifyComplete();
        client.invalidate("runtime");
        StepVerifier.create(client.token("runtime")).expectNext("s2s-runtime-2").verifyComplete();

        assertThat(auth.tokenRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Auth inaccesible → error explícito, nunca un token vacío")
    void unreachableAuthIsAnExplicitError() throws Exception {
        String baseUrl = auth.baseUrl();
        auth.shutdown();
        ServiceTokenClient client =
                new ServiceTokenClient(
                        new ServiceTokenClientProperties(baseUrl, "cryptobot", SECRET, 30, 1),
                        WebClient.builder());

        StepVerifier.create(client.token("runtime"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(ex)
                                        .isInstanceOf(
                                                ServiceTokenClient.ServiceTokenUnavailableException.class)
                                        .hasMessageContaining("No se pudo obtener un token S2S"))
                .verify();
        // El @AfterEach vuelve a apagar el server; ya está apagado y MockWebServer lo tolera.
        auth = new FakeAuthServer().start();
    }
}
