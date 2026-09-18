package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.Base58;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import java.nio.charset.StandardCharsets;

/**
 * Builds validator attestations the way {@code cryptobot-validator} does (canonical JSON, keys in
 * code-unit order, Ed25519 over the UTF-8 bytes) so the signer tests exercise the real check
 * without depending on the validator module.
 */
final class Attestations {

    private Attestations() {}

    static String payload(String proposalId, String messageHash, String decision, String validator, long issuedAt, long expiresAt) {
        return payload(proposalId, messageHash, decision, validator, issuedAt, expiresAt, "devnet");
    }

    /** {@code cluster == null} ⇒ a pre-KAN-493 payload with no {@code cluster} key at all. */
    static String payload(String proposalId, String messageHash, String decision, String validator, long issuedAt, long expiresAt, String cluster) {
        return "{" + (cluster == null ? "" : "\"cluster\":\"" + cluster + "\",")
                + "\"decision\":\"" + decision + "\",\"escalation\":\"NONE\",\"expires_at\":" + expiresAt
                + ",\"input_hash\":\"sha256:" + "1".repeat(64) + "\",\"issued_at\":" + issuedAt
                + ",\"message_hash\":\"" + messageHash + "\",\"policy_hash\":\"sha256:" + "2".repeat(64) + "\""
                + ",\"proposal_id\":\"" + proposalId + "\",\"schema_version\":\"1\",\"validator\":\"" + validator + "\""
                + ",\"verdict_hash\":\"sha256:" + "3".repeat(64) + "\"}";
    }

    static AttestationVerifier.Attestation signed(SolanaKeypair validatorKey, String payload) {
        byte[] sig = validatorKey.sign(payload.getBytes(StandardCharsets.UTF_8));
        return new AttestationVerifier.Attestation(payload, Base58.encode(sig));
    }

    /** A fresh, authorizing attestation for {@code message} on devnet by {@code validatorKey}. */
    static AttestationVerifier.Attestation fresh(SolanaKeypair validatorKey, String proposalId, byte[] message, long now) {
        return fresh(validatorKey, proposalId, message, now, "devnet");
    }

    static AttestationVerifier.Attestation fresh(SolanaKeypair validatorKey, String proposalId, byte[] message, long now, String cluster) {
        return signed(validatorKey, payload(proposalId, AttestationVerifier.sha256Hex(message), "ESCALATE", validatorKey.publicKeyBase58(), now, now + 90, cluster));
    }
}
