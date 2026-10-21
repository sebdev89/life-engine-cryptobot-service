package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-597: by default (no CRYPTOBOT_OPENAPI_ENABLED) the API map is NOT public. The same image runs in every
 * environment, PROD included, so publishing it is an explicit per-environment switch. With the default, security
 * enabled and no token, the docs answer 401 (the JWT filter does not skip them) and are never served.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class OpenApiDocsOffByDefaultTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    @DisplayName("default: GET /v3/api-docs and the Swagger UI without a token are not served (401 or 404, never 200)")
    void apiMapIsNotPublicByDefault() {
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html"}) {
            webTestClient.get().uri(path).exchange()
                    .expectStatus().value(code -> assertThat(code).as(path).isIn(401, 404));
        }
    }
}
