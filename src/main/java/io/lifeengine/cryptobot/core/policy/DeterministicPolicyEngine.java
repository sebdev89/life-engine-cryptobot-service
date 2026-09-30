package io.lifeengine.cryptobot.core.policy;

import io.lifeengine.cryptobot.core.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.core.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.AutonomyTier;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation;
import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic policy layer (paper §8, §9, §17, §18) as a pure function:
 * {@code evaluate(R, (I, S)) → verdict}. Java only, integers only, no I/O, no clock, no
 * randomness, no parallelism — the same {@code (I, S, R)} gives the same verdict (and the same
 * verdict hash) on every machine, every time, and a second implementation of this table must
 * agree with it (see {@code PolicyDeterminismTest}).
 *
 * <p>Two stages:
 * <ol>
 *   <li>{@code Valid(I) = ∧ P_i(I, S)} over {@link PolicyPredicate} in declaration order. Every
 *       predicate is evaluated (no short-circuit) so the verdict lists all failures. An unknown
 *       fact ({@code null}, out of range) fails its predicate: {@code Unknown ⇒ Deny}.
 *   <li>If everything holds, the autonomy tier by trade value decides between ALLOW and the two
 *       ESCALATE forms; anything above {@code max_trade_value} already failed
 *       {@link PolicyPredicate#TRADE_WITHIN_MAX}.
 * </ol>
 *
 * <p>This is the only component whose output is authority. The LLM that proposed the intent is
 * outside the trusted computing base (§16): whatever it said, only this table can say "execute".
 */
public final class DeterministicPolicyEngine {

    /**
     * The engine id a Decision Receipt names. Its "version" is the policy version
     * ({@code R_v}) and its "weights hash" is {@code H_R}: the code is this table, the numbers are
     * the rules, and a verifier re-runs {@link #evaluate} on the stored {@code (I, S)}.
     */
    public static final String ID = "policy-engine";

    private DeterministicPolicyEngine() {}

    public static PolicyVerdict evaluate(PolicyRules rules, PolicyInput input) {
        if (rules == null) {
            throw new IllegalArgumentException("rules: missing (a policy that cannot be loaded denies everything)");
        }
        PolicyInput in = input == null ? new PolicyInput(null, null) : input;
        IntentFacts i = in.intent();
        StateFacts s = in.state();

        List<PolicyPredicate> evaluated = new ArrayList<>(PolicyPredicate.values().length);
        List<PolicyPredicate> failed = new ArrayList<>();
        for (PolicyPredicate p : PolicyPredicate.values()) {
            evaluated.add(p);
            if (!holds(p, rules, i, s)) {
                failed.add(p);
            }
        }

        AutonomyTier tier = tier(rules, i.tradeValueCents());
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
                    // Unreachable: OVER_LIMIT implies TRADE_WITHIN_MAX failed. Fail closed anyway.
                    decision = Decision.DENY;
                    escalation = Escalation.NONE;
                }
            }
        }
        return new PolicyVerdict(decision, escalation, tier, failed, evaluated, rules.version(), rules.hash(), in.hash());
    }

    /** The §18 band of the trade value; unknown or invalid value is over the limit. */
    public static AutonomyTier tier(PolicyRules rules, Long tradeValueCents) {
        if (!inRange(tradeValueCents)) {
            return AutonomyTier.OVER_LIMIT;
        }
        long v = tradeValueCents;
        if (v <= rules.autonomousUpToCents()) {
            return AutonomyTier.AUTONOMOUS;
        }
        if (v <= rules.secondAgentUpToCents()) {
            return AutonomyTier.SECOND_AGENT;
        }
        if (v <= rules.maxTradeValueCents()) {
            return AutonomyTier.HUMAN_SIGNATURE;
        }
        return AutonomyTier.OVER_LIMIT;
    }

    private static boolean holds(PolicyPredicate p, PolicyRules r, IntentFacts i, StateFacts s) {
        return switch (p) {
            case POLICY_BOUND -> i.policyVersion() != null && r.version().equals(PolicyRules.identifierOrNull(i.policyVersion()));
            case ASSET_ALLOWED -> r.allowsAsset(i.asset());
            case TRADE_WITHIN_MAX -> inRange(i.tradeValueCents()) && i.tradeValueCents() <= r.maxTradeValueCents();
            case DAILY_LIMIT -> inRange(i.tradeValueCents()) && inRange(s.dailyExposureCents())
                    && s.dailyExposureCents() + i.tradeValueCents() <= r.dailyLimitCents();
            case ASSET_CONCENTRATION -> inBps(s.assetExposureAfterBps()) && s.assetExposureAfterBps() <= r.maxAssetExposureBps();
            case SLIPPAGE_WITHIN_MAX -> inBps(i.maxSlippageBps()) && i.maxSlippageBps() <= r.maxSlippageBps();
            case ORACLE_FRESH -> inRange(s.oracleAgeSeconds()) && s.oracleAgeSeconds() <= r.maxOracleAgeSeconds();
            case AGENT_PERMITTED -> Boolean.TRUE.equals(s.agentPermitted());
            case STRATEGY_ENABLED -> r.enablesStrategy(i.strategyId());
            case NONCE_UNUSED -> Boolean.TRUE.equals(s.nonceUnused());
            case NOT_EXPIRED -> inRange(s.currentSlot()) && inRange(i.validUntilSlot()) && s.currentSlot() <= i.validUntilSlot();
        };
    }

    /** Known, non-negative and inside the safe integer range — the only integers the model accepts. */
    private static boolean inRange(Long v) {
        return v != null && v >= 0 && v <= CanonicalJson.MAX_SAFE_INTEGER;
    }

    private static boolean inBps(Integer v) {
        return v != null && v >= 0 && v <= PolicyRules.MAX_BPS;
    }
}
