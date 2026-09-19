package io.lifeengine.cryptobot.signer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param validatorPublicKey base58 Ed25519 public key of {@code cryptobot-validator} (KAN-438).
 *     Every sign request must carry an attestation signed by it; empty ⇒ nothing is ever signed
 *     while {@code requireAttestation} is on.
 * @param requireAttestation the level-5 gate (paper §20). {@code false} is the pre-KAN-438
 *     behaviour (token + byte-level limits only) and is meant for tests and local demos, never for
 *     a wallet with anything in it. KAN-493: only accepted under the Spring profile {@code local}
 *     or {@code test}; any other profile refuses to start ({@link AttestationRequirementGuard}).
 * @param allowMainnet KAN-493: mainnet is fail-closed. {@code false} (default, env
 *     {@code SIGNER_ALLOW_MAINNET}) ⇒ a sign request for {@code mainnet-beta} is refused whatever
 *     the attestation says. Independent of the service's {@code CRYPTOBOT_ALLOW_MAINNET}.
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
        Boolean requireAttestation,
        Boolean allowMainnet) {

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
        allowMainnet = allowMainnet == null ? Boolean.FALSE : allowMainnet;
    }

    /** {@code devnet} | {@code mainnet-beta}, or {@code null} when the string names no cluster this signer knows. */
    public static String canonicalCluster(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "devnet" -> "devnet";
            case "mainnet", "mainnet-beta" -> "mainnet-beta";
            default -> null;
        };
    }

    public static boolean isMainnet(String cluster) {
        return "mainnet-beta".equals(canonicalCluster(cluster));
    }
}
