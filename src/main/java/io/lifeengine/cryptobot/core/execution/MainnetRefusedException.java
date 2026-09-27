package io.lifeengine.cryptobot.core.execution;

import io.lifeengine.cryptobot.core.Network;

/**
 * The core-level twin of the Solana adapter's own mainnet-disabled exception: a {@link
 * io.lifeengine.cryptobot.core.ports.ChainExecutionPort} refused {@code submit} because
 * {@link NetworkPolicy} does not allow {@link Network#MAINNET_BETA}. Raised before the concrete
 * adapter is even called — nothing about the transaction reached the chain.
 */
public class MainnetRefusedException extends RuntimeException {

    public static final String CODE = "MAINNET_DISABLED";

    private final Network network;

    public MainnetRefusedException(String where, Network network) {
        super(where + ": execution on " + network.id() + " is disabled (cryptobot.execution.allow-mainnet=false"
                + " / CRYPTOBOT_ALLOW_MAINNET); mainnet is fail-closed");
        this.network = network;
    }

    public String code() {
        return CODE;
    }

    public Network network() {
        return network;
    }
}
