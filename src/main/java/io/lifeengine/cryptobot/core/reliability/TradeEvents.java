package io.lifeengine.cryptobot.core.reliability;

/**
 * Event types of the trade lifecycle (Endgame §16/§31). These are the durable, published facts —
 * as opposed to {@code AuditEvent}, which is the internal trace of every step.
 *
 * <ul>
 *   <li>{@link #REQUESTED} — policy let the proposal reach the human ({@code AWAITING_APPROVAL})
 *   <li>{@link #APPROVED} / {@link #REJECTED} / {@link #EXPIRED} — the decision, or the clock's
 *   <li>{@link #CANCELLED} — a human cancelled an APPROVED proposal inside its timelock (paper §19)
 *   <li>{@link #SUBMITTED} — {@code sendTransaction} returned a signature
 *   <li>{@link #CONFIRMED} / {@link #FAILED} — the chain's verdict (synchronous or reconciled)
 *   <li>{@link #RECEIPT_CREATED} — reserved for the Decision Receipt; nobody emits it yet
 * </ul>
 */
public final class TradeEvents {

    private TradeEvents() {}

    public static final String REQUESTED = "trade.requested";
    public static final String APPROVED = "trade.approved";
    public static final String REJECTED = "trade.rejected";
    public static final String EXPIRED = "trade.expired";
    public static final String CANCELLED = "trade.cancelled";
    public static final String SUBMITTED = "trade.submitted";
    public static final String CONFIRMED = "trade.confirmed";
    public static final String FAILED = "trade.failed";
    public static final String RECEIPT_CREATED = "receipt.created";
}
