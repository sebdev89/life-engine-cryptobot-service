package io.lifeengine.cryptobot.signer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param validatorPublicKey base58 Ed25519 public key of {@code cryptobot-validator}.
 *     Every sign request must carry an attestation signed by it; empty ⇒ nothing is ever signed
 *     while {@code requireAttestation} is on.
 * @param requireAttestation the level-5 gate (paper §20). {@code false} is the legacy
 *     behaviour (token + byte-level limits only) and is meant for tests and local demos, never for
 *     a wallet with anything in it. an internal ticket: only accepted under the Spring profile {@code local}
 *     or {@code test}; any other profile refuses to start ({@link AttestationRequirementGuard}).
 * @param allowMainnet an internal ticket: mainnet is fail-closed. {@code false} (default, env
 *     {@code SIGNER_ALLOW_MAINNET}) ⇒ a sign request for {@code mainnet-beta} is refused whatever
 *     the attestation says. Independent of the service's {@code CRYPTOBOT_ALLOW_MAINNET}.
 * @param maxLamportsPerDay KAN-602: lamports this signer signs in any rolling 24 h window
 *     ({@code SIGNER_MAX_LAMPORTS_PER_DAY}, default 10 SOL); see {@link DailySpendLedger}.
 * @param spendLedgerPath KAN-602: file the 24 h window is kept in ({@code SIGNER_SPEND_LEDGER_PATH}). Empty =
 *     in memory, reset by a restart; mandatory when {@code allowMainnet} is on.
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
        Boolean allowMainnet,
        Long maxLamportsPerDay,
        String spendLedgerPath) {

    @ConstructorBinding
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
        maxLamportsPerDay = maxLamportsPerDay == null || maxLamportsPerDay <= 0 ? 10_000_000_000L : maxLamportsPerDay;
        spendLedgerPath = spendLedgerPath == null ? "" : spendLedgerPath.trim();
    }

    /** The shape before KAN-602 (tests, wiring without a daily cap of its own): default daily cap, ledger in memory. */
    public SignerProperties(String keypairPath, String keypairJson, String token, String cluster, long maxLamports,
            List<String> allowedDestinations, boolean enabled, String validatorPublicKey, Boolean requireAttestation, Boolean allowMainnet) {
        this(keypairPath, keypairJson, token, cluster, maxLamports, allowedDestinations, enabled, validatorPublicKey, requireAttestation,
                allowMainnet, null, null);
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
