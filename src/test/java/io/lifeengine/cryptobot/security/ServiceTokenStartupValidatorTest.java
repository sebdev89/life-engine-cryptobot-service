package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.application.MonitoringProperties;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

/**
 * en un ambiente real, con el camino S2S activo y sin credencial, CryptoBot no arranca —
 * y el mensaje dice QUÉ variable falta sin mostrar ningún valor.
 */
class ServiceTokenStartupValidatorTest {

    private static final String SECRET = "startup-test-secret";

    private static ServiceTokenClientProperties s2s(String url, String id, String secret) {
        return new ServiceTokenClientProperties(url, id, secret, 30, 5);
    }

    private static RuntimeClientProperties runtime(String authMode) {
        return new RuntimeClientProperties("http://runtime:8090", "crypto.market-review.v1", authMode);
    }

    private static MonitoringProperties monitoring(boolean enabled) {
        return new MonitoringProperties(enabled, "BTCUSDT", Duration.ofMinutes(10), null);
    }

    private static MockEnvironment env(String appEnv, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty("lifeengine.deployment.env", appEnv);
        return environment;
    }

    @Test
    @DisplayName("APP_ENV=uat + auth-mode=service sin CRYPTOBOT_S2S_CLIENT_SECRET → no arranca")
    void uatServiceModeWithoutSecretFails() {
        var validator =
                new ServiceTokenStartupValidator(
                        env("uat"), s2s("http://auth:8081", "cryptobot", ""), runtime("service"), monitoring(false));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRYPTOBOT_S2S_CLIENT_SECRET")
                .hasMessageContaining("CRYPTOBOT_RUNTIME_AUTH_MODE=service");
    }

    @Test
    @DisplayName("perfil prod + monitoring habilitado sin CRYPTOBOT_S2S_CLIENT_ID → no arranca (el loop no tiene usuario)")
    void prodMonitoringWithoutClientIdFails() {
        var validator =
                new ServiceTokenStartupValidator(
                        env("local", "prod"), s2s("http://auth:8081", "", SECRET), runtime("passthrough"), monitoring(true));

        assertThatThrownBy(validator::validate)
                .hasMessageContaining("CRYPTOBOT_S2S_CLIENT_ID")
                .hasMessageContaining("CRYPTOBOT_MONITORING_ENABLED=true")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(SECRET));
    }

    @Test
    @DisplayName("k8s-uat + auth-mode=service sin AUTH_INTERNAL_BASE_URL → no arranca")
    void k8sUatWithoutAuthUrlFails() {
        var validator =
                new ServiceTokenStartupValidator(
                        env("k8s-uat"), s2s("", "cryptobot", SECRET), runtime("service"), monitoring(false));

        assertThatThrownBy(validator::validate).hasMessageContaining("AUTH_INTERNAL_BASE_URL");
    }

    @Test
    @DisplayName("passthrough con el loop apagado no exige nada (rollout: allowlist → secreto → flag)")
    void passthroughWithoutMonitoringRequiresNothing() {
        assertThatCode(
                        () ->
                                new ServiceTokenStartupValidator(
                                                env("uat"), s2s("", "", ""), runtime("passthrough"), monitoring(false))
                                        .validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local/test nunca fallan; uat con todo configurado arranca; auth-mode inválido falla siempre")
    void localPassesConfiguredUatPassesInvalidModeFails() {
        assertThatCode(
                        () ->
                                new ServiceTokenStartupValidator(
                                                env("local"), s2s("", "", ""), runtime("service"), monitoring(true))
                                        .validate())
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                new ServiceTokenStartupValidator(
                                                env("test"), s2s("", "", ""), runtime("service"), monitoring(true))
                                        .validate())
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                new ServiceTokenStartupValidator(
                                                env("uat"),
                                                s2s("http://auth:8081", "cryptobot", SECRET),
                                                runtime("service"),
                                                monitoring(true))
                                        .validate())
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                new ServiceTokenStartupValidator(
                                                env("local"), s2s("", "", ""), runtime("bogus"), monitoring(false))
                                        .validate())
                .hasMessageContaining("CRYPTOBOT_RUNTIME_AUTH_MODE");
    }

    @Test
    @DisplayName("contexto completo: perfil prod + auth-mode=service sin secreto S2S no levanta")
    void fullContextRefusesToStartInProdWithoutS2s() {
        SpringApplicationBuilder app =
                new SpringApplicationBuilder(CryptobotServiceApplication.class, StubRepositoriesConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .profiles("test", "prod");

        // Por línea de comando: pisa cualquier YAML. Lo que se prueba es exactamente el arranque
        // en prod con el camino S2S activo y sin la credencial.
        assertThatThrownBy(
                        () ->
                                app.run(
                                        "--cryptobot.runtime.auth-mode=service",
                                        "--cryptobot.s2s.auth-base-url=http://auth:8081",
                                        "--cryptobot.s2s.client-id=cryptobot",
                                        "--cryptobot.s2s.client-secret="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRYPTOBOT_S2S_CLIENT_SECRET");
    }
}
