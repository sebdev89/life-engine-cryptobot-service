package io.lifeengine.cryptobot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fetches and caches the RSA public key from the Auth JWKS endpoint.
 * Inactive (always returns empty) when {@code AUTH_JWKS_URI} is not set.
 */
@Component
public class JwksPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksPublicKeyProvider.class);
    private static final long TTL_MS = 3_600_000L;

    private volatile RSAPublicKey cached;
    private volatile long expiryMs;
    private final String jwksUri;
    private final ObjectMapper objectMapper;

    public JwksPublicKeyProvider(CryptobotJwksProperties props, ObjectMapper objectMapper) {
        this.jwksUri = props.uri() == null ? "" : props.uri().trim();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return !jwksUri.isBlank();
    }

    public Optional<RSAPublicKey> getPublicKey() {
        if (!isConfigured()) return Optional.empty();
        long now = System.currentTimeMillis();
        if (cached != null && now < expiryMs) return Optional.of(cached);
        try {
            String body = WebClient.create().get().uri(jwksUri)
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            JsonNode keys = objectMapper.readTree(body).path("keys");
            if (!keys.isArray() || keys.isEmpty()) {
                log.warn("jwks_provider keys_empty uri={}", jwksUri);
                return cached != null ? Optional.of(cached) : Optional.empty();
            }
            JsonNode key = keys.get(0);
            RSAPublicKey pub = buildPublicKey(key.get("n").asText(), key.get("e").asText());
            cached = pub;
            expiryMs = System.currentTimeMillis() + TTL_MS;
            log.info("jwks_provider refreshed uri={}", jwksUri);
            return Optional.of(pub);
        } catch (Exception e) {
            log.warn("jwks_provider fetch_failed uri={} error={}", jwksUri, e.getMessage());
            return cached != null ? Optional.of(cached) : Optional.empty();
        }
    }

    private static RSAPublicKey buildPublicKey(String nB64, String eB64) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(pad(nB64)));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(pad(eB64)));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static String pad(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "====".substring(mod);
    }
}
