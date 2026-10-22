package io.lifeengine.cryptobot.integration.validator;

import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogFields;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.policy.PolicyDecision;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * HTTP client for {@code cryptobot-validator} (paper §20). Before signing, this service
 * hands the validator the facts {@code (I, S)} it recorded when it decided, the {@code H_R} it
 * decided under, the verdict hash it recorded, and the SHA-256 of the exact message bytes it is
 * about to have signed. The validator re-derives the verdict in its own process and returns a
 * signed attestation the signer will demand.
 *
 * <p>Fail-closed on this side too: disabled, unreachable, malformed, DENY, a different policy hash
 * or a different verdict hash are all {@link ValidatorRefused}; nothing is signed.
 */
@Component
public class ValidatorClient {

    private static final Logger log = LoggerFactory.getLogger(ValidatorClient.class);
    public static final String TOKEN_HEADER = "X-Validator-Token";
    /** What the validator writes in the signed payload when it computed {@code message_hash} from the bytes (KAN-600). */
    public static final String CHECK_HASH_FROM_BYTES = "message_hash_from_bytes";
    /** What the validator writes when it applied the swap rules to the bytes (pilot). */
    public static final String CHECK_JUPITER_SWAP = "jupiter_swap_v1";
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Identity(String publicKey, String policyVersion, String policyHash, boolean pinned, boolean enabled) {}

    /** What travels to the signer: the validator's canonical payload and its Ed25519 signature over it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attestation(String payload, String signature, String validator) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            String decision,
            String escalation,
            String tier,
            List<String> failedPredicates,
            List<String> refusals,
            String policyVersion,
            String policyHash,
            String inputHash,
            String verdictHash,
            long issuedAt,
            long expiresAt,
            Attestation attestation) {

        public Instant expires() {
            return Instant.ofEpochSecond(expiresAt);
        }
    }

    public static class ValidatorRefused extends RuntimeException {
        public ValidatorRefused(String reason) {
            super("Validator refused: " + reason);
        }
    }

    private final WebClient webClient;
    private final ValidatorProperties props;

    public ValidatorClient(WebClient.Builder builder, ValidatorProperties props) {
        this.webClient = builder.baseUrl(props.baseUrl()).build();
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Empty when the validator is disabled or unreachable — the policy engine treats that as "not executable". */
    public Mono<Optional<Identity>> identity() {
        if (!props.enabled()) {
            return Mono.just(Optional.empty());
        }
        return webClient
                .get()
                .uri("/api/validator/identity")
                .header(TOKEN_HEADER, props.token())
                .retrieve()
                .bodyToMono(Identity.class)
                .timeout(props.timeout())
                .map(Optional::of)
                .onErrorResume(
                        ex -> {
                            log.warn("validator_identity_unavailable baseUrl={} error={}", props.baseUrl(), ex.toString(),
                                    LogFields.event("validator_identity"), LogFields.status("unavailable"), ErrorCode.VALIDATOR_UNAVAILABLE.kv());
                            return Mono.just(Optional.empty());
                        });
    }

    /**
     * Ask for an attestation over {@code tx}'s message for {@code proposal}. Errors with
     * {@link ValidatorRefused} unless the validator answered ALLOW or ESCALATE with the same
     * {@code H_R} and the same verdict hash this service recorded.
     */
    public Mono<Response> authorize(ActionProposal proposal, PreparedTransaction tx) {
        if (!props.enabled()) {
            return Mono.error(new ValidatorRefused("validator disabled"));
        }
        PolicyDecision decision = proposal.policy();
        PolicyVerdict verdict = decision == null ? null : decision.authorization();
        PolicyInput input = decision == null ? null : decision.input();
        if (verdict == null || input == null) {
            return Mono.error(new ValidatorRefused("proposal has no recorded (I, S) and verdict to re-validate (evaluated before KAN-438): re-create it"));
        }
        return authorize(proposal.id(), verdict, input, tx);
    }

    /**
     * the same request for an operation that is not an {@link ActionProposal} — a Proof of Value payout. The
     * caller recorded {@code (I, S)} and the verdict it derived over them; {@code operationId} is what the attestation
     * binds as {@code proposal_id} (and what the signer is then asked to sign for). Same checks, same refusals.
     */
    public Mono<Response> authorize(java.util.UUID operationId, PolicyVerdict verdict, PolicyInput input, PreparedTransaction tx) {
        return request(operationId, verdict, input, tx, null);
    }

    /**
     * Pilot: the swap the validator must check against these bytes ({@code SwapRules}) and sign into its payload.
     * Amounts are base units of their mints; {@code owner} is the signer's public key (the fee payer).
     */
    public record SwapIntent(String owner, String inputMint, String outputMint, long inAmount, long minOutAmount, int maxSlippageBps,
            long maxPriorityFeeLamports) {
        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", owner);
            m.put("input_mint", inputMint);
            m.put("output_mint", outputMint);
            m.put("in_amount", inAmount);
            m.put("min_out_amount", minOutAmount);
            m.put("max_slippage_bps", maxSlippageBps);
            m.put("max_priority_fee_lamports", maxPriorityFeeLamports);
            return m;
        }
    }

    /**
     * Pilot: an attestation for a Jupiter swap. Same request and checks as {@link #authorize}, plus the swap intent:
     * the answer must declare {@code jupiter_swap_v1} and carry, in its signed payload, exactly this swap (mints, input
     * amount, minimum output) — what the signer will in turn require before signing.
     */
    public Mono<Response> authorizeSwap(java.util.UUID operationId, PolicyVerdict verdict, PolicyInput input, PreparedTransaction tx, SwapIntent swap) {
        if (swap == null) {
            return Mono.error(new ValidatorRefused("no swap intent to attest"));
        }
        return request(operationId, verdict, input, tx, swap);
    }

    private Mono<Response> request(java.util.UUID operationId, PolicyVerdict verdict, PolicyInput input, PreparedTransaction tx, SwapIntent swap) {
        if (!props.enabled()) {
            return Mono.error(new ValidatorRefused("validator disabled"));
        }
        if (operationId == null || verdict == null || input == null) {
            return Mono.error(new ValidatorRefused("no operation id, recorded (I, S) or verdict to re-validate"));
        }
        if (tx == null || tx.messageBase64() == null || tx.messageBase64().isBlank()) {
            return Mono.error(new ValidatorRefused("no transaction message to attest"));
        }
        if (tx.cluster() == null || tx.cluster().isBlank()) {
            return Mono.error(new ValidatorRefused("no cluster on the prepared transaction to attest"));
        }
        if (tx.unsignedTransactionBase64() == null || tx.unsignedTransactionBase64().isBlank()) {
            return Mono.error(new ValidatorRefused("no transaction bytes for the validator to hash"));
        }
        String messageHash = sha256Hex(Base64.getDecoder().decode(tx.messageBase64()));
        String cluster = tx.cluster().trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", operationId.toString());
        body.put("policyHash", verdict.policyHash());
        body.put("expectedVerdictHash", verdict.hash());
        body.put("messageHash", messageHash);
        // KAN-600: the bytes themselves. The validator hashes them and refuses if they are not the message above.
        body.put("transaction", tx.unsignedTransactionBase64());
        // the attestation is bound to the cluster the bytes are for; the signer checks it.
        body.put("cluster", cluster);
        Map<String, Object> facts = input.canonicalMap();
        body.put("intent", facts.get("intent"));
        body.put("state", facts.get("state"));
        if (swap != null) {
            body.put("swap", swap.wire());
        }
        return webClient
                .post()
                .uri("/api/validator/validate")
                .header(TOKEN_HEADER, props.token())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Response.class)
                .timeout(props.timeout())
                // a 2xx with no body completes empty — and an empty Mono here let the pipeline skip the
                // validator AND the signer (HTTP 200, no proposal, row stuck EXECUTING). No answer is a refusal.
                .switchIfEmpty(Mono.error(new ValidatorRefused("no answer from the validator (empty response)")))
                .onErrorMap(WebClientResponseException.class, ex -> new ValidatorRefused("HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString()))
                .onErrorMap(ex -> !(ex instanceof ValidatorRefused), ex -> new ValidatorRefused(ex.getMessage()))
                .flatMap(r -> check(r, verdict, messageHash, cluster, swap));
    }

    private static Mono<Response> check(Response r, PolicyVerdict recorded, String messageHash, String cluster, SwapIntent swap) {
        if (r == null || r.attestation() == null || r.attestation().payload() == null || r.attestation().signature() == null) {
            return Mono.error(new ValidatorRefused("no attestation in the response"));
        }
        if (!"ALLOW".equals(r.decision()) && !"ESCALATE".equals(r.decision())) {
            return Mono.error(new ValidatorRefused("decision " + r.decision() + " refusals=" + r.refusals() + " failedPredicates=" + r.failedPredicates()));
        }
        if (!recorded.policyHash().equals(r.policyHash())) {
            return Mono.error(new ValidatorRefused("policy hash disagreement: recorded " + recorded.policyHash() + ", validator holds " + r.policyHash()));
        }
        if (!recorded.hash().equals(r.verdictHash())) {
            return Mono.error(new ValidatorRefused("verdict disagreement: recorded " + recorded.hash() + ", validator derived " + r.verdictHash()));
        }
        // read the signed payload as JSON, field by field: a substring match could be satisfied by a value the
        // request itself put there (a proposal id, a cluster) instead of the field it names
        JsonNode payload;
        try {
            payload = PAYLOAD_READER.readTree(r.attestation().payload());
        } catch (Exception ex) {
            payload = null;
        }
        if (payload == null || !payload.isObject()) {
            return Mono.error(new ValidatorRefused("attestation payload is not a JSON object"));
        }
        if (!messageHash.equals(payload.path("message_hash").asText(null))) {
            return Mono.error(new ValidatorRefused("attestation is not for these transaction bytes"));
        }
        if (!cluster.equals(payload.path("cluster").asText(null))) {
            return Mono.error(new ValidatorRefused("attestation is not for cluster " + cluster));
        }
        // the signed payload, not the unsigned response, must say the hash was computed from the bytes: a validator
        // that ignored them (an older build) would otherwise attest a hash it never checked
        JsonNode checks = payload.get("checks");
        boolean hashed = false;
        if (checks != null && checks.isArray()) {
            for (JsonNode c : checks) {
                hashed |= c.isTextual() && CHECK_HASH_FROM_BYTES.equals(c.asText());
            }
        }
        if (!hashed) {
            return Mono.error(new ValidatorRefused("attestation does not say the validator hashed the bytes itself"));
        }
        if (swap != null) {
            boolean swapChecked = false;
            for (JsonNode c : checks) {
                swapChecked |= c.isTextual() && CHECK_JUPITER_SWAP.equals(c.asText());
            }
            if (!swapChecked) {
                return Mono.error(new ValidatorRefused("attestation does not say the validator checked the swap rules"));
            }
            JsonNode a = payload.get("swap");
            if (a == null || !a.isObject() || !swap.inputMint().equals(a.path("input_mint").asText(null))
                    || !swap.outputMint().equals(a.path("output_mint").asText(null))
                    || !a.path("in_amount").canConvertToLong() || a.path("in_amount").asLong() != swap.inAmount()
                    || !a.path("min_out_amount").canConvertToLong() || a.path("min_out_amount").asLong() != swap.minOutAmount()) {
                return Mono.error(new ValidatorRefused("attested swap is not the intent's swap"));
            }
        }
        return Mono.just(r);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
