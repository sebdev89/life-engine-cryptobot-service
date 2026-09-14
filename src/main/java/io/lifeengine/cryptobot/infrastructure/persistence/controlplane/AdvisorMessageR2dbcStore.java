package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.advisor.AdvisorMessage;
import io.r2dbc.postgresql.codec.Json;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class AdvisorMessageR2dbcStore implements AdvisorMessageRepository {

    private final DatabaseClient db;
    private final JsonDocs docs;

    public AdvisorMessageR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    @Override
    public Mono<AdvisorMessage> insert(AdvisorMessage m) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO advisor_message (id, wallet_id, owner_user_id, role, runtime_run_id, doc, created_at)"
                                + " VALUES (:id, :wallet, :owner, :role, :run, :doc, :created)")
                .bind("id", m.id())
                .bind("wallet", m.walletId())
                .bind("owner", m.ownerUserId())
                .bind("role", m.role())
                .bind("doc", docs.write(m))
                .bind("created", m.createdAt());
        spec = m.runtimeRunId() == null ? spec.bindNull("run", UUID.class) : spec.bind("run", m.runtimeRunId());
        return spec.fetch().rowsUpdated().thenReturn(m);
    }

    @Override
    public Flux<AdvisorMessage> findByWallet(UUID walletId, int limit) {
        return db.sql("SELECT doc FROM (SELECT doc, created_at FROM advisor_message WHERE wallet_id = :wallet"
                        + " ORDER BY created_at DESC LIMIT :limit) t ORDER BY created_at ASC")
                .bind("wallet", walletId)
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map((row, meta) -> docs.read(row.get("doc", Json.class), AdvisorMessage.class))
                .all();
    }
}
