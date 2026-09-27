package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.core.Network;
import io.lifeengine.cryptobot.trading.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptArtifact;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.AuditEvent;
import io.lifeengine.cryptobot.core.execution.ProposalTransition;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AdvisorMessageRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AuditEventRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.OutboxRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.PortfolioSnapshotRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ReceiptRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.WalletRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Real in-memory implementations (not mocks) so the state machine and owner scoping are
 * exercised end-to-end in the test slice. Call {@link #reset()} between tests.
 */
public final class InMemoryControlPlaneRepositories {

    private InMemoryControlPlaneRepositories() {}

    public static final Map<UUID, Wallet> WALLETS = new ConcurrentHashMap<>();
    public static final List<PortfolioSnapshot> SNAPSHOTS = new CopyOnWriteArrayList<>();
    public static final List<AdvisorMessage> MESSAGES = new CopyOnWriteArrayList<>();
    public static final Map<UUID, ActionProposal> PROPOSALS = new ConcurrentHashMap<>();
    public static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
    public static final Map<UUID, OutboxEvent> OUTBOX = new ConcurrentHashMap<>();
    public static final List<DeadLetter> DEAD_LETTERS = new CopyOnWriteArrayList<>();
    public static final Map<String, IntelligenceReceipt> RECEIPTS = new ConcurrentHashMap<>();
    public static final List<ReceiptEdge> EDGES = new CopyOnWriteArrayList<>();
    public static final List<ReceiptArtifact> ARTIFACTS = new CopyOnWriteArrayList<>();
    public static final Map<String, DeterministicInference> INFERENCES = new ConcurrentHashMap<>();

    public static void reset() {
        WALLETS.clear();
        SNAPSHOTS.clear();
        MESSAGES.clear();
        PROPOSALS.clear();
        AUDIT.clear();
        OUTBOX.clear();
        DEAD_LETTERS.clear();
        RECEIPTS.clear();
        EDGES.clear();
        ARTIFACTS.clear();
        INFERENCES.clear();
        InMemoryAnchorRepository.reset();
    }

    /** Receipts of one proposal, oldest first — the pipeline as the DAG API would return it. */
    public static List<IntelligenceReceipt> receiptsOf(UUID proposalId) {
        return RECEIPTS.values().stream()
                .filter(r -> r.body().refs() != null && proposalId.toString().equals(r.body().refs().proposalId()))
                .sorted(Comparator.comparing(IntelligenceReceipt::createdAt).thenComparing(IntelligenceReceipt::receiptHash)).toList();
    }

    /** Outbox events of one proposal, oldest first — what the tests assert on. */
    public static List<OutboxEvent> outboxOf(UUID proposalId) {
        return OUTBOX.values().stream().filter(e -> e.aggregateId().equals(proposalId))
                .sorted(Comparator.comparing(OutboxEvent::createdAt)).toList();
    }

    public static WalletRepository wallets() {
        return new WalletRepository() {
            @Override
            public Mono<Wallet> insert(Wallet wallet) {
                WALLETS.put(wallet.id(), wallet);
                return Mono.just(wallet);
            }

            @Override
            public Mono<Wallet> findByIdAndOwner(UUID id, UUID ownerUserId) {
                Wallet w = WALLETS.get(id);
                return w == null || !w.ownerUserId().equals(ownerUserId) ? Mono.empty() : Mono.just(w);
            }

            @Override
            public Mono<Wallet> findByOwnerAndAddress(UUID ownerUserId, String address, Network cluster) {
                return Flux.fromIterable(WALLETS.values())
                        .filter(w -> w.ownerUserId().equals(ownerUserId) && w.address().equals(address) && w.cluster() == cluster)
                        .next();
            }

            @Override
            public Flux<Wallet> findByOwner(UUID ownerUserId) {
                return Flux.fromIterable(WALLETS.values()).filter(w -> w.ownerUserId().equals(ownerUserId));
            }
        };
    }

    public static PortfolioSnapshotRepository snapshots() {
        return new PortfolioSnapshotRepository() {
            @Override
            public Mono<PortfolioSnapshot> insert(PortfolioSnapshot snapshot) {
                SNAPSHOTS.add(snapshot);
                return Mono.just(snapshot);
            }

            @Override
            public Mono<PortfolioSnapshot> findById(UUID id) {
                return Flux.fromIterable(SNAPSHOTS).filter(s -> s.id().equals(id)).next();
            }

            @Override
            public Flux<PortfolioSnapshot> findRecent(UUID walletId, int limit) {
                return Flux.fromIterable(SNAPSHOTS)
                        .filter(s -> s.walletId().equals(walletId))
                        .sort(Comparator.comparing(PortfolioSnapshot::capturedAt).reversed())
                        .take(limit);
            }
        };
    }

    public static AdvisorMessageRepository messages() {
        return new AdvisorMessageRepository() {
            @Override
            public Mono<AdvisorMessage> insert(AdvisorMessage message) {
                MESSAGES.add(message);
                return Mono.just(message);
            }

            @Override
            public Flux<AdvisorMessage> findByWallet(UUID walletId, int limit) {
                return Flux.fromIterable(MESSAGES).filter(m -> m.walletId().equals(walletId)).takeLast(limit);
            }
        };
    }

    public static ActionProposalRepository proposals() {
        return new ActionProposalRepository() {
            @Override
            public Mono<ActionProposal> insert(ActionProposal proposal) {
                ActionProposal stored = proposal.withVersion(0);
                PROPOSALS.put(stored.id(), stored);
                return Mono.just(stored);
            }

            /**
             * Same contract as the R2DBC store (KAN-403): the guard on status + version is
             * checked atomically; audit and outbox rows are written only when it passes; a
             * duplicated operationId is refused.
             */
            @Override
            public Mono<ActionProposal> commit(ProposalTransition t) {
                return Mono.defer(() -> {
                    ActionProposal p = t.proposal();
                    synchronized (PROPOSALS) {
                        ActionProposal current = PROPOSALS.get(p.id());
                        if (current == null || !current.ownerUserId().equals(p.ownerUserId())
                                || current.status() != t.expectedStatus() || current.version() != t.expectedVersion()) {
                            return Mono.error(new ControlPlaneExceptions.StaleProposal(p.id(), t.expectedStatus() + " v" + t.expectedVersion()));
                        }
                        if (p.operationId() != null && PROPOSALS.values().stream()
                                .anyMatch(o -> !o.id().equals(p.id()) && p.operationId().equals(o.operationId()))) {
                            return Mono.error(new ControlPlaneExceptions.DuplicateOperation(p.operationId()));
                        }
                        ActionProposal stored = p.withVersion(t.expectedVersion() + 1);
                        PROPOSALS.put(stored.id(), stored);
                        AUDIT.addAll(t.audit());
                        t.outbox().forEach(e -> OUTBOX.put(e.id(), e));
                        return Mono.just(stored);
                    }
                });
            }

            @Override
            public Mono<ActionProposal> findByIdAndOwner(UUID id, UUID ownerUserId) {
                ActionProposal p = PROPOSALS.get(id);
                return p == null || !p.ownerUserId().equals(ownerUserId) ? Mono.empty() : Mono.just(p);
            }

            @Override
            public Flux<ActionProposal> findByWallet(UUID walletId, int limit) {
                return Flux.fromIterable(PROPOSALS.values()).filter(p -> p.walletId().equals(walletId))
                        .sort(Comparator.comparing(ActionProposal::createdAt).reversed()).take(limit);
            }

            @Override
            public Flux<ActionProposal> findByOwner(UUID ownerUserId, int limit) {
                return Flux.fromIterable(PROPOSALS.values()).filter(p -> p.ownerUserId().equals(ownerUserId))
                        .sort(Comparator.comparing(ActionProposal::createdAt).reversed()).take(limit);
            }

            @Override
            public Flux<ActionProposal> findInFlight(Instant updatedBefore, int limit) {
                return Flux.fromIterable(PROPOSALS.values())
                        .filter(p -> p.status().inFlight() && p.updatedAt().isBefore(updatedBefore))
                        .sort(Comparator.comparing(ActionProposal::updatedAt)).take(limit);
            }
        };
    }

    public static OutboxRepository outbox() {
        return new OutboxRepository() {
            @Override
            public Mono<Long> processDue(Instant now, int batchSize, Function<OutboxEvent, Mono<Outcome>> work) {
                List<OutboxEvent> due = OUTBOX.values().stream()
                        .filter(e -> e.status() == OutboxEvent.Status.PENDING && !e.nextAttemptAt().isAfter(now))
                        .sorted(Comparator.comparing(OutboxEvent::nextAttemptAt).thenComparing(OutboxEvent::createdAt))
                        .limit(batchSize).toList();
                return Flux.fromIterable(due)
                        .concatMap(e -> work.apply(e).doOnNext(outcome -> {
                            if (outcome instanceof Outcome.Published p) {
                                OUTBOX.put(e.id(), e.published(p.at()));
                            } else if (outcome instanceof Outcome.Retry r) {
                                OUTBOX.put(e.id(), e.retryLater(r.nextAttemptAt(), r.error()));
                            } else if (outcome instanceof Outcome.Dead d) {
                                OUTBOX.put(e.id(), e.failed(d.error()));
                                DEAD_LETTERS.add(d.letter());
                            }
                        }))
                        .count();
            }

            @Override
            public Flux<OutboxEvent> findByAggregate(UUID aggregateId) {
                return Flux.fromIterable(outboxOf(aggregateId));
            }

            @Override
            public Mono<Long> countByStatus(OutboxEvent.Status status) {
                return Mono.just(OUTBOX.values().stream().filter(e -> e.status() == status).count());
            }

            @Override
            public Mono<OutboxEvent> findById(UUID id) {
                return Mono.justOrEmpty(OUTBOX.get(id));
            }

            @Override
            public Mono<OutboxEvent> requeue(UUID id, java.time.Instant now) {
                OutboxEvent e = OUTBOX.get(id);
                if (e == null || e.status() != OutboxEvent.Status.FAILED) {
                    return Mono.empty();
                }
                OutboxEvent again = e.requeued(now);
                OUTBOX.put(id, again);
                return Mono.just(again);
            }
        };
    }

    public static DeadLetterRepository deadLetters() {
        return new DeadLetterRepository() {
            @Override
            public Mono<DeadLetter> append(DeadLetter letter) {
                DEAD_LETTERS.add(letter);
                return Mono.just(letter);
            }

            @Override
            public Flux<DeadLetter> findByProposal(UUID proposalId) {
                return Flux.fromIterable(new ArrayList<>(DEAD_LETTERS)).filter(d -> proposalId.equals(d.proposalId()));
            }

            @Override
            public Mono<Long> countUnresolved() {
                return Mono.just(DEAD_LETTERS.stream().filter(d -> d.resolvedAt() == null).count());
            }

            @Override
            public Mono<DeadLetter> findById(UUID id) {
                return Mono.justOrEmpty(DEAD_LETTERS.stream().filter(d -> d.id().equals(id)).findFirst());
            }

            @Override
            public Flux<DeadLetter> findAll(Boolean resolved, DeadLetter.Source source, UUID proposalId, int limit, int offset) {
                return Flux.fromIterable(new ArrayList<>(DEAD_LETTERS))
                        .filter(d -> resolved == null || resolved == (d.resolvedAt() != null))
                        .filter(d -> source == null || d.source() == source)
                        .filter(d -> proposalId == null || proposalId.equals(d.proposalId()))
                        .sort(Comparator.comparing(DeadLetter::createdAt).reversed())
                        .skip(Math.max(0, offset))
                        .take(Math.max(1, limit));
            }

            @Override
            public Mono<DeadLetter> resolve(UUID id, java.time.Instant at, String by, String note, DeadLetter.Outcome outcome) {
                synchronized (DEAD_LETTERS) {
                    for (int i = 0; i < DEAD_LETTERS.size(); i++) {
                        DeadLetter d = DEAD_LETTERS.get(i);
                        if (d.id().equals(id)) {
                            if (d.resolvedAt() != null) {
                                return Mono.empty();
                            }
                            DeadLetter done = d.resolved(at, by, note, outcome);
                            DEAD_LETTERS.set(i, done);
                            return Mono.just(done);
                        }
                    }
                }
                return Mono.empty();
            }
        };
    }

    /**
     * Same contract as the R2DBC store (KAN-391): content-addressed no-op on the same hash, a
     * replayed {@code (tenant, nonce)} with another hash is refused, an edge to an unknown parent is
     * refused (the FK), artifacts collapse on their hash.
     */
    public static ReceiptRepository receipts() {
        return new ReceiptRepository() {
            @Override
            public Mono<IntelligenceReceipt> insert(IntelligenceReceipt receipt, List<ReceiptEdge> edges, List<ReceiptArtifact> artifacts,
                    DeterministicInference inference) {
                return Mono.defer(() -> {
                    synchronized (RECEIPTS) {
                        IntelligenceReceipt existing = RECEIPTS.get(receipt.receiptHash());
                        if (existing != null) {
                            return Mono.just(existing);
                        }
                        boolean replay = RECEIPTS.values().stream().anyMatch(r -> r.body().tenantId().equals(receipt.body().tenantId())
                                && r.body().nonce().equals(receipt.body().nonce()));
                        boolean dangling = edges.stream().anyMatch(e -> !RECEIPTS.containsKey(e.parentHash()));
                        if (replay || dangling) {
                            return Mono.error(new ControlPlaneExceptions.Conflict("Receipt refused: nonce already used or a parent does not exist (" + receipt.receiptHash() + ")"));
                        }
                        RECEIPTS.put(receipt.receiptHash(), receipt);
                        EDGES.addAll(edges);
                        for (ReceiptArtifact a : artifacts) {
                            if (ARTIFACTS.stream().noneMatch(x -> x.artifactHash().equals(a.artifactHash()))) {
                                ARTIFACTS.add(a);
                            }
                        }
                        if (inference != null) {
                            INFERENCES.put(receipt.receiptHash(), inference.bound(receipt.receiptHash()));
                        }
                        return Mono.just(receipt);
                    }
                });
            }

            @Override
            public Mono<IntelligenceReceipt> findByHash(String receiptHash) {
                return Mono.justOrEmpty(RECEIPTS.get(receiptHash));
            }

            @Override
            public Mono<DeterministicInference> findInference(String receiptHash) {
                return Mono.justOrEmpty(INFERENCES.get(receiptHash));
            }

            @Override
            public Mono<IntelligenceReceipt> findByHashAndOwner(String receiptHash, UUID ownerId) {
                IntelligenceReceipt r = RECEIPTS.get(receiptHash);
                return r == null || !r.body().ownerId().equals(ownerId.toString()) ? Mono.empty() : Mono.just(r);
            }

            @Override
            public Flux<String> existingInTenant(List<String> hashes, String tenantId) {
                return Flux.fromIterable(hashes).filter(h -> RECEIPTS.containsKey(h) && RECEIPTS.get(h).body().tenantId().equals(tenantId));
            }

            @Override
            public Mono<IntelligenceReceipt> findByNonce(String tenantId, String nonce) {
                return Flux.fromIterable(RECEIPTS.values())
                        .filter(r -> r.body().tenantId().equals(tenantId) && r.body().nonce().equals(nonce)).next();
            }

            @Override
            public Flux<IntelligenceReceipt> findByWallet(UUID walletId, int limit) {
                return Flux.fromIterable(RECEIPTS.values())
                        .filter(r -> r.body().refs() != null && walletId.toString().equals(r.body().refs().walletId()))
                        .sort(Comparator.comparing(IntelligenceReceipt::createdAt).reversed()).take(limit);
            }

            @Override
            public Flux<IntelligenceReceipt> findByProposal(UUID proposalId) {
                return Flux.fromIterable(receiptsOf(proposalId));
            }

            @Override
            public Mono<IntelligenceReceipt> findLatestByWalletAndKind(UUID walletId, ReceiptKind kind) {
                return findByWallet(walletId, Integer.MAX_VALUE).filter(r -> r.kind() == kind).next();
            }

            @Override
            public Flux<ReceiptEdge> parentsOf(String childHash) {
                return Flux.fromIterable(new ArrayList<>(EDGES)).filter(e -> e.childHash().equals(childHash));
            }

            @Override
            public Flux<ReceiptEdge> childrenOf(String parentHash) {
                return Flux.fromIterable(new ArrayList<>(EDGES)).filter(e -> e.parentHash().equals(parentHash));
            }
        };
    }

    public static AuditEventRepository audit() {
        return new AuditEventRepository() {
            @Override
            public Mono<AuditEvent> append(AuditEvent event) {
                AUDIT.add(event);
                return Mono.just(event);
            }

            @Override
            public Flux<AuditEvent> findByProposal(UUID proposalId) {
                return Flux.fromIterable(AUDIT).filter(e -> proposalId.equals(e.proposalId()));
            }

            @Override
            public Flux<AuditEvent> findByWallet(UUID walletId, int limit) {
                return Flux.fromIterable(AUDIT).filter(e -> walletId.equals(e.walletId())).takeLast(limit);
            }
        };
    }
}
