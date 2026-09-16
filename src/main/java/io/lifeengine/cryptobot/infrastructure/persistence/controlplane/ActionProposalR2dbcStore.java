package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The proposal row + its JSONB document. {@link #commit} is the only write path after insert: one
 * transaction with an optimistic-lock guard on {@code version} and a status guard, plus the audit
 * and outbox rows of the step (KAN-403). {@code version} is read from the column, never from the
 * document.
 */
@Profile("!test")
@Component
public class ActionProposalR2dbcStore implements ActionProposalRepository {

    private final DatabaseClient db;
    private final JsonDocs docs;
    private final TransactionalOperator tx;

    public ActionProposalR2dbcStore(DatabaseClient db, JsonDocs docs, TransactionalOperator tx) {
        this.db = db;
        this.docs = docs;
        this.tx = tx;
    }

    @Override
    public Mono<ActionProposal> insert(ActionProposal p) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO action_proposal (id, wallet_id, owner_user_id, status, kind, runtime_run_id, doc, created_at, updated_at, version, operation_id)"
                                + " VALUES (:id, :wallet, :owner, :status, :kind, :run, :doc, :created, :updated, 0, :op)")
                .bind("id", p.id())
                .bind("wallet", p.walletId())
                .bind("owner", p.ownerUserId())
                .bind("status", p.status().name())
                .bind("kind", p.kind())
                .bind("doc", docs.write(p.withVersion(0)))
                .bind("created", p.createdAt())
                .bind("updated", p.updatedAt());
        spec = p.runtimeRunId() == null ? spec.bindNull("run", UUID.class) : spec.bind("run", p.runtimeRunId());
        spec = p.operationId() == null ? spec.bindNull("op", UUID.class) : spec.bind("op", p.operationId());
        return spec.fetch().rowsUpdated().then(findByIdAndOwner(p.id(), p.ownerUserId()));
    }

    @Override
    public Mono<ActionProposal> commit(ProposalTransition t) {
        ActionProposal p = t.proposal();
        long nextVersion = t.expectedVersion() + 1;
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "UPDATE action_proposal SET status = :status, runtime_run_id = :run, doc = :doc, updated_at = :updated,"
                                + " version = :nextVersion, operation_id = :op"
                                + " WHERE id = :id AND owner_user_id = :owner AND version = :expectedVersion AND status = :expectedStatus")
                .bind("id", p.id())
                .bind("owner", p.ownerUserId())
                .bind("status", p.status().name())
                .bind("doc", docs.write(p.withVersion(nextVersion)))
                .bind("updated", p.updatedAt())
                .bind("nextVersion", nextVersion)
                .bind("expectedVersion", t.expectedVersion())
                .bind("expectedStatus", t.expectedStatus().name());
        spec = p.runtimeRunId() == null ? spec.bindNull("run", UUID.class) : spec.bind("run", p.runtimeRunId());
        spec = p.operationId() == null ? spec.bindNull("op", UUID.class) : spec.bind("op", p.operationId());

        Mono<Void> update = spec.fetch().rowsUpdated().flatMap(rows -> rows == 1 ? Mono.empty()
                : Mono.error(new ControlPlaneExceptions.StaleProposal(p.id(), t.expectedStatus() + " v" + t.expectedVersion())));
        Mono<Void> audit = Flux.fromIterable(t.audit()).concatMap(e -> AuditEventR2dbcStore.insert(db, docs, e)).then();
        Mono<Void> outbox = Flux.fromIterable(t.outbox()).concatMap(e -> OutboxEventR2dbcStore.insert(db, docs, e)).then();

        return tx.transactional(update.then(audit).then(outbox))
                .onErrorMap(ActionProposalR2dbcStore::isUniqueViolation, ex -> new ControlPlaneExceptions.DuplicateOperation(p.operationId()))
                .then(findByIdAndOwner(p.id(), p.ownerUserId()));
    }

    private static boolean isUniqueViolation(Throwable ex) {
        return ex instanceof DataIntegrityViolationException || ex instanceof R2dbcDataIntegrityViolationException
                || (ex.getCause() != null && ex.getCause() instanceof R2dbcDataIntegrityViolationException);
    }

    @Override
    public Mono<ActionProposal> findByIdAndOwner(UUID id, UUID ownerUserId) {
        return db.sql("SELECT doc, version FROM action_proposal WHERE id = :id AND owner_user_id = :owner")
                .bind("id", id)
                .bind("owner", ownerUserId)
                .map((row, meta) -> read(row))
                .one();
    }

    @Override
    public Flux<ActionProposal> findByWallet(UUID walletId, int limit) {
        return db.sql("SELECT doc, version FROM action_proposal WHERE wallet_id = :wallet ORDER BY created_at DESC LIMIT :limit")
                .bind("wallet", walletId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map((row, meta) -> read(row))
                .all();
    }

    @Override
    public Flux<ActionProposal> findByOwner(UUID ownerUserId, int limit) {
        return db.sql("SELECT doc, version FROM action_proposal WHERE owner_user_id = :owner ORDER BY created_at DESC LIMIT :limit")
                .bind("owner", ownerUserId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map((row, meta) -> read(row))
                .all();
    }

    @Override
    public Flux<ActionProposal> findInFlight(Instant updatedBefore, int limit) {
        return db.sql("SELECT doc, version FROM action_proposal WHERE status IN (:executing, :submitted) AND updated_at < :before"
                        + " ORDER BY updated_at ASC LIMIT :limit")
                .bind("executing", ProposalStatus.EXECUTING.name())
                .bind("submitted", ProposalStatus.SUBMITTED.name())
                .bind("before", updatedBefore)
                .bind("limit", Math.max(1, Math.min(limit, 500)))
                .map((row, meta) -> read(row))
                .all();
    }

    private ActionProposal read(Row row) {
        ActionProposal p = docs.read(row.get("doc", Json.class), ActionProposal.class);
        Long version = row.get("version", Long.class);
        return p.withVersion(version == null ? 0 : version);
    }
}
