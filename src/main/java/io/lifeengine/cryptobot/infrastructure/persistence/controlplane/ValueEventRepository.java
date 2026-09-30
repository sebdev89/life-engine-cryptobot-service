package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Append-only store of value events (KAN-818). No update path: an event is content-addressed. */
public interface ValueEventRepository {

    /** The stored row: the queryable columns plus the canonical JSON that hashes to {@code valueEventHash}. */
    record Row(
            String valueEventHash,
            String receiptHash,
            String tenantId,
            UUID ownerId,
            String contributorId,
            String contributorKind,
            String agentId,
            String contributionType,
            String evidenceHash,
            String evidenceRef,
            String acceptorId,
            String acceptanceMethod,
            String acceptanceHash,
            String canonical,
            Instant occurredAt,
            Instant createdAt) {}

    /** Same hash again ⇒ no-op, the stored row is returned. */
    Mono<Row> insert(Row row);

    /** Owner-scoped: an event of another owner is a 404. */
    Mono<Row> findByHashAndOwner(String valueEventHash, UUID ownerId);

    /** Newest first. */
    Flux<Row> findByOwner(UUID ownerId, int limit);

    /** Events whose contribution evidence has this hash — the correlation with Life Engine records. */
    Flux<Row> findByEvidence(UUID ownerId, String evidenceHash);
}
