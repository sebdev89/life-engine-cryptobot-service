package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AnchorRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Same contract as {@code AnchorR2dbcStore} (KAN-394) over {@link InMemoryControlPlaneRepositories#RECEIPTS}:
 * a root is inserted once, members are written with it, receipts are stamped only from a FINALIZED anchor.
 */
public class InMemoryAnchorRepository implements AnchorRepository {

    public static final Map<String, ReceiptAnchor> ANCHORS = new ConcurrentHashMap<>();
    public static final List<ReceiptAnchor.Member> MEMBERS = new CopyOnWriteArrayList<>();

    public static void reset() {
        ANCHORS.clear();
        MEMBERS.clear();
    }

    @Override
    public Flux<String> unanchoredReceiptHashes(int limit) {
        return Flux.fromIterable(InMemoryControlPlaneRepositories.RECEIPTS.values())
                .filter(r -> r.anchor() == null || r.anchor().tx() == null)
                .filter(r -> MEMBERS.stream().noneMatch(m -> m.receiptHash().equals(r.receiptHash())
                        && ANCHORS.containsKey(m.root()) && ANCHORS.get(m.root()).status() != ReceiptAnchor.Status.ABANDONED))
                .sort(Comparator.comparing(IntelligenceReceipt::createdAt).thenComparing(IntelligenceReceipt::receiptHash))
                .map(IntelligenceReceipt::receiptHash)
                .take(limit);
    }

    @Override
    public Mono<Long> countUnanchored() {
        return Mono.just(InMemoryControlPlaneRepositories.RECEIPTS.values().stream().filter(r -> r.anchor() == null || r.anchor().tx() == null).count());
    }

    @Override
    public Mono<ReceiptAnchor> insert(ReceiptAnchor anchor, List<ReceiptAnchor.Member> members) {
        return Mono.fromSupplier(() -> {
            synchronized (ANCHORS) {
                ReceiptAnchor existing = ANCHORS.putIfAbsent(anchor.root(), anchor);
                if (existing != null) {
                    return existing;
                }
                for (ReceiptAnchor.Member m : members) {
                    if (!InMemoryControlPlaneRepositories.RECEIPTS.containsKey(m.receiptHash())) {
                        throw new IllegalStateException("FK: receipt does not exist " + m.receiptHash());
                    }
                    if (MEMBERS.stream().noneMatch(x -> x.root().equals(m.root()) && x.receiptHash().equals(m.receiptHash()))) {
                        MEMBERS.add(m);
                    }
                }
                return anchor;
            }
        });
    }

    @Override
    public Mono<ReceiptAnchor> update(ReceiptAnchor anchor) {
        return Mono.fromSupplier(() -> {
            ANCHORS.put(anchor.root(), anchor);
            return anchor;
        });
    }

    @Override
    public Mono<ReceiptAnchor> findByRoot(String root) {
        return Mono.justOrEmpty(ANCHORS.get(root));
    }

    @Override
    public Flux<ReceiptAnchor> findByStatus(ReceiptAnchor.Status status, int limit) {
        return Flux.fromIterable(new ArrayList<>(ANCHORS.values())).filter(a -> a.status() == status)
                .sort(Comparator.comparing(ReceiptAnchor::updatedAt)).take(limit);
    }

    @Override
    public Flux<ReceiptAnchor> findRecent(int limit) {
        return Flux.fromIterable(new ArrayList<>(ANCHORS.values())).sort(Comparator.comparing(ReceiptAnchor::createdAt).reversed()).take(limit);
    }

    @Override
    public Flux<ReceiptAnchor.Member> members(String root) {
        return Flux.fromIterable(new ArrayList<>(MEMBERS)).filter(m -> m.root().equals(root)).sort(Comparator.comparing(ReceiptAnchor.Member::receiptHash));
    }

    @Override
    public Flux<ReceiptAnchor.Member> membersOwnedBy(String root, UUID ownerId) {
        return members(root).filter(m -> {
            IntelligenceReceipt r = InMemoryControlPlaneRepositories.RECEIPTS.get(m.receiptHash());
            return r != null && r.body().ownerId().equals(ownerId.toString());
        });
    }

    @Override
    public Mono<ReceiptAnchor.Member> membershipOf(String receiptHash) {
        return Flux.fromIterable(new ArrayList<>(MEMBERS)).filter(m -> m.receiptHash().equals(receiptHash))
                .filter(m -> ANCHORS.containsKey(m.root()) && ANCHORS.get(m.root()).status() != ReceiptAnchor.Status.ABANDONED)
                .sort(Comparator.comparing((ReceiptAnchor.Member m) -> ANCHORS.get(m.root()).status() == ReceiptAnchor.Status.FINALIZED ? 0 : 1)
                        .thenComparing(m -> ANCHORS.get(m.root()).updatedAt(), Comparator.reverseOrder()))
                .next();
    }

    @Override
    public Mono<Long> stampReceipts(ReceiptAnchor anchor) {
        if (anchor.status() != ReceiptAnchor.Status.FINALIZED || anchor.tx() == null) {
            return Mono.error(new IllegalStateException("Only a FINALIZED anchor stamps receipts"));
        }
        return Mono.fromSupplier(() -> {
            long n = 0;
            for (ReceiptAnchor.Member m : MEMBERS) {
                if (!m.root().equals(anchor.root())) {
                    continue;
                }
                IntelligenceReceipt r = InMemoryControlPlaneRepositories.RECEIPTS.get(m.receiptHash());
                if (r != null) {
                    InMemoryControlPlaneRepositories.RECEIPTS.put(r.receiptHash(), r.withAnchor(anchor.anchorFor(m)));
                    n++;
                }
            }
            return n;
        });
    }
}
