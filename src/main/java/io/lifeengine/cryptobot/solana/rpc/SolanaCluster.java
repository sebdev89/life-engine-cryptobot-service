package io.lifeengine.cryptobot.solana.rpc;

import io.lifeengine.cryptobot.core.Network;
import java.util.Locale;

/**
 * The Solana clusters CryptoBot knows how to read. Execution is only ever allowed on devnet.
 * Adapts {@link Network} (the core's own, adapter-independent representation) at the
 * boundary: {@link #toNetwork()} / {@link #from(Network)}.
 */
public enum SolanaCluster {
    DEVNET("devnet"),
    MAINNET_BETA("mainnet-beta");

    private final String id;

    SolanaCluster(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Accepts {@code devnet}, {@code mainnet}, {@code mainnet-beta} (case-insensitive). */
    public static SolanaCluster parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEVNET;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "devnet" -> DEVNET;
            case "mainnet", "mainnet-beta" -> MAINNET_BETA;
            default -> throw new IllegalArgumentException("Unknown Solana cluster: " + raw);
        };
    }

    public Network toNetwork() {
        return this == DEVNET ? Network.DEVNET : Network.MAINNET_BETA;
    }

    public static SolanaCluster from(Network network) {
        return network == Network.DEVNET ? DEVNET : MAINNET_BETA;
    }

    public String explorerTxUrl(String signature) {
        String suffix = this == DEVNET ? "?cluster=devnet" : "";
        return "https://explorer.solana.com/tx/" + signature + suffix;
    }

    public String explorerAddressUrl(String address) {
        String suffix = this == DEVNET ? "?cluster=devnet" : "";
        return "https://explorer.solana.com/address/" + address + suffix;
    }
}
