package io.lifeengine.cryptobot.integration.validator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
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
 * HTTP client for {@code cryptobot-validator} (KAN-438, paper §20). Before signing, this service
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
                            log.warn("validator_identity_unavailable baseUrl={} error={}", props.baseUrl(), ex.toString());
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
        if (tx == null || tx.messageBase64() == null || tx.messageBase64().isBlank()) {
            return Mono.error(new ValidatorRefused("no transaction message to attest"));
        }
        if (tx.cluster() == null || tx.cluster().isBlank()) {
            return Mono.error(new ValidatorRefused("no cluster on the prepared transaction to attest"));
        }
        String messageHash = sha256Hex(Base64.getDecoder().decode(tx.messageBase64()));
        String cluster = tx.cluster().trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposal.id().toString());
        body.put("policyHash", verdict.policyHash());
        body.put("expectedVerdictHash", verdict.hash());
        body.put("messageHash", messageHash);
        // KAN-493: the attestation is bound to the cluster the bytes are for; the signer checks it.
        body.put("cluster", cluster);
        Map<String, Object> facts = input.canonicalMap();
        body.put("intent", facts.get("intent"));
        body.put("state", facts.get("state"));
        return webClient
                .post()
                .uri("/api/validator/validate")
                .header(TOKEN_HEADER, props.token())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Response.class)
                .timeout(props.timeout())
                // KAN-500: a 2xx with no body completes empty — and an empty Mono here let the pipeline skip the
                // validator AND the signer (HTTP 200, no proposal, row stuck EXECUTING). No answer is a refusal.
                .switchIfEmpty(Mono.error(new ValidatorRefused("no answer from the validator (empty response)")))
                .onErrorMap(WebClientResponseException.class, ex -> new ValidatorRefused("HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString()))
                .onErrorMap(ex -> !(ex instanceof ValidatorRefused), ex -> new ValidatorRefused(ex.getMessage()))
                .flatMap(r -> check(r, verdict, messageHash, cluster));
    }

    private static Mono<Response> check(Response r, PolicyVerdict recorded, String messageHash, String cluster) {
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
        if (!r.attestation().payload().contains("\"message_hash\":\"" + messageHash + "\"")) {
            return Mono.error(new ValidatorRefused("attestation is not for these transaction bytes"));
        }
        if (!r.attestation().payload().contains("\"cluster\":\"" + cluster + "\"")) {
            return Mono.error(new ValidatorRefused("attestation is not for cluster " + cluster));
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
