package io.lifeengine.cryptobot.core;

import java.util.Locale;

/**
 * The networks the trusted execution core knows about, independent of Solana. an internal ticket (TAE phase
 * 1, audit §20/§21): the core must not import an adapter type to know which network a wallet or a
 * proposal is on. {@code io.lifeengine.cryptobot.solana.rpc.SolanaCluster} is the Solana adapter's
 * own representation and converts to/from this one at the boundary
 * ({@code SolanaCluster.from(Network)} / {@code SolanaCluster#toNetwork()}); mainnet stays refused
 * by default in every layer regardless of which of the two types a check reads (audit §6).
 */
public enum Network {
    DEVNET("devnet"),
    MAINNET_BETA("mainnet-beta");

    private final String id;

    Network(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Accepts {@code devnet}, {@code mainnet}, {@code mainnet-beta} (case-insensitive). */
    public static Network parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEVNET;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "devnet" -> DEVNET;
            case "mainnet", "mainnet-beta" -> MAINNET_BETA;
            default -> throw new IllegalArgumentException("Unknown network: " + raw);
        };
    }
}
