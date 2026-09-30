package io.lifeengine.cryptobot.proofofvalue;

/** Proof of Value V3 (KAN-820): what kind of knowledge an asset is. Mirrors the CHECK of {@code pov_knowledge_asset.kind} (V13). */
public enum KnowledgeAssetKind {
    ARCHITECTURE,
    PROMPT,
    RULESET,
    DATASET,
    EVAL_SUITE,
    STRATEGY,
    RUNBOOK,
    ALGORITHM,
    AGENT_CONFIG,
    DOMAIN_KNOWLEDGE
}
