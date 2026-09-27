package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.execution.AuditEvent;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Append-only by construction: there is no update method to call. */
public interface AuditEventRepository {
    Mono<AuditEvent> append(AuditEvent event);

    /** Oldest first — a timeline. */
    Flux<AuditEvent> findByProposal(UUID proposalId);

    /** Newest first, capped. */
    Flux<AuditEvent> findByWallet(UUID walletId, int limit);
}
