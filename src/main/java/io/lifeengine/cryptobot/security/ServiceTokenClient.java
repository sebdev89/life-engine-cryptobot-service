package io.lifeengine.cryptobot.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Obtiene y cachea tokens service-to-service emitidos por Auth.
 *
 * <p>Mismo contrato y mismo comportamiento que el {@code ServiceTokenClient} de Business Chat
 *: {@code POST {authBaseUrl}/api/auth/internal/service-token} con
 * {@code {clientId, clientSecret, audience}} → {@code {access_token, expires_in, …}}, RS256,
 * firmado por Auth. CryptoBot no firma nada: pide.
 *
 * <p><b>No hay fallback.</b> Ni a un token estático, ni a mandar la request sin
 * {@code Authorization}. Si Auth no responde o rechaza la credencial, la llamada falla con
 * {@link ServiceTokenUnavailableException} y el llamador ve el error.
 */
@Component
public class ServiceTokenClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenClient.class);

    /** La única audiencia que CryptoBot necesita hoy (least privilege): no pide otras. */
    public static final String AUDIENCE_RUNTIME = "runtime";

    /** Un token cacheado y hasta cuándo se considera usable (ya con el margen descontado). */
    private record CachedToken(String token, Instant renewAfter) {
        boolean usable() {
            return Instant.now().isBefore(renewAfter);
        }
    }

    /**
     * Caché <b>por audiencia</b>. Hoy hay una sola, pero un token con una audiencia es una
     * credencial distinta de otro con otra audiencia: nunca se comparten entre servicios destino.
     */
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * Pedido en vuelo por audiencia: N llamadores concurrentes con el token vencido se suscriben
     * al MISMO {@code Mono} en vez de disparar N pedidos a Auth. Sin locks: bloquear dentro de una
     * cadena reactiva inmoviliza un hilo del event loop de Netty.
     */
    private final Map<String, Mono<String>> inFlight = new ConcurrentHashMap<>();

    private final ServiceTokenClientProperties props;
    private final WebClient webClient;

    public ServiceTokenClient(ServiceTokenClientProperties props, WebClient.Builder webClientBuilder) {
        this.props = props;
        this.webClient = webClientBuilder.build();
    }

    /** Si hay credencial S2S completa. Los llamadores headless lo consultan antes de intentar. */
    public boolean configured() {
        return props.configured();
    }

    /** Falla si falta configuración, sin intentar la llamada y sin nombrar ningún valor. */
    private void requireConfigured() {
        if (props.authBaseUrlNormalized().isBlank()) {
            throw new ServiceTokenUnavailableException(
                    "AUTH_INTERNAL_BASE_URL sin configurar: CryptoBot no puede pedir tokens S2S");
        }
        if (props.clientIdNormalized().isBlank()) {
            throw new ServiceTokenUnavailableException(
                    "CRYPTOBOT_S2S_CLIENT_ID sin configurar: CryptoBot no puede autenticarse ante Auth");
        }
        if (props.clientSecretNormalized().isBlank()) {
            throw new ServiceTokenUnavailableException(
                    "CRYPTOBOT_S2S_CLIENT_SECRET sin configurar: CryptoBot no puede autenticarse ante Auth");
        }
    }

    /**
     * Token válido para {@code audience}, de la caché o pidiéndolo a Auth.
     *
     * <p>Devuelve {@code Mono} y no {@code String}: los llamadores están en cadenas reactivas
     * sobre el event loop de Netty, donde {@code block()} está prohibido. El error se propaga como
     * señal de error del {@code Mono}, no como excepción lanzada.
     */
    public Mono<String> token(String audience) {
        return Mono.defer(
                () -> {
                    requireConfigured();

                    CachedToken cached = cache.get(audience);
                    if (cached != null && cached.usable()) {
                        return Mono.just(cached.token());
                    }
                    return inFlight.computeIfAbsent(audience, this::singleFlight);
                });
    }

    private Mono<String> singleFlight(String audience) {
        return fetch(audience)
                .doOnNext(fresh -> cache.put(audience, fresh))
                .map(CachedToken::token)
                .doFinally(signal -> inFlight.remove(audience))
                .cache();
    }

    /**
     * Invalida el token cacheado de una audiencia. Lo llama el llamador tras un 401, ANTES de
     * reintentar: reintentar con el mismo token rechazado sería un reintento decorativo.
     */
    public void invalidate(String audience) {
        cache.remove(audience);
        // El pedido en vuelo está construido con cache(): si quedara registrado volvería a EMITIR
        // el token ya rechazado a quien se suscriba después.
        inFlight.remove(audience);
        log.debug("s2s_token_invalidated audience={}", audience);
    }

    private Mono<CachedToken> fetch(String audience) {
        return webClient
                .post()
                .uri(props.authBaseUrlNormalized() + "/api/auth/internal/service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "clientId", props.clientIdNormalized(),
                                "clientSecret", props.clientSecretNormalized(),
                                "audience", audience))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                // Un 200 con cuerpo vacío llegaría como Mono vacío y la cadena terminaría sin
                // token ni error: el llamador vería "sin resultado" en vez de un fallo de auth.
                .switchIfEmpty(
                        Mono.error(
                                new ServiceTokenUnavailableException(
                                        "Auth respondió sin cuerpo para audience=" + audience)))
                .map(response -> toCachedToken(audience, response))
                .onErrorMap(ex -> translate(audience, ex));
    }

    private CachedToken toCachedToken(String audience, Map<String, Object> response) {
        if (!(response.get("access_token") instanceof String token) || token.isBlank()) {
            throw new ServiceTokenUnavailableException(
                    "Auth respondió sin access_token para audience=" + audience);
        }
        // El TTL sale de la RESPUESTA de Auth, no de una constante local.
        int expiresIn = response.get("expires_in") instanceof Number n ? n.intValue() : 0;
        if (expiresIn <= 0) {
            throw new ServiceTokenUnavailableException(
                    "Auth respondió con expires_in inválido para audience=" + audience);
        }
        // El margen no puede dejar la ventana en cero o negativa.
        long usableSeconds = Math.max(1, expiresIn - props.refreshMarginSeconds());

        log.info(
                "s2s_token_obtained clientId={} audience={} expires_in_s={} renew_in_s={}",
                props.clientIdNormalized(),
                audience,
                expiresIn,
                usableSeconds);

        return new CachedToken(token, Instant.now().plusSeconds(usableSeconds));
    }

    /** Traduce cualquier fallo a {@link ServiceTokenUnavailableException}, sin filtrar el secreto. */
    private Throwable translate(String audience, Throwable ex) {
        if (ex instanceof ServiceTokenUnavailableException already) {
            return already;
        }
        if (ex instanceof WebClientResponseException http) {
            // Ni el cuerpo de la respuesta ni el secreto presentado van al log.
            log.error(
                    "s2s_token_request_failed audience={} status={} — sin fallback: la llamada falla",
                    audience,
                    http.getStatusCode().value());
            return new ServiceTokenUnavailableException(
                    "Auth rechazó el pedido de token S2S para audience="
                            + audience
                            + " (status "
                            + http.getStatusCode().value()
                            + ")",
                    http);
        }
        log.error(
                "s2s_token_request_failed audience={} error={} — sin fallback: la llamada falla",
                audience,
                ex.getClass().getSimpleName());
        return new ServiceTokenUnavailableException(
                "No se pudo obtener un token S2S de Auth para audience="
                        + audience
                        + ": "
                        + ex.getClass().getSimpleName(),
                ex);
    }

    /** CryptoBot no puede autenticarse S2S. Se propaga: no hay camino alternativo. */
    public static class ServiceTokenUnavailableException extends RuntimeException {
        public ServiceTokenUnavailableException(String message) {
            super(message);
        }

        public ServiceTokenUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Solo para tests: vacía la caché. */
    void clearCacheForTests() {
        cache.clear();
        inFlight.clear();
    }
}
