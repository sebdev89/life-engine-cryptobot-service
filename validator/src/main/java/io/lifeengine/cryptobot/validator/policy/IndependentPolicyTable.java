package io.lifeengine.cryptobot.validator.policy;

import io.lifeengine.cryptobot.validator.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.validator.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.validator.policy.PolicyVerdict.AutonomyTier;
import io.lifeengine.cryptobot.validator.policy.PolicyVerdict.Decision;
import io.lifeengine.cryptobot.validator.policy.PolicyVerdict.Escalation;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The validator's <em>own</em> implementation of the policy decision table (paper §8, §18, §20).
 * It is deliberately not the service's {@code DeterministicPolicyEngine}: one boolean per
 * predicate, hand-inlined, no shared helpers, so that the two processes agreeing on a verdict
 * hash means two independent readings of the schema agreed — not one piece of code run twice.
 *
 * <p>Unknown ({@code null}, out of range) fails its predicate: {@code Unknown ⇒ Deny} (§17).
 * Same evaluation order as the schema ({@link PolicyPredicate} declaration order) because the
 * failed-predicates list is part of the hashed verdict.
 */
public final class IndependentPolicyTable {

    private static final long MAX_SAFE = 9_007_199_254_740_991L;

    private IndependentPolicyTable() {}

    public static PolicyVerdict evaluate(PolicyRules r, PolicyInput input) {
        if (r == null) {
            throw new IllegalArgumentException("rules: missing (no policy ⇒ deny everything)");
        }
        PolicyInput in = input == null ? new PolicyInput(null, null) : input;
        IntentFacts i = in.intent();
        StateFacts s = in.state();

        Map<PolicyPredicate, Boolean> ok = new EnumMap<>(PolicyPredicate.class);
        String version = i.policyVersion() == null ? null : Normalizer.normalize(i.policyVersion().trim(), Normalizer.Form.NFC);
        ok.put(PolicyPredicate.POLICY_BOUND, version != null && !version.isEmpty() && version.equals(r.version()));
        ok.put(PolicyPredicate.ASSET_ALLOWED, i.asset() != null && r.allowedAssets().contains(i.asset().trim().toUpperCase(Locale.ROOT)));
        boolean valueKnown = i.tradeValueCents() != null && i.tradeValueCents() >= 0 && i.tradeValueCents() <= MAX_SAFE;
        ok.put(PolicyPredicate.TRADE_WITHIN_MAX, valueKnown && i.tradeValueCents() <= r.maxTradeValueCents());
        boolean dailyKnown = s.dailyExposureCents() != null && s.dailyExposureCents() >= 0 && s.dailyExposureCents() <= MAX_SAFE;
        ok.put(PolicyPredicate.DAILY_LIMIT, valueKnown && dailyKnown && Math.addExact(s.dailyExposureCents(), i.tradeValueCents()) <= r.dailyLimitCents());
        ok.put(PolicyPredicate.ASSET_CONCENTRATION, s.assetExposureAfterBps() != null && s.assetExposureAfterBps() >= 0
                && s.assetExposureAfterBps() <= 10_000 && s.assetExposureAfterBps() <= r.maxAssetExposureBps());
        ok.put(PolicyPredicate.SLIPPAGE_WITHIN_MAX, i.maxSlippageBps() != null && i.maxSlippageBps() >= 0
                && i.maxSlippageBps() <= 10_000 && i.maxSlippageBps() <= r.maxSlippageBps());
        ok.put(PolicyPredicate.ORACLE_FRESH, s.oracleAgeSeconds() != null && s.oracleAgeSeconds() >= 0
                && s.oracleAgeSeconds() <= MAX_SAFE && s.oracleAgeSeconds() <= r.maxOracleAgeSeconds());
        ok.put(PolicyPredicate.AGENT_PERMITTED, s.agentPermitted() != null && s.agentPermitted());
        String strategy = i.strategyId() == null ? null : Normalizer.normalize(i.strategyId().trim(), Normalizer.Form.NFC);
        ok.put(PolicyPredicate.STRATEGY_ENABLED, strategy != null && r.enabledStrategies().contains(strategy));
        ok.put(PolicyPredicate.NONCE_UNUSED, s.nonceUnused() != null && s.nonceUnused());
        boolean slotsKnown = s.currentSlot() != null && i.validUntilSlot() != null && s.currentSlot() >= 0 && i.validUntilSlot() >= 0
                && s.currentSlot() <= MAX_SAFE && i.validUntilSlot() <= MAX_SAFE;
        ok.put(PolicyPredicate.NOT_EXPIRED, slotsKnown && s.currentSlot() <= i.validUntilSlot());

        List<PolicyPredicate> failed = new ArrayList<>();
        for (PolicyPredicate p : PolicyPredicate.values()) {
            if (!ok.get(p)) {
                failed.add(p);
            }
        }
        AutonomyTier tier;
        if (!valueKnown) {
            tier = AutonomyTier.OVER_LIMIT;
        } else if (i.tradeValueCents() <= r.autonomousUpToCents()) {
            tier = AutonomyTier.AUTONOMOUS;
        } else if (i.tradeValueCents() <= r.secondAgentUpToCents()) {
            tier = AutonomyTier.SECOND_AGENT;
        } else if (i.tradeValueCents() <= r.maxTradeValueCents()) {
            tier = AutonomyTier.HUMAN_SIGNATURE;
        } else {
            tier = AutonomyTier.OVER_LIMIT;
        }
        Decision decision;
        Escalation escalation;
        if (!failed.isEmpty()) {
            decision = Decision.DENY;
            escalation = Escalation.NONE;
        } else {
            switch (tier) {
                case AUTONOMOUS -> {
                    decision = Decision.ALLOW;
                    escalation = Escalation.NONE;
                }
                case SECOND_AGENT -> {
                    decision = Decision.ESCALATE;
                    escalation = Escalation.REQUIRE_SECOND_AGENT;
                }
                case HUMAN_SIGNATURE -> {
                    decision = Decision.ESCALATE;
                    escalation = Escalation.REQUIRE_HUMAN_SIGNATURE;
                }
                default -> {
                    decision = Decision.DENY;
                    escalation = Escalation.NONE;
                }
            }
        }
        return new PolicyVerdict(decision, escalation, tier, failed, List.of(PolicyPredicate.values()), r.version(), r.hash(), in.hash());
    }
}
