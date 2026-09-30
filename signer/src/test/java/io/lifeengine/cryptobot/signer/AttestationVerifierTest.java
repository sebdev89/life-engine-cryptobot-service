package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttestationVerifierTest {

    static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    static final long T = NOW.getEpochSecond();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();
    static final SolanaKeypair IMPOSTOR = SolanaKeypair.generate();
    static final byte[] MESSAGE = "the exact bytes".getBytes(StandardCharsets.UTF_8);
    static final String HASH = AttestationVerifier.sha256Hex(MESSAGE);

    static AttestationVerifier verifier(String validatorKey, boolean required) {
        SignerProperties p = new SignerProperties("", SigningPolicyTest.keyJson(SigningPolicyTest.KEY), "t", "devnet", 1, List.of(), true, validatorKey, required, false);
        return new AttestationVerifier(p, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static AttestationVerifier verifier() {
        return verifier(VALIDATOR.publicKeyBase58(), true);
    }

    @Test
    void acceptsAFreshAuthorizingAttestationForTheseBytes() {
        AttestationVerifier.Verdict v = verifier().verify(Attestations.fresh(VALIDATOR, "p-1", MESSAGE, T), "p-1", MESSAGE, "devnet");
        assertThat(v.ok()).isTrue();
        assertThat(v.validator()).isEqualTo(VALIDATOR.publicKeyBase58());
        assertThat(v.decision()).isEqualTo("ESCALATE");
        assertThat(v.verdictHash()).isEqualTo("sha256:" + "3".repeat(64));
    }

    @Test
    void failsClosedWithoutAValidatorKey() {
        AttestationVerifier.Verdict v = verifier("", true).verify(Attestations.fresh(VALIDATOR, "p-1", MESSAGE, T), "p-1", MESSAGE, "devnet");
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).isEqualTo("validator_key_not_configured");
        assertThatThrownBy(() -> verifier("not-a-key!!", true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrForgedAttestationsAreRefused() {
        AttestationVerifier verifier = verifier();
        assertThat(verifier.verify(null, "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_missing");
        assertThat(verifier.verify(new AttestationVerifier.Attestation("{}", ""), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_missing");
        // Signed by someone who is not the pinned validator.
        String payload = Attestations.payload("p-1", HASH, "ALLOW", VALIDATOR.publicKeyBase58(), T, T + 90);
        assertThat(verifier.verify(Attestations.signed(IMPOSTOR, payload), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_bad_signature");
        // Payload edited after signing.
        AttestationVerifier.Attestation real = Attestations.signed(VALIDATOR, payload);
        AttestationVerifier.Attestation edited = new AttestationVerifier.Attestation(real.payload().replace("\"expires_at\":" + (T + 90), "\"expires_at\":" + (T + 9000)), real.signature());
        assertThat(verifier.verify(edited, "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_bad_signature");
        // The impostor names itself: signature valid, but not the pinned key.
        String impostorPayload = Attestations.payload("p-1", HASH, "ALLOW", IMPOSTOR.publicKeyBase58(), T, T + 90);
        assertThat(verifier.verify(Attestations.signed(IMPOSTOR, impostorPayload), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_bad_signature");
    }

    @Test
    void mustBeForThisProposalAndTheseBytes() {
        AttestationVerifier verifier = verifier();
        assertThat(verifier.verify(Attestations.fresh(VALIDATOR, "p-1", MESSAGE, T), "p-2", MESSAGE, "devnet").reason()).isEqualTo("attestation_proposal_mismatch");
        byte[] other = "other bytes".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(Attestations.fresh(VALIDATOR, "p-1", other, T), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_message_mismatch");
    }

    @Test
    void aDenyIsEvidenceNotAuthority() {
        String payload = Attestations.payload("p-1", HASH, "DENY", VALIDATOR.publicKeyBase58(), T, T + 90);
        assertThat(verifier().verify(Attestations.signed(VALIDATOR, payload), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_denied");
    }

    @Test
    void windowIsEnforcedWithSmallSkew() {
        AttestationVerifier verifier = verifier();
        String expired = Attestations.payload("p-1", HASH, "ALLOW", VALIDATOR.publicKeyBase58(), T - 200, T - 100);
        assertThat(verifier.verify(Attestations.signed(VALIDATOR, expired), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_expired");
        String future = Attestations.payload("p-1", HASH, "ALLOW", VALIDATOR.publicKeyBase58(), T + 100, T + 200);
        assertThat(verifier.verify(Attestations.signed(VALIDATOR, future), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_not_yet_valid");
        // 10 s of skew either way is tolerated.
        String skewed = Attestations.payload("p-1", HASH, "ALLOW", VALIDATOR.publicKeyBase58(), T + 10, T + 100);
        assertThat(verifier.verify(Attestations.signed(VALIDATOR, skewed), "p-1", MESSAGE, "devnet").ok()).isTrue();
        String justExpired = Attestations.payload("p-1", HASH, "ALLOW", VALIDATOR.publicKeyBase58(), T - 100, T - 10);
        assertThat(verifier.verify(Attestations.signed(VALIDATOR, justExpired), "p-1", MESSAGE, "devnet").ok()).isTrue();
    }

    @Test
    void notRequiredMeansNotChecked() {
        AttestationVerifier.Verdict v = verifier("", false).verify(null, "p-1", MESSAGE, "devnet");
        assertThat(v.ok()).isTrue();
        assertThat(v.decision()).isEqualTo("NOT_REQUIRED");
    }

    // ---- an internal ticket: the attestation is for a cluster ------------------------------------------

    @Test
    void theAttestedClusterMustBeTheRequestsCluster() {
        AttestationVerifier verifier = verifier();
        // A mainnet attestation presented for a devnet request, and the other way round.
        AttestationVerifier.Attestation forMainnet = Attestations.fresh(VALIDATOR, "p-1", MESSAGE, T, "mainnet-beta");
        assertThat(verifier.verify(forMainnet, "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_cluster_mismatch");
        AttestationVerifier.Attestation forDevnet = Attestations.fresh(VALIDATOR, "p-1", MESSAGE, T, "devnet");
        assertThat(verifier.verify(forDevnet, "p-1", MESSAGE, "mainnet-beta").reason()).isEqualTo("attestation_cluster_mismatch");
        // Same cluster, either spelling: ok.
        assertThat(verifier.verify(forMainnet, "p-1", MESSAGE, "mainnet").ok()).isTrue();
        assertThat(verifier.verify(forDevnet, "p-1", MESSAGE, "DEVNET").ok()).isTrue();
    }

    @Test
    void anAttestationWithoutAClusterIsRefused() {
        String noCluster = Attestations.payload("p-1", HASH, "ESCALATE", VALIDATOR.publicKeyBase58(), T, T + 90, null);
        assertThat(verifier().verify(Attestations.signed(VALIDATOR, noCluster), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_cluster_missing");
        String unknown = Attestations.payload("p-1", HASH, "ESCALATE", VALIDATOR.publicKeyBase58(), T, T + 90, "testnet");
        assertThat(verifier().verify(Attestations.signed(VALIDATOR, unknown), "p-1", MESSAGE, "devnet").reason()).isEqualTo("attestation_cluster_missing");
    }
}
