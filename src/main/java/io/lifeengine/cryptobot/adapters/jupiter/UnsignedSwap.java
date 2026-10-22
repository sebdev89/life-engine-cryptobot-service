package io.lifeengine.cryptobot.adapters.jupiter;

/**
 * The swap transaction Jupiter built for a quote: a v0 transaction with its signature slot empty, base64. It is only
 * ever simulated. {@code jupiterSimulationError} is what Jupiter's own pre-simulation reported (null when it found
 * none); it is surfaced, not trusted, and never replaces our own simulation.
 */
public record UnsignedSwap(
        String transactionBase64,
        long lastValidBlockHeight,
        long prioritizationFeeLamports,
        long computeUnitLimit,
        String jupiterSimulationError) {}
