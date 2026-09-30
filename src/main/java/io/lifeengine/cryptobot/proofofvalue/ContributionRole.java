package io.lifeengine.cryptobot.proofofvalue;

/** Proof of Value V1 (KAN-818): what a contributor did for an accepted outcome. Mirrors the CHECK of {@code pov_contribution.role} (V12). */
public enum ContributionRole {
    SPECIFIER,
    ARCHITECT,
    IMPLEMENTER,
    REVIEWER,
    KNOWLEDGE_PROVIDER,
    COMPUTE_PROVIDER,
    OPERATOR,
    CAPITAL_PROVIDER
}
