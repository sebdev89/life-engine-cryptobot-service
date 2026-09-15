package io.lifeengine.cryptobot.domain.transactions;

import java.time.Instant;

/** Who decided what, when. The whole point of the product is that this row exists. */
public record ApprovalRecord(Decision decision, String by, Instant at, String note) {

    public enum Decision {
        APPROVED,
        REJECTED
    }
}
