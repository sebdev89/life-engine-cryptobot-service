package io.lifeengine.cryptobot.core.execution;

/** The unsigned transaction attached to a proposal. Base64 wire bytes with zeroed signatures. */
public record PreparedTransaction(
        String cluster,
        String feePayer,
        String destination,
        long lamports,
        String recentBlockhash,
        long lastValidBlockHeight,
        String unsignedTransactionBase64,
        String messageBase64,
        String instructionSummary) {}
