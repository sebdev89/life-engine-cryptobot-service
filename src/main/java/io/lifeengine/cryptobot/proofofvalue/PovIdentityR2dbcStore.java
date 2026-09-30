package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class PovIdentityR2dbcStore implements PovIdentityRepository {

    private static final String COLS = "tenant_id, id, kind, display_name, wallet, owner_id, operator_id, created_at";

    private final DatabaseClient db;

    public PovIdentityR2dbcStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<PovIdentity> insertIfAbsent(PovIdentity i) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO pov_identity (" + COLS + ") VALUES (:tenant, :id, :kind, :name, :wallet, :owner,"
                        + " :operator, :created) ON CONFLICT (tenant_id, id) DO NOTHING")
                .bind("tenant", i.tenantId())
                .bind("id", i.id())
                .bind("kind", i.kind().name())
                .bind("name", i.displayName())
                .bind("created", i.createdAt());
        spec = bindNullable(spec, "wallet", i.wallet());
        spec = bindNullable(spec, "owner", i.ownerId());
        spec = bindNullable(spec, "operator", i.operatorId());
        return spec.fetch().rowsUpdated().flatMap(n -> n > 0 ? Mono.just(i) : Mono.empty());
    }

    @Override
    public Mono<PovIdentity> find(String tenantId, String id) {
        return db.sql("SELECT " + COLS + " FROM pov_identity WHERE tenant_id = :tenant AND id = :id")
                .bind("tenant", tenantId).bind("id", id).map((row, meta) -> map(row)).one();
    }

    @Override
    public Mono<PovIdentity> setWalletIfMissing(String tenantId, String id, String wallet) {
        return db.sql("UPDATE pov_identity SET wallet = :wallet WHERE tenant_id = :tenant AND id = :id AND wallet IS NULL")
                .bind("wallet", wallet).bind("tenant", tenantId).bind("id", id)
                .fetch().rowsUpdated().flatMap(n -> n > 0 ? find(tenantId, id) : Mono.empty());
    }

    @Override
    public Flux<PovIdentity> findAll(String tenantId) {
        return db.sql("SELECT " + COLS + " FROM pov_identity WHERE tenant_id = :tenant ORDER BY created_at, id")
                .bind("tenant", tenantId).map((row, meta) -> map(row)).all();
    }

    @Override
    public Flux<PovIdentity> findAll(String tenantId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("SELECT " + COLS + " FROM pov_identity WHERE tenant_id = :tenant AND id IN (:ids)")
                .bind("tenant", tenantId).bind("ids", List.copyOf(ids)).map((row, meta) -> map(row)).all();
    }

    static DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static PovIdentity map(io.r2dbc.spi.Row row) {
        return new PovIdentity(
                row.get("tenant_id", String.class),
                row.get("id", String.class),
                IdentityKind.valueOf(row.get("kind", String.class)),
                row.get("display_name", String.class),
                row.get("wallet", String.class),
                row.get("owner_id", String.class),
                row.get("operator_id", String.class),
                row.get("created_at", Instant.class));
    }
}
