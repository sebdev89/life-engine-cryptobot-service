package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.domain.receipt.Digests;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptArtifact;
import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.lifeengine.cryptobot.domain.receipt.ReceiptCanonicalizer;
import io.lifeengine.cryptobot.domain.receipt.ReceiptEdge;
import io.lifeengine.cryptobot.domain.receipt.ReceiptKind;
import io.lifeengine.cryptobot.domain.receipt.ReceiptSigningKey;
import io.lifeengine.cryptobot.domain.receipt.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ReceiptRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code canonicalize → hash → sign → persist} (Endgame §4, §6), and the inverse: {@code verify}.
 *
 * <p>The service knows nothing about wallets, prompts or plans: it receives a {@link ReceiptDraft}
 * whose body already contains only hashes and identifiers. What it enforces:
 *
 * <ul>
 *   <li><b>Parents exist and are the tenant's.</b> A receipt may only name receipts that are
 *       already stored under the same tenant (§7 "padres falsos"). Publishing across tenants is a
 *       later feature, not a hole.
 *   <li><b>The id is the content.</b> {@code receiptHash = SHA-256(domain ‖ 0x00 ‖ JCS(body))};
 *       the body lists the parents, so the child's id depends on theirs and a cycle cannot be
 *       built.
 *   <li><b>One nonce, one receipt.</b> The store refuses a second receipt with the same
 *       {@code (tenant, nonce)}; the same body again is a no-op.
 *   <li><b>Signed by the service key</b>, with the key id, over a domain-tagged message.
 * </ul>
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    /** Result of {@link #verify}: every check on its own, so a UI can say exactly what failed. */
    public record Verification(
            String receiptHash,
            boolean hashMatchesCanonical,
            boolean bodyMatchesCanonical,
            boolean signatureValid,
            String keyId,
            boolean parentsPresent,
            ReproducibilityLevel level,
            Boolean reproduced) {

        /** Serialised too: the one field a client needs when it does not care which check failed. */
        @com.fasterxml.jackson.annotation.JsonProperty("valid")
        public boolean valid() {
            return hashMatchesCanonical && bodyMatchesCanonical && signatureValid && parentsPresent && !Boolean.FALSE.equals(reproduced);
        }
    }

    private final ReceiptRepository repository;
    private final ReceiptSigningKey key;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    /** Test-friendly: no metrics exported. */
    public ReceiptService(ReceiptRepository repository, ReceiptSigningKey key) {
        this(repository, key, CryptobotMetrics.noop());
    }

    @Autowired
    public ReceiptService(ReceiptRepository repository, ReceiptSigningKey key, CryptobotMetrics metrics) {
        this.repository = repository;
        this.key = key;
        this.metrics = metrics;
        this.clock = Clock.systemUTC();
    }

    public ReceiptSigningKey signingKey() {
        return key;
    }

    /** Pure: the receipt as it would be stored (hash + signature), without touching the store. Used by verify and by tests. */
    public IntelligenceReceipt seal(ReceiptBody body) {
        byte[] canonical = ReceiptCanonicalizer.canonicalBytes(body);
        String hash = ReceiptCanonicalizer.receiptHash(canonical);
        byte[] signature = key.sign(ReceiptCanonicalizer.signingMessage(hash));
        return new IntelligenceReceipt(hash, ReceiptCanonicalizer.HASH_DOMAIN, body, new String(canonical, StandardCharsets.UTF_8),
                new IntelligenceReceipt.Signature("ed25519", key.keyId(), Base64.getEncoder().encodeToString(signature)), null, clock.instant());
    }

    public Mono<IntelligenceReceipt> issue(ReceiptDraft draft) {
        ReceiptBody body = draft.body();
        return requireParents(body.parents(), body.tenantId())
                .then(Mono.fromSupplier(() -> seal(body)))
                .flatMap(receipt -> {
                    List<ReceiptEdge> edges = new ArrayList<>();
                    for (String parent : body.parents()) {
                        edges.add(new ReceiptEdge(receipt.receiptHash(), parent, draft.parentRoles().getOrDefault(parent, ReceiptEdge.Role.DERIVES_FROM)));
                    }
                    List<ReceiptArtifact> artifacts = draft.artifact() == null ? List.of() : List.of(new ReceiptArtifact(
                            body.output().hash(), receipt.receiptHash(), draft.artifact().type(), draft.artifact().schema(), draft.artifact().storageRef(), body.tenantId()));
                    return repository.insert(receipt, edges, artifacts);
                })
                .doOnNext(stored -> {
                    metrics.intelligenceReceipt("issued");
                    if (stored.body().reproducibility() == ReproducibilityLevel.L1_REPRODUCIBLE) {
                        metrics.deterministicInference();
                    }
                    log.info("receipt_issued kind={} hash={} level={} parents={} nonce={} keyId={}", stored.kind(), stored.receiptHash(),
                            stored.body().reproducibility(), stored.parents().size(), stored.body().nonce(), stored.signature().keyId());
                });
    }

    private Mono<Void> requireParents(List<String> parents, String tenantId) {
        if (parents.isEmpty()) {
            return Mono.empty();
        }
        return repository.existingInTenant(parents, tenantId).collectList().flatMap(found -> {
            Set<String> present = new HashSet<>(found);
            List<String> missing = parents.stream().filter(p -> !present.contains(p)).toList();
            return missing.isEmpty() ? Mono.empty()
                    : Mono.error(new ControlPlaneExceptions.Conflict("Parent receipt unknown or of another tenant: " + missing));
        });
    }

    /** Owner-scoped read. */
    public Mono<IntelligenceReceipt> require(UUID ownerId, String receiptHash) {
        String hash;
        try {
            hash = Digests.requireHash("receiptHash", receiptHash);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_RECEIPT_HASH", ex.getMessage()));
        }
        return repository.findByHashAndOwner(hash, ownerId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Receipt " + hash)));
    }

    public Flux<IntelligenceReceipt> forProposal(UUID proposalId) {
        return repository.findByProposal(proposalId);
    }

    public Flux<IntelligenceReceipt> forWallet(UUID walletId, int limit) {
        return repository.findByWallet(walletId, limit);
    }

    public Mono<IntelligenceReceipt> latest(UUID walletId, ReceiptKind kind) {
        return repository.findLatestByWalletAndKind(walletId, kind);
    }

    public Mono<IntelligenceReceipt> byNonce(String tenantId, String nonce) {
        return repository.findByNonce(tenantId, nonce);
    }

    public Flux<ReceiptEdge> parentsOf(String receiptHash) {
        return repository.parentsOf(receiptHash);
    }

    public Flux<ReceiptEdge> childrenOf(String receiptHash) {
        return repository.childrenOf(receiptHash);
    }

    /**
     * Recomputes everything from what is stored: the canonical bytes must hash to the id, the JSON
     * body must canonicalise to those bytes, the signature must verify under the service key, and
     * every parent must exist. {@code reproduced} is reserved for L1 re-execution (the risk engine
     * of KAN-392) and stays {@code null} until then — a receipt is never marked reproduced because
     * it says so.
     */
    public Mono<Verification> verify(UUID ownerId, String receiptHash) {
        return require(ownerId, receiptHash).flatMap(this::verify);
    }

    public Mono<Verification> verify(IntelligenceReceipt r) {
        byte[] canonical = r.canonicalJson().getBytes(StandardCharsets.UTF_8);
        boolean hashOk = ReceiptCanonicalizer.receiptHash(canonical).equals(r.receiptHash());
        boolean bodyOk;
        try {
            Map<String, Object> tree = r.body().toMap();
            bodyOk = java.util.Arrays.equals(ReceiptCanonicalizer.canonicalBytes(tree), canonical);
        } catch (RuntimeException ex) {
            bodyOk = false;
        }
        boolean sigOk = false;
        if (r.signature() != null && key.keyId().equals(r.signature().keyId())) {
            try {
                sigOk = key.verify(ReceiptCanonicalizer.signingMessage(r.receiptHash()), Base64.getDecoder().decode(r.signature().signatureBase64()));
            } catch (IllegalArgumentException ex) {
                sigOk = false;
            }
        }
        final boolean signatureValid = sigOk;
        final boolean bodyValid = bodyOk;
        return repository.existingInTenant(r.parents(), r.body().tenantId()).count()
                .map(found -> found == r.parents().size())
                .defaultIfEmpty(r.parents().isEmpty())
                .map(parentsOk -> new Verification(r.receiptHash(), hashOk, bodyValid, signatureValid, r.signature() == null ? null : r.signature().keyId(),
                        parentsOk, r.body().reproducibility(), null))
                .doOnNext(v -> {
                    metrics.intelligenceReceipt(v.valid() ? "verified" : "invalid");
                    if (!v.valid()) {
                        log.warn("receipt_verification_failed hash={} hashOk={} bodyOk={} signatureOk={} parentsOk={}",
                                v.receiptHash(), v.hashMatchesCanonical(), v.bodyMatchesCanonical(), v.signatureValid(), v.parentsPresent());
                    }
                });
    }
}
