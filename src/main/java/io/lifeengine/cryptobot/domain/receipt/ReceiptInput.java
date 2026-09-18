package io.lifeengine.cryptobot.domain.receipt;

import java.util.Locale;

/**
 * One thing a step looked at, by hash. {@code type} names what the hash is of (a receipt, a market
 * snapshot, the user's text as a salted commitment, a policy verdict…) so a verifier knows how to
 * recompute it; it never carries the thing itself.
 */
public record ReceiptInput(String type, String hash) {

    public static final String RECEIPT = "RECEIPT";
    public static final String USER_TEXT = "USER_TEXT";
    public static final String WALLET = "WALLET";
    public static final String CHAIN_HOLDINGS = "CHAIN_HOLDINGS";
    public static final String PRICE_QUOTES = "PRICE_QUOTES";
    public static final String WALLET_SNAPSHOT = "WALLET_SNAPSHOT";
    public static final String PORTFOLIO_DIFF = "PORTFOLIO_DIFF";
    /** The canonical, quantised input of the deterministic risk engine ({@code risk-input/1}, KAN-392): what an L1 verifier re-executes. */
    public static final String RISK_INPUT = "RISK_INPUT";
    public static final String INTENT = "INTENT";
    public static final String TRANSACTION = "TRANSACTION";
    public static final String POLICY_VERDICT = "POLICY_VERDICT";
    /** {@code oracle-reading/1}: the multi-source quotes a decision was priced with (KAN-439). */
    public static final String ORACLE_READING = "ORACLE_READING";
    public static final String APPROVAL = "APPROVAL";
    public static final String RAG_DOC = "RAG_DOC";
    public static final String MARKET_SNAPSHOT = "MARKET_SNAPSHOT";

    public ReceiptInput {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("input type is required");
        }
        type = type.trim().toUpperCase(Locale.ROOT);
        hash = Digests.requireHash("inputs[].hash", hash);
    }

    public static ReceiptInput receipt(String hash) {
        return new ReceiptInput(RECEIPT, hash);
    }
}
