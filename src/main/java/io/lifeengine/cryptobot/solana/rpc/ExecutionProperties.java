package io.lifeengine.cryptobot.solana.rpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-493 — the explicit mainnet switch. Mainnet is fail-closed: with {@code allowMainnet=false}
 * (the default, env {@code CRYPTOBOT_ALLOW_MAINNET}) no mainnet transaction is executed or
 * broadcast, whatever {@code cryptobot.policy.execution-cluster} says. The same flag is read at
 * {@code ExecutionService.execute} and at {@code SolanaRpcClient.sendTransaction}; the signer has
 * its own ({@code SIGNER_ALLOW_MAINNET}). Flipping it is not enough on its own: mainnet stays
 * closed until the readiness gate exists.
 */
@ConfigurationProperties(prefix = "cryptobot.execution")
public record ExecutionProperties(boolean allowMainnet) {

    /** Everything a flag can never open: {@code false} for mainnet, unchanged for the rest. */
    public static ExecutionProperties failClosed() {
        return new ExecutionProperties(false);
    }

    /** Whether a write to {@code cluster} may proceed. Devnet always; mainnet only with the flag. */
    public boolean permits(SolanaCluster cluster) {
        return cluster != SolanaCluster.MAINNET_BETA || allowMainnet;
    }
}
