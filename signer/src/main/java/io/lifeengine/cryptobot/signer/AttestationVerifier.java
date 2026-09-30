package io.lifeengine.cryptobot.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.signer.solana.Base58;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The signer's half of paper §20: it signs nothing the independent validator did not attest.
 * An attestation is the validator's canonical-JSON payload plus its Ed25519 signature. The
 * signer verifies, in this order and refusing at the first failure:
 *
 * <ol>
 *   <li>a validator key is pinned here (else nothing is ever signed: fail-closed);
 *   <li>the signature verifies over the exact payload bytes with that key;
 *   <li>{@code proposal_id} is the proposal being signed;
 *   <li>{@code message_hash} is SHA-256 of the message bytes the signer just decoded — the
 *       attestation covers <em>these</em> bytes, not a description of them;
 *   <li>{@code decision} is ALLOW or ESCALATE (a DENY attestation is evidence, not authority);
 *   <li>the attestation is inside its {@code [issued_at, expires_at]} window (small clock skew allowed);
 *   <li>an internal ticket: {@code cluster} is present and is the cluster the request named — the validator
 *       attested these bytes <em>for that cluster</em>, so a devnet attestation cannot be replayed
 *       for mainnet bytes and vice-versa.
 * </ol>
 *
 * It never re-evaluates the policy: that is the validator's job, and the whole point is that
 * this process cannot be talked into it.
 */
@Component
public class AttestationVerifier {

    private static final Logger log = LoggerFactory.getLogger(AttestationVerifier.class);
    private static final Set<String> AUTHORIZING = Set.of("ALLOW", "ESCALATE");
    static final long CLOCK_SKEW_SECONDS = 30;

    public record Attestation(String payload, String signature) {}

    public record Verdict(boolean ok, String reason, String validator, String decision, String verdictHash) {
        static Verdict refuse(String reason) {
            return new Verdict(false, reason, null, null, null);
        }
    }

    private final SignerProperties props;
    private final byte[] validatorKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AttestationVerifier(SignerProperties props) {
        this(props, Clock.systemUTC());
    }

    AttestationVerifier(SignerProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        byte[] key = null;
        if (!props.validatorPublicKey().isEmpty()) {
            try {
                key = Base58.decode(props.validatorPublicKey());
                if (key.length != 32) {
                    throw new IllegalArgumentException("expected 32 bytes, got " + key.length);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("SIGNER_VALIDATOR_PUBLIC_KEY is not a base58 Ed25519 public key: " + e.getMessage(), e);
            }
        }
        this.validatorKey = key;
        if (props.requireAttestation() && key == null) {
            log.warn("signer_no_validator_key — attestation is required and no validator key is pinned: every sign request will be refused");
        } else if (!props.requireAttestation()) {
            log.warn("signer_attestation_not_required — the level-5 gate is OFF; only for tests and empty demo wallets");
        } else {
            log.info("signer_validator_pinned validator={}", props.validatorPublicKey());
        }
    }

    public boolean required() {
        return props.requireAttestation();
    }

    /** @param cluster the cluster the sign request named; the attestation must be for the same one. */
    public Verdict verify(Attestation att, String proposalId, byte[] message, String cluster) {
        if (!props.requireAttestation()) {
            return new Verdict(true, null, null, "NOT_REQUIRED", null);
        }
        if (validatorKey == null) {
            return Verdict.refuse("validator_key_not_configured");
        }
        if (att == null || att.payload() == null || att.payload().isBlank() || att.signature() == null || att.signature().isBlank()) {
            return Verdict.refuse("attestation_missing");
        }
        byte[] signature;
        try {
            signature = Base58.decode(att.signature());
        } catch (IllegalArgumentException e) {
            return Verdict.refuse("attestation_bad_signature");
        }
        if (signature.length != 64 || !SolanaKeypair.verify(validatorKey, att.payload().getBytes(StandardCharsets.UTF_8), signature)) {
            return Verdict.refuse("attestation_bad_signature");
        }
        JsonNode p;
        try {
            p = mapper.readTree(att.payload());
        } catch (Exception e) {
            return Verdict.refuse("attestation_unparseable");
        }
        if (p == null || !p.isObject()) {
            return Verdict.refuse("attestation_unparseable");
        }
        String validator = text(p, "validator");
        if (validator == null || !validator.equals(props.validatorPublicKey())) {
            return Verdict.refuse("attestation_validator_mismatch");
        }
        if (proposalId == null || proposalId.isBlank() || !proposalId.equals(text(p, "proposal_id"))) {
            return Verdict.refuse("attestation_proposal_mismatch");
        }
        String expectedHash = sha256Hex(message);
        String attested = text(p, "message_hash");
        if (attested == null || !expectedHash.equals(attested.toLowerCase(Locale.ROOT))) {
            return Verdict.refuse("attestation_message_mismatch");
        }
        String decision = text(p, "decision");
        if (decision == null || !AUTHORIZING.contains(decision)) {
            return Verdict.refuse("attestation_denied");
        }
        long now = clock.instant().getEpochSecond();
        JsonNode issued = p.get("issued_at");
        JsonNode expires = p.get("expires_at");
        if (issued == null || !issued.canConvertToLong() || expires == null || !expires.canConvertToLong()) {
            return Verdict.refuse("attestation_no_window");
        }
        if (issued.asLong() > now + CLOCK_SKEW_SECONDS) {
            return Verdict.refuse("attestation_not_yet_valid");
        }
        if (expires.asLong() < now - CLOCK_SKEW_SECONDS) {
            return Verdict.refuse("attestation_expired");
        }
        String attestedCluster = SignerProperties.canonicalCluster(text(p, "cluster"));
        if (attestedCluster == null) {
            return Verdict.refuse("attestation_cluster_missing");
        }
        if (!attestedCluster.equals(SignerProperties.canonicalCluster(cluster))) {
            return Verdict.refuse("attestation_cluster_mismatch");
        }
        return new Verdict(true, null, validator, decision, text(p, "verdict_hash"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
