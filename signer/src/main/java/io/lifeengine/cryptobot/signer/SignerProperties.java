package io.lifeengine.cryptobot.signer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param validatorPublicKey base58 Ed25519 public key of {@code cryptobot-validator} (KAN-438).
 *     Every sign request must carry an attestation signed by it; empty ⇒ nothing is ever signed
 *     while {@code requireAttestation} is on.
 * @param requireAttestation the level-5 gate (paper §20). {@code false} is the pre-KAN-438
 *     behaviour (token + byte-level limits only) and is meant for tests and local demos, never for
 *     a wallet with anything in it.
 */
@ConfigurationProperties(prefix = "signer")
public record SignerProperties(
        String keypairPath,
        String keypairJson,
        String token,
        String cluster,
        long maxLamports,
        List<String> allowedDestinations,
        boolean enabled,
        String validatorPublicKey,
        Boolean requireAttestation) {

    public SignerProperties {
        keypairPath = keypairPath == null ? "" : keypairPath.trim();
        keypairJson = keypairJson == null ? "" : keypairJson.trim();
        token = token == null ? "" : token.trim();
        cluster = cluster == null || cluster.isBlank() ? "devnet" : cluster.trim();
        maxLamports = maxLamports <= 0 ? 2_000_000_000L : maxLamports;
        allowedDestinations = allowedDestinations == null
                ? List.of()
                : allowedDestinations.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        validatorPublicKey = validatorPublicKey == null ? "" : validatorPublicKey.trim();
        requireAttestation = requireAttestation == null ? Boolean.TRUE : requireAttestation;
    }
}
