package io.lifeengine.cryptobot.domain.transactions;

import java.time.Instant;

/** On-chain outcome. {@code signature} is the proof; {@code explorerUrl} is the link a judge clicks. */
public record ExecutionRecord(
        String status,
        String signature,
        String explorerUrl,
        String signerPublicKey,
        Instant submittedAt,
        Instant confirmedAt,
        String confirmationStatus,
        String error) {}
