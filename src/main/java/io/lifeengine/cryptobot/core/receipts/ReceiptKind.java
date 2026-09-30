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
    /** Proof of Value V1 (KAN-818): a contribution and the acceptance that made it value. The output hash is the value-event hash. */
    VALUE_EVENT
}
