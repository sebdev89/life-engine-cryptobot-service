package io.lifeengine.cryptobot.core.execution;

import io.lifeengine.cryptobot.core.Network;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-596 (TAE phase 1, gap G9) — the core's own copy of the mainnet fail-closed rule (KAN-493),
 * independent of the Solana adapter's {@code ExecutionProperties}/{@code SolanaCluster}. Reads the
 * same flag ({@code cryptobot.execution.allow-mainnet} / {@code CRYPTOBOT_ALLOW_MAINNET}) so the
 * two never disagree, but core does not import the adapter's type to know it: a {@link
 * io.lifeengine.cryptobot.core.ports.ChainExecutionPort} adapter checks this <em>before</em> its
 * own gate (defense in depth, audit §6) — "el gate de sendTransaction se conservan dentro del
 * adapter (gate #2) y además como regla del core (network/mainnetEnabled)".
 */
@ConfigurationProperties(prefix = "cryptobot.execution")
public record NetworkPolicy(boolean allowMainnet) {

    /** Everything a flag can never open: {@code false} for mainnet, whatever the config says. */
    public static NetworkPolicy failClosed() {
        return new NetworkPolicy(false);
    }

    /** Whether a write to {@code network} may proceed. Devnet always; mainnet only with the flag. */
    public boolean permits(Network network) {
        return network != Network.MAINNET_BETA || allowMainnet;
    }
}
