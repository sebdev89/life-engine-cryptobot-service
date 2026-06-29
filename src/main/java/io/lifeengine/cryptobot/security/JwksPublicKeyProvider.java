package io.lifeengine.cryptobot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fetches and caches the RSA public keys from the Auth JWKS endpoint, keyed by {@code kid}.
 * Supports key rotation: if Auth serves multiple keys during a rotation window, all are cached
 * and tokens can be verified with the matching key.
 * Inactive (always returns empty) when {@code AUTH_JWKS_URI} is not configured.
 */
@Component
public class JwksPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksPublicKeyProvider.class);
    private static final long TTL_MS = 3_600_000L;

    private volatile Map<String, RSAPublicKey> cachedKeys = Collections.emptyMap();
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

    /** Returns the public key matching the given {@code kid}, refreshing the cache if needed. */
    public Optional<RSAPublicKey> getPublicKey(String kid) {
        Map<String, RSAPublicKey> keys = refreshIfNeeded();
        if (kid != null && !kid.isBlank() && keys.containsKey(kid)) {
            return Optional.of(keys.get(kid));
        }
        // Fallback: if kid not found or blank, return the first available key
        return keys.values().stream().findFirst();
    }

    /** Returns the first available public key — convenience method for single-key scenarios. */
    public Optional<RSAPublicKey> getPublicKey() {
        return getPublicKey(null);
    }

    private Map<String, RSAPublicKey> refreshIfNeeded() {
        if (!isConfigured()) return Collections.emptyMap();
        long now = System.currentTimeMillis();
        if (!cachedKeys.isEmpty() && now < expiryMs) return cachedKeys;
        try {
            String body = WebClient.create().get().uri(jwksUri)
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            JsonNode keysNode = objectMapper.readTree(body).path("keys");
            if (!keysNode.isArray() || keysNode.isEmpty()) {
                log.warn("jwks_provider keys_empty uri={}", jwksUri);
                return cachedKeys;
            }
            Map<String, RSAPublicKey> parsed = new LinkedHashMap<>();
            for (JsonNode keyNode : keysNode) {
                String kid = keyNode.path("kid").asText("");
                RSAPublicKey pub = buildPublicKey(keyNode.get("n").asText(), keyNode.get("e").asText());
                parsed.put(kid, pub);
            }
            cachedKeys = Collections.unmodifiableMap(parsed);
            expiryMs = System.currentTimeMillis() + TTL_MS;
            log.info("jwks_provider refreshed uri={} keys={}", jwksUri, parsed.keySet());
            return cachedKeys;
        } catch (Exception e) {
            log.warn("jwks_provider fetch_failed uri={} error={}", jwksUri, e.getMessage());
            return cachedKeys;
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
