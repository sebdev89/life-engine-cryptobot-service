package io.lifeengine.cryptobot.health;

import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.observability.BuildIdentityJsonProvider;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** KAN-1359: the public health endpoint says which build answers — what the UI shows as the service's version. */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class CryptobotHealthVersionTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    void healthCarriesTheBuildIdentityWithoutAToken() {
        BuildIdentityJsonProvider.BuildIdentity id = BuildIdentityJsonProvider.current();
        webTestClient.get().uri("/api/cryptobot/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.version").isEqualTo(id.version())
                .jsonPath("$.commit").isEqualTo(id.commit());
    }
}
