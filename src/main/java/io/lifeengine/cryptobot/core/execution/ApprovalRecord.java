package io.lifeengine.cryptobot.core.execution;

import java.time.Instant;

/**
 * Who decided what, when. The whole point of the product is that this row exists.
 *
 * <p>{@code executableAt} (KAN-438, paper §19): the end of the timelock that starts at approval.
 * Before it the proposal can be cancelled by a human and cannot be executed; {@code null} on rows
 * approved before KAN-438 (the engine derives the lock from {@code at} in that case).
 */
public record ApprovalRecord(Decision decision, String by, Instant at, String note, Instant executableAt) {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ApprovalRecord {}

    /** Pre-KAN-438 shape. */
    public ApprovalRecord(Decision decision, String by, Instant at, String note) {
        this(decision, by, at, note, null);
    }
}
