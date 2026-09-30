package io.lifeengine.cryptobot.application.value;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.core.value.ValueEvent;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ValueEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Records and verifies Value Events (KAN-818).
 *
 * <pre>
 *   command ─▶ ValueEvent (invariants: accepted by someone else, acceptance after contribution)
 *           ─▶ value-event hash ─▶ signed receipt VALUE_EVENT (output.hash = that hash)
 *           ─▶ value_event row ─▶ … the anchoring batch puts the receipt on Solana devnet …
 * </pre>
 *
 * The tenant is the owner of the JWT, resolved server-side by the caller; nothing here trusts a
 * client-supplied tenant.
 */
@Service
public class ValueEventService {

    private static final Logger log = LoggerFactory.getLogger(ValueEventService.class);

    public record Command(ValueEvent.Identity contributor, ValueEvent.AgentIdentity agent, ValueEvent.Contribution contribution,
            ValueEvent.AcceptanceProof acceptance, Instant occurredAt, String nonce) {}

    /** A stored event with its place in the chain of custody. {@code anchor} says whether the devnet memo is final. */
    public record View(ValueEventRepository.Row event, AnchorService.Inclusion anchor) {}

    /** {@code valid} = the canonical JSON re-hashes to the id AND the receipt verifies AND the receipt commits to that same id. */
    public record Verification(String valueEventHash, String receiptHash, boolean hashMatchesCanonical, boolean receiptValid,
            boolean receiptCommitsToEvent, AnchorService.Inclusion anchor) {
        @com.fasterxml.jackson.annotation.JsonProperty("valid")
        public boolean valid() {
            return hashMatchesCanonical && receiptValid && receiptCommitsToEvent;
        }
    }

    public record Summary(long events, long anchored, Map<String, Long> byContributionType, Map<String, Long> byContributor, Map<String, Long> byAcceptor,
            List<ValueEventRepository.Row> recent) {}

    private final ValueEventRepository events;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final Clock clock;

    @Autowired
    public ValueEventService(ValueEventRepository events, ReceiptService receipts, AnchorService anchors) {
        this(events, receipts, anchors, Clock.systemUTC());
    }

    ValueEventService(ValueEventRepository events, ReceiptService receipts, AnchorService anchors, Clock clock) {
        this.events = events;
        this.receipts = receipts;
        this.anchors = anchors;
        this.clock = clock;
    }

    public Mono<View> record(UUID ownerId, Command c) {
        ValueEvent event;
        try {
            event = new ValueEvent(ownerId.toString(), c.contributor(), c.agent(), c.contribution(), c.acceptance(), c.occurredAt(), c.nonce());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_VALUE_EVENT", ex.getMessage()));
        }
        String hash = event.hash();
        Map<String, Object> params = new TreeMap<>();
        params.put("contributionType", event.contribution().type().name());
        params.put("acceptanceMethod", event.acceptance().method());
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_EVENT, event.tenantId(), ownerId.toString(), event.contributor().id(), List.of(),
                List.of(new ReceiptInput(ReceiptInput.CONTRIBUTION_EVIDENCE, event.contribution().evidenceHash()),
                        new ReceiptInput(ReceiptInput.ACCEPTANCE_EVIDENCE, event.acceptance().evidenceHash())),
                null, null, null, null, params, new ReceiptBody.Output(hash, ValueEvent.SCHEMA, null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L0_SIGNED, event.occurredAt(), event.acceptance().acceptedAt(), "value-event:" + hash.substring(Digests.PREFIX.length()), null);
        return receipts.issue(ReceiptDraft.of(body))
                .flatMap(receipt -> events.insert(toRow(event, hash, receipt.receiptHash(), ownerId))
                        .flatMap(row -> anchors.inclusion(receipt).map(inc -> new View(row, inc))))
                .doOnNext(v -> log.info("value_event_recorded hash={} receipt={} type={} contributor={} acceptor={}", v.event().valueEventHash(),
                        v.event().receiptHash(), v.event().contributionType(), v.event().contributorId(), v.event().acceptorId()));
    }

    private ValueEventRepository.Row toRow(ValueEvent e, String hash, String receiptHash, UUID ownerId) {
        return new ValueEventRepository.Row(hash, receiptHash, e.tenantId(), ownerId, e.contributor().id(), e.contributor().kind().name(),
                e.agent() == null ? null : e.agent().identity().id(), e.contribution().type().name(), e.contribution().evidenceHash(),
                e.contribution().evidenceRef(), e.acceptance().acceptor().id(), e.acceptance().method(), e.acceptance().evidenceHash(), e.canonicalJson(),
                e.occurredAt(), clock.instant());
    }

    public Mono<View> get(UUID ownerId, String valueEventHash) {
        return requireRow(ownerId, valueEventHash).flatMap(row -> receipts.require(ownerId, row.receiptHash())
                .flatMap(anchors::inclusion).map(inc -> new View(row, inc)));
    }

    public Flux<ValueEventRepository.Row> list(UUID ownerId, int limit) {
        return events.findByOwner(ownerId, limit);
    }

    /** The events whose contributed artefact has this hash: how a Life Engine record (PR, handoff) finds its value events. */
    public Flux<ValueEventRepository.Row> byEvidence(UUID ownerId, String evidenceHash) {
        String h;
        try {
            h = Digests.requireHash("evidenceHash", evidenceHash);
        } catch (IllegalArgumentException ex) {
            return Flux.error(new ControlPlaneExceptions.InvalidRequest("INVALID_EVIDENCE_HASH", ex.getMessage()));
        }
        return events.findByEvidence(ownerId, h);
    }

    /** Recomputes everything from what is stored; nothing is believed because it is stored. */
    public Mono<Verification> verify(UUID ownerId, String valueEventHash) {
        return requireRow(ownerId, valueEventHash).flatMap(row -> receipts.require(ownerId, row.receiptHash()).flatMap(receipt -> {
            boolean hashOk = Digests.domainSeparated(ValueEvent.HASH_DOMAIN, row.canonical().getBytes(StandardCharsets.UTF_8)).equals(row.valueEventHash());
            boolean commits = receipt.kind() == ReceiptKind.VALUE_EVENT && receipt.body().output().hash().equals(row.valueEventHash());
            return Mono.zip(receipts.verify(receipt), anchors.inclusion(receipt))
                    .map(t -> new Verification(row.valueEventHash(), row.receiptHash(), hashOk, t.getT1().valid(), commits, t.getT2()));
        }));
    }

    public Mono<Summary> summary(UUID ownerId) {
        return events.findByOwner(ownerId, 500).collectList().flatMap(rows -> Flux.fromIterable(rows)
                .flatMap(r -> receipts.require(ownerId, r.receiptHash()).map(rc -> rc.anchor() != null && rc.anchor().tx() != null ? 1L : 0L).defaultIfEmpty(0L))
                .reduce(0L, Long::sum)
                .map(anchored -> new Summary(rows.size(), anchored, count(rows, ValueEventRepository.Row::contributionType),
                        count(rows, ValueEventRepository.Row::contributorId), count(rows, ValueEventRepository.Row::acceptorId),
                        rows.stream().limit(10).toList())));
    }

    private static Map<String, Long> count(List<ValueEventRepository.Row> rows, java.util.function.Function<ValueEventRepository.Row, String> key) {
        Map<String, Long> m = new TreeMap<>();
        for (ValueEventRepository.Row r : rows) {
            m.merge(key.apply(r), 1L, Long::sum);
        }
        return m;
    }

    private Mono<ValueEventRepository.Row> requireRow(UUID ownerId, String valueEventHash) {
        String h;
        try {
            h = Digests.requireHash("valueEventHash", valueEventHash);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_VALUE_EVENT_HASH", ex.getMessage()));
        }
        return events.findByHashAndOwner(h, ownerId).switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Value event " + h)));
    }
}
