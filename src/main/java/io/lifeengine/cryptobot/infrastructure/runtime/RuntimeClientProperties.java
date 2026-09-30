package io.lifeengine.cryptobot.infrastructure.runtime;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cryptobot.runtime")
public record RuntimeClientProperties(String baseUrl, String workflowId, String authMode) {

    /**
     * Con qué credencial CryptoBot llama a Runtime.
     *
     * <ul>
     *   <li>{@link #PASSTHROUGH} (default): las llamadas disparadas por un usuario viajan con el
     *       JWT de ese usuario, verbatim — es el modelo de confianza de fase 1
     *       (service-boundaries.md §3): Runtime ve la identidad y el tenant reales del llamador.
     *       Los llamadores sin usuario (el loop de monitoreo) usan el token S2S del servicio.
     *   <li>{@link #SERVICE}: TODAS las llamadas viajan con el token S2S ({@code aud=runtime},
     *       {@code sub=service:cryptobot}). Requiere el cliente {@code cryptobot} en el allowlist
     *       de Auth y {@code service:cryptobot} en el de Runtime.
     * </ul>
     */
    public enum AuthMode {
        PASSTHROUGH,
        SERVICE
    }

    public String baseUrlNormalized() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8090";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    public String workflowIdNormalized() {
        return workflowId == null || workflowId.isBlank() ? "crypto.market-review.v1" : workflowId;
    }

    public AuthMode authModeNormalized() {
        if (authMode == null || authMode.isBlank()) {
            return AuthMode.PASSTHROUGH;
        }
        try {
            return AuthMode.valueOf(authMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "cryptobot.runtime.auth-mode (CRYPTOBOT_RUNTIME_AUTH_MODE) inválido: se acepta"
                            + " passthrough o service",
                    ex);
        }
    }
}
