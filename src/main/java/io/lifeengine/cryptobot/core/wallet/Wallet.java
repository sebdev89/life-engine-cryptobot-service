package io.lifeengine.cryptobot.core.wallet;

import io.lifeengine.cryptobot.core.Network;
import java.time.Instant;
import java.util.UUID;

/**
 * A public address tracked by one user. Never holds a key. KAN-595 (TAE phase 1, audit §20 gap
 * G9): the core no longer imports the Solana adapter to know the network — {@link Network} is the
 * core's own type; {@code SolanaCluster.from(wallet.cluster())} converts at the boundary where an
 * adapter call still needs it.
 */
public record Wallet(
        UUID id,
        UUID ownerUserId,
        String address,
        Network cluster,
        String label,
        Instant createdAt,
        Instant updatedAt) {}
