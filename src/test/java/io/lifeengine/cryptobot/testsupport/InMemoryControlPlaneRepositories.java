package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AdvisorMessageRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AuditEventRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.PortfolioSnapshotRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.WalletRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public static void reset() {
        WALLETS.clear();
        SNAPSHOTS.clear();
        MESSAGES.clear();
        PROPOSALS.clear();
        AUDIT.clear();
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
            public Mono<Wallet> findByOwnerAndAddress(UUID ownerUserId, String address, SolanaCluster cluster) {
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
                PROPOSALS.put(proposal.id(), proposal);
                return Mono.just(proposal);
            }

            @Override
            public Mono<ActionProposal> update(ActionProposal proposal) {
                PROPOSALS.put(proposal.id(), proposal);
                return Mono.just(proposal);
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
