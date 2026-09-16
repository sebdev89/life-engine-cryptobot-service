package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AuditEventRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Every state change of a proposal goes through here, and also to the log with the same fields.
 *
 * <p>KAN-403: proposal steps build the event with {@link #event} and hand it to
 * {@code ProposalTransition}, so it is written in the same transaction as the state. {@link #record}
 * stays for events that are not a proposal step (wallet registered, …).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    /** Builds (and logs) the event; the caller persists it atomically with the state. */
    public AuditEvent event(UUID ownerUserId, UUID walletId, UUID proposalId, String eventType, String actor, Map<String, Object> payload) {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), ownerUserId, walletId, proposalId, eventType, actor, payload, clock.instant());
        log.info("audit_event type={} proposalId={} walletId={} actor={}", eventType, proposalId, walletId, actor);
        return event;
    }

    public Mono<AuditEvent> record(UUID ownerUserId, UUID walletId, UUID proposalId, String eventType, String actor, Map<String, Object> payload) {
        return repository.append(event(ownerUserId, walletId, proposalId, eventType, actor, payload));
    }

    public Flux<AuditEvent> forProposal(UUID proposalId) {
        return repository.findByProposal(proposalId);
    }

    public Flux<AuditEvent> forWallet(UUID walletId, int limit) {
        return repository.findByWallet(walletId, limit);
    }
}
