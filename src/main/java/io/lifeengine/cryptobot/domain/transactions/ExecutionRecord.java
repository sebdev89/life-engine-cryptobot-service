package io.lifeengine.cryptobot.domain.transactions;

import java.time.Instant;

/**
 * On-chain outcome. {@code signature} is the proof; {@code explorerUrl} is the link a judge clicks.
 *
 * <p>{@code status} is the fine-grained step: {@code SIGNED} (signature known, nothing broadcast
 * yet), {@code SUBMITTED}, {@code EXECUTED}, {@code FAILED}. The signature of a Solana transaction
 * is the first 64 bytes of the signed wire form, so it is known — and persisted — <em>before</em>
 * {@code sendTransaction}: a crash anywhere after signing leaves a row reconciliation can look up.
 * {@code recentBlockhash}/{@code lastValidBlockHeight} are the ones the signed bytes carry; past
 * that height the chain can no longer include the transaction, which is what lets reconciliation
 * declare a never-seen signature {@code FAILED} instead of retrying it.
 */
public record ExecutionRecord(
        String status,
        String signature,
        String explorerUrl,
        String signerPublicKey,
        Instant submittedAt,
        Instant confirmedAt,
        String confirmationStatus,
        String error,
        String recentBlockhash,
        Long lastValidBlockHeight,
        int reconciliationAttempts,
        Instant reconciledAt) {

    public static final String SIGNED = "SIGNED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String EXECUTED = "EXECUTED";
    public static final String FAILED = "FAILED";

    public ExecutionRecord withStatus(String next, Instant confirmedAt, String confirmationStatus, String error) {
        return new ExecutionRecord(next, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts, reconciledAt);
    }

    public ExecutionRecord withSubmitted(Instant submittedAt) {
        return new ExecutionRecord(SUBMITTED, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts, reconciledAt);
    }

    public ExecutionRecord withReconciliationAttempt(Instant at) {
        return new ExecutionRecord(status, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts + 1, at);
    }

    public boolean hasSignature() {
        return signature != null && !signature.isBlank();
    }
}
