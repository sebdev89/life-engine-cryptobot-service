package io.lifeengine.cryptobot.adapters.jupiter;

/** Jupiter answered something we did not ask for, or a transaction that fails {@link UnsignedSwapCheck}. */
public class SwapRejectedException extends RuntimeException {

    private final String reason;

    public SwapRejectedException(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    /** Stable machine-readable reason, e.g. {@code fee_payer_mismatch}. */
    public String reason() {
        return reason;
    }
}
