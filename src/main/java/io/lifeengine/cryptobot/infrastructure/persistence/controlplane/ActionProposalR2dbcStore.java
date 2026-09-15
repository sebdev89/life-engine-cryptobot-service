package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.r2dbc.postgresql.codec.Json;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class ActionProposalR2dbcStore implements ActionProposalRepository {

    private final DatabaseClient db;
    private final JsonDocs docs;

    public ActionProposalR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    @Override
    public Mono<ActionProposal> insert(ActionProposal p) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO action_proposal (id, wallet_id, owner_user_id, status, kind, runtime_run_id, doc, created_at, updated_at)"
                                + " VALUES (:id, :wallet, :owner, :status, :kind, :run, :doc, :created, :updated)")
                .bind("id", p.id())
                .bind("wallet", p.walletId())
                .bind("owner", p.ownerUserId())
                .bind("status", p.status().name())
                .bind("kind", p.kind())
                .bind("doc", docs.write(p))
                .bind("created", p.createdAt())
                .bind("updated", p.updatedAt());
        spec = p.runtimeRunId() == null ? spec.bindNull("run", UUID.class) : spec.bind("run", p.runtimeRunId());
        return spec.fetch().rowsUpdated().then(findByIdAndOwner(p.id(), p.ownerUserId()));
    }

    @Override
    public Mono<ActionProposal> update(ActionProposal p) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "UPDATE action_proposal SET status = :status, runtime_run_id = :run, doc = :doc, updated_at = :updated"
                                + " WHERE id = :id AND owner_user_id = :owner")
                .bind("id", p.id())
                .bind("owner", p.ownerUserId())
                .bind("status", p.status().name())
                .bind("doc", docs.write(p))
                .bind("updated", p.updatedAt());
        spec = p.runtimeRunId() == null ? spec.bindNull("run", UUID.class) : spec.bind("run", p.runtimeRunId());
        return spec.fetch().rowsUpdated().then(findByIdAndOwner(p.id(), p.ownerUserId()));
    }

    @Override
    public Mono<ActionProposal> findByIdAndOwner(UUID id, UUID ownerUserId) {
        return db.sql("SELECT doc FROM action_proposal WHERE id = :id AND owner_user_id = :owner")
                .bind("id", id)
                .bind("owner", ownerUserId)
                .map((row, meta) -> docs.read(row.get("doc", Json.class), ActionProposal.class))
                .one();
    }

    @Override
    public Flux<ActionProposal> findByWallet(UUID walletId, int limit) {
        return db.sql("SELECT doc FROM action_proposal WHERE wallet_id = :wallet ORDER BY created_at DESC LIMIT :limit")
                .bind("wallet", walletId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map((row, meta) -> docs.read(row.get("doc", Json.class), ActionProposal.class))
                .all();
    }

    @Override
    public Flux<ActionProposal> findByOwner(UUID ownerUserId, int limit) {
        return db.sql("SELECT doc FROM action_proposal WHERE owner_user_id = :owner ORDER BY created_at DESC LIMIT :limit")
                .bind("owner", ownerUserId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map((row, meta) -> docs.read(row.get("doc", Json.class), ActionProposal.class))
                .all();
    }
}
