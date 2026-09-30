package io.lifeengine.cryptobot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cómo CryptoBot le pide tokens service-to-service a Auth.
 *
 * <p>Antes CryptoBot llamaba a Runtime únicamente con el JWT del usuario que disparó el pedido
 * (pass-through), y el loop programado de monitoreo, que no tiene usuario, se salteaba cada tick
 * por no tener con qué autenticarse. Ahora el servicio tiene credencial propia
 * ({@code clientId=cryptobot}): pide un token a Auth y Auth lo firma con RS256 con la audiencia
 * exacta del servicio destino ({@code runtime}).
 *
 * <p>Todos los defaults de credencial son vacíos: {@link ServiceTokenClient} falla explícitamente
 * si falta configuración, y {@link ServiceTokenStartupValidator} no deja arrancar el servicio en
 * un ambiente real cuando el camino S2S está activo y falta la credencial.
 */
@ConfigurationProperties(prefix = "cryptobot.s2s")
public record ServiceTokenClientProperties(
        /** Base URL interna de Auth, p.ej. {@code http://auth:8081}. Nunca sale por el túnel. */
        @DefaultValue("") String authBaseUrl,

        /** Identidad de este servicio ante Auth. Tiene que estar en el allowlist de Auth. */
        @DefaultValue("") String clientId,

        /** Secreto propio, distinto por ambiente. No comparte nada con {@code JWT_SECRET}. */
        @DefaultValue("") String clientSecret,

        /**
         * Cuántos segundos antes del vencimiento se renueva el token. Se descuenta del TTL que
         * informa Auth en la respuesta, no de uno asumido.
         */
        @DefaultValue("30") int refreshMarginSeconds,

        /** Timeout de la llamada a Auth. Corto: es una llamada interna en la misma red. */
        @DefaultValue("5") int requestTimeoutSeconds) {

    public String authBaseUrlNormalized() {
        return authBaseUrl == null ? "" : authBaseUrl.trim().replaceAll("/+$", "");
    }

    public String clientIdNormalized() {
        return clientId == null ? "" : clientId.trim();
    }

    public String clientSecretNormalized() {
        return clientSecret == null ? "" : clientSecret.trim();
    }

    /** Las tres cosas sin las cuales no se puede pedir un token. */
    public boolean configured() {
        return !authBaseUrlNormalized().isBlank()
                && !clientIdNormalized().isBlank()
                && !clientSecretNormalized().isBlank();
    }
}
