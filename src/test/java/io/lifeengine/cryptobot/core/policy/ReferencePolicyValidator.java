package io.lifeengine.cryptobot.core.policy;

import io.lifeengine.cryptobot.core.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.core.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.AutonomyTier;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The <em>second, independent</em> implementation of the policy decision table (paper §20,
 * "independent validation"; §29, "every conforming validator must return the same decision").
 * One boolean per predicate, hand-inlined, none of the engine's helpers, no shared code beyond
 * the value types. {@code PolicyDeterminismTest} compares it with the engine over a seeded
 * corpus; the adversarial benchmark (KAN-440) uses it as the offline validator that must agree
 * with the engine on the verdict hash of every one of the 10 000 intents before anything executes.
 */
public final class ReferencePolicyValidator {

    private ReferencePolicyValidator() {}

    public record Out(Decision decision, Escalation escalation, AutonomyTier tier, List<PolicyPredicate> failed) {}

    public static Out decide(PolicyRules r, IntentFacts i, StateFacts s) {
        Map<PolicyPredicate, Boolean> ok = new EnumMap<>(PolicyPredicate.class);
        String version = i.policyVersion() == null ? null : java.text.Normalizer.normalize(i.policyVersion().trim(), java.text.Normalizer.Form.NFC);
        ok.put(PolicyPredicate.POLICY_BOUND, version != null && !version.isEmpty() && version.equals(r.version()));
        ok.put(PolicyPredicate.ASSET_ALLOWED, i.asset() != null && r.allowedAssets().contains(i.asset().trim().toUpperCase(java.util.Locale.ROOT)));
        boolean valueKnown = i.tradeValueCents() != null && i.tradeValueCents() >= 0 && i.tradeValueCents() <= 9_007_199_254_740_991L;
        ok.put(PolicyPredicate.TRADE_WITHIN_MAX, valueKnown && i.tradeValueCents() <= r.maxTradeValueCents());
        boolean dailyKnown = s.dailyExposureCents() != null && s.dailyExposureCents() >= 0 && s.dailyExposureCents() <= 9_007_199_254_740_991L;
        ok.put(PolicyPredicate.DAILY_LIMIT, valueKnown && dailyKnown && Math.addExact(s.dailyExposureCents(), i.tradeValueCents()) <= r.dailyLimitCents());
        ok.put(PolicyPredicate.ASSET_CONCENTRATION, s.assetExposureAfterBps() != null && s.assetExposureAfterBps() >= 0 && s.assetExposureAfterBps() <= 10_000
                && s.assetExposureAfterBps() <= r.maxAssetExposureBps());
        ok.put(PolicyPredicate.SLIPPAGE_WITHIN_MAX, i.maxSlippageBps() != null && i.maxSlippageBps() >= 0 && i.maxSlippageBps() <= 10_000
                && i.maxSlippageBps() <= r.maxSlippageBps());
        ok.put(PolicyPredicate.ORACLE_FRESH, s.oracleAgeSeconds() != null && s.oracleAgeSeconds() >= 0 && s.oracleAgeSeconds() <= 9_007_199_254_740_991L
                && s.oracleAgeSeconds() <= r.maxOracleAgeSeconds());
        ok.put(PolicyPredicate.AGENT_PERMITTED, s.agentPermitted() != null && s.agentPermitted());
        String strategy = i.strategyId() == null ? null : java.text.Normalizer.normalize(i.strategyId().trim(), java.text.Normalizer.Form.NFC);
        ok.put(PolicyPredicate.STRATEGY_ENABLED, strategy != null && r.enabledStrategies().contains(strategy));
        ok.put(PolicyPredicate.NONCE_UNUSED, s.nonceUnused() != null && s.nonceUnused());
        boolean slotsKnown = s.currentSlot() != null && i.validUntilSlot() != null && s.currentSlot() >= 0 && i.validUntilSlot() >= 0
                && s.currentSlot() <= 9_007_199_254_740_991L && i.validUntilSlot() <= 9_007_199_254_740_991L;
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
        if (!failed.isEmpty()) {
            return new Out(Decision.DENY, Escalation.NONE, tier, failed);
        }
        return switch (tier) {
            case AUTONOMOUS -> new Out(Decision.ALLOW, Escalation.NONE, tier, failed);
            case SECOND_AGENT -> new Out(Decision.ESCALATE, Escalation.REQUIRE_SECOND_AGENT, tier, failed);
            case HUMAN_SIGNATURE -> new Out(Decision.ESCALATE, Escalation.REQUIRE_HUMAN_SIGNATURE, tier, failed);
            case OVER_LIMIT -> new Out(Decision.DENY, Escalation.NONE, tier, failed);
        };
    }

    /**
     * The full verdict this validator would sign, built with the same value type as the engine's
     * so the two hashes are comparable: {@code hash(engine) == hash(reference)} is the agreement
     * check of the benchmark.
     */
    public static PolicyVerdict verdict(PolicyRules r, PolicyInput in) {
        Out out = decide(r, in.intent(), in.state());
        return new PolicyVerdict(out.decision(), out.escalation(), out.tier(), out.failed(), List.of(PolicyPredicate.values()),
                r.version(), r.hash(), in.hash());
    }
}
