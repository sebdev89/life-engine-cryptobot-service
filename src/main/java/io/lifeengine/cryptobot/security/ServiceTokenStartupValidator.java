package io.lifeengine.cryptobot.security;

import io.lifeengine.cryptobot.application.MonitoringProperties;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * en un ambiente real, CryptoBot no arranca si el camino S2S está activo y falta la
 * credencial.
 *
 * <p>"Camino S2S activo" es cualquiera de: {@code cryptobot.runtime.auth-mode=service} (todas las
 * llamadas a Runtime con identidad de servicio) o {@code cryptobot.monitoring.enabled=true} (el
 * loop programado no tiene usuario y sólo puede autenticarse con la credencial propia; sin ella
 * saltearía cada tick en silencio — que es lo que pasaba antes de un cambio posterior).
 *
 * <p>Con {@code auth-mode=passthrough} y el loop apagado no se exige nada: las llamadas viajan con
 * el JWT del usuario, como hasta ahora. Cambiar ese default es una decisión de rollout, no de
 * código: primero el allowlist en Auth y en Runtime, después la credencial, después el flag.
 *
 * <p>"Ambiente real" = perfil {@code prod}/{@code uat}, o {@code APP_ENV} distinto de
 * {@code local}/{@code test} (CryptoBot no declara perfiles en UAT: se identifica por APP_ENV).
 * El mensaje nombra la variable que falta y nunca su valor.
 */
@Component
public class ServiceTokenStartupValidator {

    private static final Set<String> LOCAL_ENVS = Set.of("", "local", "test", "dev");

    private final Environment environment;
    private final ServiceTokenClientProperties s2s;
    private final RuntimeClientProperties runtime;
    private final MonitoringProperties monitoring;

    public ServiceTokenStartupValidator(
            Environment environment,
            ServiceTokenClientProperties s2s,
            RuntimeClientProperties runtime,
            MonitoringProperties monitoring) {
        this.environment = environment;
        this.s2s = s2s;
        this.runtime = runtime;
        this.monitoring = monitoring;
    }

    @PostConstruct
    void validate() {
        // Un valor inválido de auth-mode falla acá, al arrancar, no en la primera llamada.
        RuntimeClientProperties.AuthMode mode = runtime.authModeNormalized();
        if (!isProductionLike()) {
            return;
        }
        boolean s2sRequired =
                mode == RuntimeClientProperties.AuthMode.SERVICE || monitoring.enabledFlag();
        if (!s2sRequired) {
            return;
        }
        String reason =
                mode == RuntimeClientProperties.AuthMode.SERVICE
                        ? "CRYPTOBOT_RUNTIME_AUTH_MODE=service"
                        : "CRYPTOBOT_MONITORING_ENABLED=true";
        require(!s2s.authBaseUrlNormalized().isBlank(), "AUTH_INTERNAL_BASE_URL", reason);
        require(!s2s.clientIdNormalized().isBlank(), "CRYPTOBOT_S2S_CLIENT_ID", reason);
        require(!s2s.clientSecretNormalized().isBlank(), "CRYPTOBOT_S2S_CLIENT_SECRET", reason);
    }

    private static void require(boolean ok, String envVar, String reason) {
        if (!ok) {
            throw new IllegalStateException(
                    envVar
                            + " es obligatoria con "
                            + reason
                            + " en un ambiente real: sin ella CryptoBot no puede obtener tokens S2S"
                            + " de Auth y no arranca (KAN-69). Ver README, sección Configuration.");
        }
    }

    private boolean isProductionLike() {
        if (environment.acceptsProfiles(Profiles.of("prod", "uat"))) {
            return true;
        }
        String appEnv =
                environment.getProperty("lifeengine.deployment.env", "").trim().toLowerCase(Locale.ROOT);
        return !LOCAL_ENVS.contains(appEnv);
    }
}
