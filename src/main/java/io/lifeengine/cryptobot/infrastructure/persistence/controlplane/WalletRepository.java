package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Wallets are always looked up with the owner: a wallet id from another user is "not found". */
public interface WalletRepository {
    Mono<Wallet> insert(Wallet wallet);

    Mono<Wallet> findByIdAndOwner(UUID id, UUID ownerUserId);

    Mono<Wallet> findByOwnerAndAddress(UUID ownerUserId, String address, SolanaCluster cluster);

    Flux<Wallet> findByOwner(UUID ownerUserId);
}
