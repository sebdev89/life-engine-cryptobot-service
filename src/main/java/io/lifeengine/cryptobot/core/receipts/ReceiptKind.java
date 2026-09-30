package io.lifeengine.cryptobot.core.receipts;

/**
 * What step of the pipeline a receipt records (Endgame §6). One receipt per step; the DAG is the
 * pipeline: {@code WALLET_SNAPSHOT → RISK_DECISION}, {@code HUMAN_IDEA → MARKET_ANALYSIS →
 * STRATEGY → RISK_DECISION / SIMULATION → EXECUTION}.
 *
 * <p>The names are part of the canonical body (and so of the hash): renaming one is a schema bump.
 */
public enum ReceiptKind {
    /** The valued portfolio read from the chain and priced by the oracle. */
    WALLET_SNAPSHOT,
    /** A human asked something; the text stays in the service, only a salted commitment travels. */
    HUMAN_IDEA,
    /** The LLM's analysis (advisor answer). Level L0: signed, not reproducible. */
    MARKET_ANALYSIS,
    /** The deterministic risk engine's verdict over a snapshot. Level L1. */
    RISK_DECISION,
    /** The rebalance plan derived from an intent (deterministic planner). Level L1. */
    STRATEGY,
    /** Economic + on-chain simulation of the exact transaction. */
    SIMULATION,
    /** The transaction reached (or was refused by) the chain. */
    EXECUTION,
    /** Reserved (§13): claims with hashed evidence. Not produced yet. */
    PROJECT_ANALYSIS,
    /** Proof of Value V1 (KAN-818): an accepted contribution. {@code output.hash} = sha256 of the ValueEvent canonical JSON (proofofvalue.ValueEventCanonical). */
    VALUE_EVENT,
    /**
     * Proof of Value V5 (KAN-822): the immediate reward of an ANCHORED ValueEvent. Parent = the VALUE_EVENT receipt;
     * {@code output.hash} = sha256 of the distribution's canonical JSON (every payout: identity, wallet, lamports, status, tx).
     */
    VALUE_DISTRIBUTION,
    /**
     * Proof of Value V7 (KAN-824): an economic result split with {@code pov/revenue-share/v1}. Parents = the VALUE_EVENT receipts
     * it is attributed to; {@code output.hash} = sha256 of the revenue event's canonical JSON (amount, policy, pool/fee/retained,
     * every allocation and payout).
     */
    REVENUE_EVENT
}
