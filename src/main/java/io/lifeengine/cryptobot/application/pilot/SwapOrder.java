package io.lifeengine.cryptobot.application.pilot;

import java.time.Instant;
import java.util.UUID;

/**
 * One pilot swap (KAN-1249): proposed with a simulated preview, approved by the configured human, executed. What the
 * approver saw is bound to the order — direction, cluster, owner, input amount, minimum output, the preview and its
 * hash — so the approval of one swap can never be spent on another.
 */
public record SwapOrder(
        UUID id,
        String idempotencyKey,
        UUID createdBy,
        Status status,
        Direction direction,
        String cluster,
        String owner,
        long inAmount,
        long minOut,
        int maxSlippageBps,
        String preview,
        String previewHash,
        String approvalNonce,
        UUID approvedBy,
        Instant approvedAt,
        String approvalJti,
        Instant expiresAt,
        String txSignature,
        String error,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { PENDING_APPROVAL, APPROVED, EXECUTING, SENT, CONFIRMED, UNKNOWN_SENT, FAILED, REJECTED, EXPIRED }

    /** SOL→USDC spends lamports and receives USDC units; USDC→SOL the other way round. */
    public enum Direction {
        SOL_USDC("So11111111111111111111111111111111111111112", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"),
        USDC_SOL("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "So11111111111111111111111111111111111111112");

        private final String inputMint;
        private final String outputMint;

        Direction(String inputMint, String outputMint) {
            this.inputMint = inputMint;
            this.outputMint = outputMint;
        }

        public String inputMint() {
            return inputMint;
        }

        public String outputMint() {
            return outputMint;
        }
    }
}
