package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class KnowledgeAssetR2dbcStore implements KnowledgeAssetRepository {

    private static final String SELECT = "SELECT a.tenant_id, a.id, a.version, a.kind, a.title, a.creator_id, a.content_hash, a.parent_ids,"
            + " a.created_at, i.display_name FROM pov_knowledge_asset a"
            + " JOIN pov_identity i ON i.tenant_id = a.tenant_id AND i.id = a.creator_id";

    private final DatabaseClient db;

    public KnowledgeAssetR2dbcStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<PovKnowledgeAsset> insertIfAbsent(PovKnowledgeAsset a) {
        return db.sql("INSERT INTO pov_knowledge_asset (tenant_id, id, version, kind, title, creator_id, content_hash, parent_ids, created_at)"
                        + " VALUES (:tenant, :id, :version, :kind, :title, :creator, :hash, :parents, :created) ON CONFLICT (tenant_id, id) DO NOTHING")
                .bind("tenant", a.tenantId())
                .bind("id", a.id())
                .bind("version", a.version())
                .bind("kind", a.kind().name())
                .bind("title", a.title())
                .bind("creator", a.creatorId())
                .bind("hash", a.contentHash())
                .bind("parents", a.parentIds().toArray(String[]::new))
                .bind("created", a.createdAt())
                .fetch().rowsUpdated()
                .flatMap(n -> n > 0 ? find(a.tenantId(), a.id()) : Mono.empty());
    }

    @Override
    public Mono<PovKnowledgeAsset> find(String tenantId, String id) {
        return db.sql(SELECT + " WHERE a.tenant_id = :tenant AND a.id = :id")
                .bind("tenant", tenantId).bind("id", id).map((row, meta) -> map(row)).one();
    }

    @Override
    public Flux<PovKnowledgeAsset> findAll(String tenantId) {
        return db.sql(SELECT + " WHERE a.tenant_id = :tenant ORDER BY a.created_at, a.id")
                .bind("tenant", tenantId).map((row, meta) -> map(row)).all();
    }

    @Override
    public Flux<PovKnowledgeAsset> findAll(String tenantId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        return db.sql(SELECT + " WHERE a.tenant_id = :tenant AND a.id IN (:ids)")
                .bind("tenant", tenantId).bind("ids", List.copyOf(ids)).map((row, meta) -> map(row)).all();
    }

    @Override
    public Flux<Usage> usage(String tenantId) {
        return db.sql("SELECT k.asset_id, k.value_event_id FROM pov_value_event_knowledge k JOIN pov_value_event e ON e.id = k.value_event_id"
                        + " WHERE k.tenant_id = :tenant ORDER BY e.accepted_at, e.created_at, e.id, k.position")
                .bind("tenant", tenantId)
                .map((row, meta) -> new Usage(row.get("asset_id", String.class), row.get("value_event_id", UUID.class))).all();
    }

    private static PovKnowledgeAsset map(io.r2dbc.spi.Row row) {
        String[] parents = row.get("parent_ids", String[].class);
        return new PovKnowledgeAsset(
                row.get("tenant_id", String.class),
                row.get("id", String.class),
                row.get("version", Integer.class),
                KnowledgeAssetKind.valueOf(row.get("kind", String.class)),
                row.get("title", String.class),
                row.get("creator_id", String.class),
                row.get("content_hash", String.class),
                parents == null ? List.of() : Arrays.asList(parents),
                row.get("created_at", Instant.class),
                row.get("display_name", String.class));
    }
}
