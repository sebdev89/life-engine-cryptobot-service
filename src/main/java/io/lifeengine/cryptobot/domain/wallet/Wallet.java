package io.lifeengine.cryptobot.domain.wallet;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import java.time.Instant;
import java.util.UUID;

/** A public Solana address tracked by one user. Never holds a key. */
public record Wallet(
        UUID id,
        UUID ownerUserId,
        String address,
        SolanaCluster cluster,
        String label,
        Instant createdAt,
        Instant updatedAt) {}
