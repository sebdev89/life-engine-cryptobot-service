package io.lifeengine.cryptobot.domain.receipt;

/**
 * What a receipt proves (Endgame §10). Declared by the producer, never inferred: an LLM step is
 * {@link #L0_SIGNED} whatever seed or temperature it used; only a pure Java function over
 * canonical inputs may claim {@link #L1_REPRODUCIBLE}.
 */
public enum ReproducibilityLevel {
    /** "Life Engine affirms it ran this": Ed25519 signature over the canonical hash. */
    L0_SIGNED,
    /** Another node recomputes the same {@code output.hash} from the same inputs (deterministic engine). */
    L1_REPRODUCIBLE,
    /** A third party challenged it with a bond and it held. Not produced in this version. */
    L2_CHALLENGED,
    /** Cryptographic proof (zkML / TEE). Not produced in this version. */
    L3_PROVEN
}
