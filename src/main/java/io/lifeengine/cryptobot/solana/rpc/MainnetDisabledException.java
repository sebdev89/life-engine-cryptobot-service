package io.lifeengine.cryptobot.solana.rpc;

/**
 * KAN-493 — a write to mainnet was attempted while {@code cryptobot.execution.allow-mainnet} is
 * false. Raised before anything is signed, sent or persisted; mapped to {@code 409
 * MAINNET_DISABLED} at the API. Nothing about the transaction reached the chain.
 */
public class MainnetDisabledException extends RuntimeException {

    public static final String CODE = "MAINNET_DISABLED";

    private final SolanaCluster cluster;

    /** @param where the guarded step, e.g. {@code execute} or {@code sendTransaction}. */
    public MainnetDisabledException(String where, SolanaCluster cluster) {
        super(where + ": execution on " + cluster.id() + " is disabled (cryptobot.execution.allow-mainnet=false / CRYPTOBOT_ALLOW_MAINNET); mainnet is fail-closed");
        this.cluster = cluster;
    }

    public String code() {
        return CODE;
    }

    public SolanaCluster cluster() {
        return cluster;
    }
}
