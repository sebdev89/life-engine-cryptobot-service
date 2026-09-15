package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Registers public addresses per user. Idempotent on (owner, address, cluster). */
@Service
public class WalletService {

    private final WalletRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public WalletService(WalletRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
        this.clock = Clock.systemUTC();
    }

    public Mono<Wallet> register(UUID ownerUserId, String actor, String rawAddress, String rawCluster, String label) {
        String address = rawAddress == null ? "" : rawAddress.trim();
        if (!Base58.isPublicKey(address)) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_ADDRESS", "Not a Solana public key: " + address));
        }
        SolanaCluster cluster;
        try {
            cluster = SolanaCluster.parse(rawCluster);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_CLUSTER", ex.getMessage()));
        }
        return repository
                .findByOwnerAndAddress(ownerUserId, address, cluster)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    Instant now = clock.instant();
                                    Wallet wallet = new Wallet(UUID.randomUUID(), ownerUserId, address, cluster, blankToNull(label), now, now);
                                    return repository
                                            .insert(wallet)
                                            .flatMap(
                                                    saved ->
                                                            audit.record(
                                                                            ownerUserId,
                                                                            saved.id(),
                                                                            null,
                                                                            "WALLET_REGISTERED",
                                                                            actor,
                                                                            Map.of("address", address, "cluster", cluster.id()))
                                                                    .thenReturn(saved));
                                }));
    }

    public Mono<Wallet> require(UUID ownerUserId, UUID walletId) {
        return repository.findByIdAndOwner(walletId, ownerUserId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Wallet " + walletId)));
    }

    public Flux<Wallet> list(UUID ownerUserId) {
        return repository.findByOwner(ownerUserId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
