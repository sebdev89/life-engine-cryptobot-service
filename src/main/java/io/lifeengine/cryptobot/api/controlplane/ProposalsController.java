package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.core.intent.IntentHash;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.AuditEvent;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.OutboxRepository;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/cryptobot/proposals", produces = "application/json")
public class ProposalsController {

    /** Idempotency key of {@code POST …/execute} (IETF draft name). Body {@code operationId} is the fallback. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProposalService proposals;
    private final ExecutionService execution;
    private final AuditService audit;
    private final OutboxRepository outbox;
    private final DeadLetterRepository deadLetters;

    public ProposalsController(ProposalService proposals, ExecutionService execution, AuditService audit, OutboxRepository outbox, DeadLetterRepository deadLetters) {
        this.proposals = proposals;
        this.execution = execution;
        this.audit = audit;
        this.outbox = outbox;
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public Flux<ActionProposal> list(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return proposals.listForOwner(Principals.require(principal).userId(), limit);
    }

    @GetMapping("/{proposalId}")
    public Mono<ControlPlaneDtos.ProposalView> get(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId)
                .flatMap(pr -> audit.forProposal(pr.id()).collectList().map(ev -> new ControlPlaneDtos.ProposalView(pr, ev)));
    }

    @GetMapping("/{proposalId}/audit")
    public Flux<AuditEvent> auditTrail(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId).flatMapMany(pr -> audit.forProposal(pr.id()));
    }

    /**
     * The durable event stream of the proposal: outbox events with their delivery state,
     * plus any dead letters. Owner-scoped through the proposal lookup — never a cross-owner list.
     */
    @GetMapping("/{proposalId}/events")
    public Mono<ControlPlaneDtos.ProposalEvents> events(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId)
                .flatMap(pr -> Mono.zip(outbox.findByAggregate(pr.id()).collectList(), deadLetters.findByProposal(pr.id()).collectList())
                        .map(t -> new ControlPlaneDtos.ProposalEvents(pr.id(), pr.status().name(), pr.operationId(), t.getT1(), t.getT2())));
    }

    @PostMapping(path = "/{proposalId}/approve")
    public Mono<ActionProposal> approve(@PathVariable UUID proposalId, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.approve(p.userId(), proposalId, Principals.actor(p), body == null ? null : body.note());
    }

    @PostMapping(path = "/{proposalId}/reject")
    public Mono<ActionProposal> reject(@PathVariable UUID proposalId, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.reject(p.userId(), proposalId, Principals.actor(p), body == null ? null : body.note());
    }

    /**
     * (paper §19): during the timelock that starts at approval a human may cancel. Only
     * APPROVED proposals; anything in flight or terminal is a 409.
     */
    @PostMapping(path = "/{proposalId}/cancel")
    public Mono<ActionProposal> cancel(@PathVariable UUID proposalId, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.cancel(p.userId(), proposalId, Principals.actor(p), body == null ? null : body.note());
    }

    /**
     * Second, explicit click. Only APPROVED + executable + devnet. Everything else is a 409 with the
     * reason. Idempotent on {@code Idempotency-Key} (or body {@code operationId}): the same key
     * never produces a second transaction; a different key while in flight is a 409.
     *
     * <p>The key is either a UUID or an intent hash {@code sha256:<64 hex>}: the hash of
     * the canonical intent is the identity of the operation, so re-submitting the same intent is
     * idempotent by construction — the operationId is derived from the hash, never invented.
     */
    @PostMapping(path = "/{proposalId}/execute")
    public Mono<ActionProposal> execute(@PathVariable UUID proposalId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) ControlPlaneDtos.ExecuteRequest body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        String raw = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : body == null ? null : body.operationId();
        UUID operationId;
        String intentHash = null;
        if (raw == null || raw.isBlank()) {
            operationId = UUID.randomUUID();
        } else {
            try {
                intentHash = intentHashOf(raw.trim());
                operationId = intentHash != null ? IntentHash.parse(intentHash).toOperationId() : UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ex) {
                return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_OPERATION_ID",
                        "Idempotency-Key / operationId must be a UUID or an intent hash sha256:<64 hex>"));
            }
        }
        // (CB-03): the hash itself is persisted with the row (action_proposal.intent_hash), not only folded into the id.
        return execution.execute(p.userId(), proposalId, Principals.actor(p), operationId, intentHash);
    }

    /** {@code sha256:…} ⇒ the intent's operationId (first 128 bits of the hash); anything else must be a UUID. */
    static UUID operationIdOf(String key) {
        String hash = intentHashOf(key);
        return hash != null ? IntentHash.parse(hash).toOperationId() : UUID.fromString(key);
    }

    /** The canonical {@code sha256:<64 lower-case hex>} when {@code key} is an intent hash; {@code null} when it is not one. */
    static String intentHashOf(String key) {
        if (key.regionMatches(true, 0, IntentHash.PREFIX, 0, IntentHash.PREFIX.length())) {
            return IntentHash.parse(key).value();
        }
        return null;
    }
}
