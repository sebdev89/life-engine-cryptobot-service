package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ValueEventRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Same contract as {@code ValueEventR2dbcStore} (KAN-818): content-addressed, owner-scoped, append-only. */
public class InMemoryValueEventRepository implements ValueEventRepository {

    public static final Map<String, Row> EVENTS = new ConcurrentHashMap<>();

    public static void reset() {
        EVENTS.clear();
    }

    @Override
    public Mono<Row> insert(Row row) {
        return Mono.fromSupplier(() -> {
            Row existing = EVENTS.putIfAbsent(row.valueEventHash(), row);
            return existing != null ? existing : row;
        });
    }

    @Override
    public Mono<Row> findByHashAndOwner(String valueEventHash, UUID ownerId) {
        return Mono.justOrEmpty(EVENTS.get(valueEventHash)).filter(r -> r.ownerId().equals(ownerId));
    }

    @Override
    public Flux<Row> findByOwner(UUID ownerId, int limit) {
        return Flux.fromIterable(EVENTS.values()).filter(r -> r.ownerId().equals(ownerId))
                .sort(Comparator.comparing(Row::occurredAt).reversed().thenComparing(Row::valueEventHash)).take(limit);
    }

    @Override
    public Flux<Row> findByEvidence(UUID ownerId, String evidenceHash) {
        return findByOwner(ownerId, 500).filter(r -> r.evidenceHash().equals(evidenceHash));
    }
}
