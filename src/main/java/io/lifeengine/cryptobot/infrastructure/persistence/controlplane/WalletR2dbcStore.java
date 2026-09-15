package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class WalletR2dbcStore implements WalletRepository {

    private static final String COLS = "id, owner_user_id, address, cluster, label, created_at, updated_at";
    private final DatabaseClient db;

    public WalletR2dbcStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Wallet> insert(Wallet w) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO wallet (id, owner_user_id, address, cluster, label, created_at, updated_at)"
                                + " VALUES (:id, :owner, :address, :cluster, :label, :created, :updated)")
                .bind("id", w.id())
                .bind("owner", w.ownerUserId())
                .bind("address", w.address())
                .bind("cluster", w.cluster().id())
                .bind("created", w.createdAt())
                .bind("updated", w.updatedAt());
        spec = w.label() == null ? spec.bindNull("label", String.class) : spec.bind("label", w.label());
        return spec.fetch().rowsUpdated().then(findByIdAndOwner(w.id(), w.ownerUserId()));
    }

    @Override
    public Mono<Wallet> findByIdAndOwner(UUID id, UUID ownerUserId) {
        return db.sql("SELECT " + COLS + " FROM wallet WHERE id = :id AND owner_user_id = :owner")
                .bind("id", id)
                .bind("owner", ownerUserId)
                .map((row, meta) -> map(row))
                .one();
    }

    @Override
    public Mono<Wallet> findByOwnerAndAddress(UUID ownerUserId, String address, SolanaCluster cluster) {
        return db.sql("SELECT " + COLS + " FROM wallet WHERE owner_user_id = :owner AND address = :address AND cluster = :cluster")
                .bind("owner", ownerUserId)
                .bind("address", address)
                .bind("cluster", cluster.id())
                .map((row, meta) -> map(row))
                .one();
    }

    @Override
    public Flux<Wallet> findByOwner(UUID ownerUserId) {
        return db.sql("SELECT " + COLS + " FROM wallet WHERE owner_user_id = :owner ORDER BY created_at DESC")
                .bind("owner", ownerUserId)
                .map((row, meta) -> map(row))
                .all();
    }

    private static Wallet map(Row row) {
        return new Wallet(
                row.get("id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("address", String.class),
                SolanaCluster.parse(row.get("cluster", String.class)),
                row.get("label", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
    }
}
