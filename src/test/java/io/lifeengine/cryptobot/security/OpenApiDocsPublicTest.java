package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-597: with the API map turned ON for an environment (CRYPTOBOT_OPENAPI_ENABLED=true, as in overlays/uat), the
 * OpenAPI docs are public (SecurityConfig: GET permitAll), with security ENABLED as in every
 * environment (the {@code test} profile sets {@code cryptobot.security.enabled=true}). Before this, the JWT filter
 * that runs ahead of Spring Security did not skip them, so they answered 401 without a token in k8s-uat, and the
 * new routes could not be checked as deployed without a credential. Only GET opens; the API itself still requires
 * a token.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class OpenApiDocsPublicTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    @DisplayName("GET /v3/api-docs without a token → 200 and lists the intents/executions routes")
    void apiDocsArePublicAndListTheNewRoutes() throws Exception {
        byte[] body = webTestClient.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().returnResult().getResponseBody();

        JsonNode paths = new ObjectMapper().readTree(body).path("paths");
        assertThat(paths.has("/api/cryptobot/intents")).isTrue();
        assertThat(paths.has("/api/cryptobot/executions/{id}")).isTrue();
    }

    @Test
    @DisplayName("the Swagger UI entry point is public too")
    void swaggerUiIsPublic() {
        webTestClient.get().uri("/swagger-ui.html").exchange()
                .expectStatus().value(code -> assertThat(code).isBetween(200, 399));
    }

    @Test
    @DisplayName("nothing else opens: a non-GET on the docs and the API itself still answer 401 without a token")
    void onlyGetOnTheDocsOpens() {
        webTestClient.post().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/cryptobot/intents").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/cryptobot/executions/00000000-0000-0000-0000-000000000000").exchange()
                .expectStatus().isUnauthorized();
    }
}
