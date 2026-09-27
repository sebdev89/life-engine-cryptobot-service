package io.lifeengine.cryptobot.core.execution;

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
 * declare a never-seen signature dead — and, since KAN-571, retry it <em>idempotently</em>: same
 * {@code operationId}, fresh blockhash, new signature. {@code retries} counts those retries (0 on
 * the first attempt; absent in older documents ⇒ 0) and {@code previousSignature} keeps the last
 * signature that was superseded, so the trail never loses a signature that might have been seen.
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
        Instant reconciledAt,
        int retries,
        String previousSignature) {

    /** Pre-KAN-571 shape: first attempt, no superseded signature. */
    public ExecutionRecord(String status, String signature, String explorerUrl, String signerPublicKey, Instant submittedAt, Instant confirmedAt,
            String confirmationStatus, String error, String recentBlockhash, Long lastValidBlockHeight, int reconciliationAttempts, Instant reconciledAt) {
        this(status, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error, recentBlockhash, lastValidBlockHeight,
                reconciliationAttempts, reconciledAt, 0, null);
    }

    public static final String SIGNED = "SIGNED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String EXECUTED = "EXECUTED";
    public static final String FAILED = "FAILED";

    public ExecutionRecord withStatus(String next, Instant confirmedAt, String confirmationStatus, String error) {
        return new ExecutionRecord(next, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts, reconciledAt, retries, previousSignature);
    }

    public ExecutionRecord withSubmitted(Instant submittedAt) {
        return new ExecutionRecord(SUBMITTED, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts, reconciledAt, retries, previousSignature);
    }

    public ExecutionRecord withReconciliationAttempt(Instant at) {
        return new ExecutionRecord(status, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, reconciliationAttempts + 1, at, retries, previousSignature);
    }

    /** The reconciler set the attempt counter to the ceiling (dead-lettered) or a human reset it (requeue). */
    public ExecutionRecord withReconciliationAttempts(int attempts, String error, Instant at) {
        return new ExecutionRecord(status, signature, explorerUrl, signerPublicKey, submittedAt, confirmedAt, confirmationStatus, error,
                recentBlockhash, lastValidBlockHeight, attempts, at, retries, previousSignature);
    }

    /**
     * KAN-571: a retry supersedes this record. The new record is {@code SIGNED} with the fresh
     * signature/blockhash, {@code retries + 1}, this signature as {@code previousSignature}, and the
     * reconciliation counter back to 0 — the chain is asked again about the new signature.
     */
    public ExecutionRecord retriedWith(String newSignature, String newExplorerUrl, String newSigner, String newBlockhash, Long newLastValidBlockHeight) {
        return new ExecutionRecord(SIGNED, newSignature, newExplorerUrl, newSigner, null, null, null, null, newBlockhash, newLastValidBlockHeight,
                0, null, retries + 1, signature);
    }

    public boolean hasSignature() {
        return signature != null && !signature.isBlank();
    }
}
