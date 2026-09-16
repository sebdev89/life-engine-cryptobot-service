package io.lifeengine.cryptobot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.domain.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict.AutonomyTier;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict.Decision;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict.Escalation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The decision table of paper §8 + §18, one row at a time. */
class DeterministicPolicyEngineTest {

    /** ≤ $1,000 ALLOW · ≤ $10,000 second agent · ≤ $50,000 human signature · above DENY. */
    static final PolicyRules R = PolicyRulesTest.paper();

    static IntentFacts intent(long tradeValueCents) {
        return new IntentFacts("crypto-agent-42", "momentum-v3", "paper-v1", "SOL", tradeValueCents, 30, 421_872_200L);
    }

    static StateFacts state() {
        return new StateFacts(500_000L, 4_500, 12L, true, true, 421_872_118L);
    }

    static PolicyVerdict eval(IntentFacts i, StateFacts s) {
        return DeterministicPolicyEngine.evaluate(R, new PolicyInput(i, s));
    }

    @ParameterizedTest(name = "{0} cents → {1} / {2} / {3}")
    @CsvSource({
        "0,        ALLOW,    NONE,                    AUTONOMOUS",
        "1,        ALLOW,    NONE,                    AUTONOMOUS",
        "100000,   ALLOW,    NONE,                    AUTONOMOUS",
        "100001,   ESCALATE, REQUIRE_SECOND_AGENT,    SECOND_AGENT",
        "1000000,  ESCALATE, REQUIRE_SECOND_AGENT,    SECOND_AGENT",
        "1000001,  ESCALATE, REQUIRE_HUMAN_SIGNATURE, HUMAN_SIGNATURE",
        "2000000,  ESCALATE, REQUIRE_HUMAN_SIGNATURE, HUMAN_SIGNATURE",
        "5000000,  ESCALATE, REQUIRE_HUMAN_SIGNATURE, HUMAN_SIGNATURE",
        "5000001,  DENY,     NONE,                    OVER_LIMIT",
        "9000000,  DENY,     NONE,                    OVER_LIMIT",
    })
    void tiersByTradeValueAtTheBoundaries(long cents, Decision decision, Escalation escalation, AutonomyTier tier) {
        PolicyVerdict v = eval(intent(cents), state());
        assertThat(v.decision()).isEqualTo(decision);
        assertThat(v.escalation()).isEqualTo(escalation);
        assertThat(v.tier()).isEqualTo(tier);
        if (decision == Decision.DENY) {
            assertThat(v.failedPredicates()).containsExactly(PolicyPredicate.TRADE_WITHIN_MAX);
        } else {
            assertThat(v.failedPredicates()).isEmpty();
        }
        assertThat(v.evaluatedPredicates()).containsExactly(PolicyPredicate.values());
        assertThat(v.policyHash()).isEqualTo(R.hash());
        assertThat(v.policyVersion()).isEqualTo("paper-v1");
    }

    @Test
    void policyBindingRequiresTheIntentToNameThisPolicyVersion() {
        IntentFacts other = new IntentFacts("a", "momentum-v3", "paper-v0", "SOL", 1L, 30, 421_872_200L);
        assertThat(eval(other, state()).failedPredicates()).containsExactly(PolicyPredicate.POLICY_BOUND);
        IntentFacts nfd = new IntentFacts("a", "momentum-v3", " paper-v1 ", "SOL", 1L, 30, 421_872_200L);
        assertThat(eval(nfd, state()).failedPredicates()).isEmpty();
    }

    @Test
    void assetOutsideTheAllowlistIsDenied() {
        IntentFacts doge = new IntentFacts("a", "momentum-v3", "paper-v1", "DOGE", 1L, 30, 421_872_200L);
        assertThat(eval(doge, state()).failedPredicates()).containsExactly(PolicyPredicate.ASSET_ALLOWED);
        IntentFacts lower = new IntentFacts("a", "momentum-v3", "paper-v1", "usdc", 1L, 30, 421_872_200L);
        assertThat(eval(lower, state()).failedPredicates()).isEmpty();
    }

    @Test
    void dailyLimitCountsTodaysExposurePlusThisTrade() {
        // 9,000,000 already + 1,000,000 = limit exactly ⇒ holds
        assertThat(eval(intent(1_000_000L), new StateFacts(9_000_000L, 4_500, 12L, true, true, 421_872_118L)).failedPredicates()).isEmpty();
        // one cent more ⇒ fails, and only that predicate
        assertThat(eval(intent(1_000_001L), new StateFacts(9_000_000L, 4_500, 12L, true, true, 421_872_118L)).failedPredicates())
                .containsExactly(PolicyPredicate.DAILY_LIMIT);
        // absurd exposure cannot overflow into an ALLOW
        assertThat(eval(intent(1L), new StateFacts(Long.MAX_VALUE, 4_500, 12L, true, true, 421_872_118L)).failedPredicates())
                .containsExactly(PolicyPredicate.DAILY_LIMIT);
    }

    @Test
    void concentrationSlippageAndOracleAgeAreInclusiveUpperBounds() {
        assertThat(eval(intent(1L), new StateFacts(0L, 6_000, 60L, true, true, 1L)).failedPredicates()).isEmpty();
        assertThat(eval(intent(1L), new StateFacts(0L, 6_001, 61L, true, true, 1L)).failedPredicates())
                .containsExactly(PolicyPredicate.ASSET_CONCENTRATION, PolicyPredicate.ORACLE_FRESH);
        IntentFacts slippage50 = new IntentFacts("a", "momentum-v3", "paper-v1", "SOL", 1L, 50, 421_872_200L);
        assertThat(eval(slippage50, state()).failedPredicates()).isEmpty();
        IntentFacts slippage51 = new IntentFacts("a", "momentum-v3", "paper-v1", "SOL", 1L, 51, 421_872_200L);
        assertThat(eval(slippage51, state()).failedPredicates()).containsExactly(PolicyPredicate.SLIPPAGE_WITHIN_MAX);
    }

    @Test
    void permissionStrategyNonceAndExpiryAreHardGates() {
        assertThat(eval(intent(1L), new StateFacts(0L, 0, 0L, false, true, 1L)).failedPredicates()).containsExactly(PolicyPredicate.AGENT_PERMITTED);
        assertThat(eval(intent(1L), new StateFacts(0L, 0, 0L, true, false, 1L)).failedPredicates()).containsExactly(PolicyPredicate.NONCE_UNUSED);
        IntentFacts yolo = new IntentFacts("a", "yolo", "paper-v1", "SOL", 1L, 30, 421_872_200L);
        assertThat(eval(yolo, state()).failedPredicates()).containsExactly(PolicyPredicate.STRATEGY_ENABLED);
        // current_slot == valid_until holds; one past fails
        assertThat(eval(intent(1L), new StateFacts(0L, 0, 0L, true, true, 421_872_200L)).failedPredicates()).isEmpty();
        assertThat(eval(intent(1L), new StateFacts(0L, 0, 0L, true, true, 421_872_201L)).failedPredicates()).containsExactly(PolicyPredicate.NOT_EXPIRED);
    }

    @Test
    void everyPredicateIsEvaluatedAndReportedInOrder() {
        IntentFacts bad = new IntentFacts("a", "yolo", "paper-v0", "DOGE", 6_000_000L, 51, 1L);
        StateFacts worse = new StateFacts(10_000_000L, 10_000, 61L, false, false, 2L);
        PolicyVerdict v = eval(bad, worse);
        assertThat(v.decision()).isEqualTo(Decision.DENY);
        assertThat(v.escalation()).isEqualTo(Escalation.NONE);
        assertThat(v.tier()).isEqualTo(AutonomyTier.OVER_LIMIT);
        assertThat(v.failedPredicates()).containsExactly(PolicyPredicate.values());
    }

    @Test
    void unknownIsDeny() {
        PolicyVerdict allUnknown = DeterministicPolicyEngine.evaluate(R, new PolicyInput(null, null));
        assertThat(allUnknown.decision()).isEqualTo(Decision.DENY);
        assertThat(allUnknown.tier()).isEqualTo(AutonomyTier.OVER_LIMIT);
        assertThat(allUnknown.failedPredicates()).containsExactly(PolicyPredicate.values());

        PolicyVerdict noInput = DeterministicPolicyEngine.evaluate(R, null);
        assertThat(noInput.decision()).isEqualTo(Decision.DENY);
        assertThat(noInput.inputHash()).isEqualTo(allUnknown.inputHash());

        // a single unknown fact fails only its predicate; negative or out-of-range integers are unknown too
        assertThat(eval(intent(1L), new StateFacts(null, 4_500, 12L, true, true, 421_872_118L)).failedPredicates()).containsExactly(PolicyPredicate.DAILY_LIMIT);
        assertThat(eval(intent(1L), new StateFacts(0L, -1, 12L, true, true, 421_872_118L)).failedPredicates()).containsExactly(PolicyPredicate.ASSET_CONCENTRATION);
        assertThat(eval(intent(1L), new StateFacts(0L, 10_001, 12L, true, true, 421_872_118L)).failedPredicates()).containsExactly(PolicyPredicate.ASSET_CONCENTRATION);
        assertThat(eval(intent(-1L), state()).failedPredicates()).containsExactly(PolicyPredicate.TRADE_WITHIN_MAX, PolicyPredicate.DAILY_LIMIT);
        assertThat(eval(intent(1L), new StateFacts(0L, 0, -5L, true, true, 1L)).failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);
        assertThat(eval(intent(1L), new StateFacts(0L, 0, 0L, null, true, 1L)).failedPredicates()).containsExactly(PolicyPredicate.AGENT_PERMITTED);

        assertThatThrownBy(() -> DeterministicPolicyEngine.evaluate(null, new PolicyInput(intent(1L), state())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("policy that cannot be loaded");
    }

    @Test
    void unknownFactsAreAbsentFromTheCanonicalInputSoTheHashSaysWhatWasKnown() {
        PolicyInput partial = new PolicyInput(new IntentFacts("a", null, "paper-v1", null, 5L, null, null), new StateFacts(null, null, null, true, null, null));
        assertThat(partial.canonicalJson()).isEqualTo(
                "{\"intent\":{\"agent_id\":\"a\",\"policy_version\":\"paper-v1\",\"trade_value_cents\":5},\"schema_version\":\"1\",\"state\":{\"agent_permitted\":true}}");
        assertThat(new PolicyInput(null, null).canonicalJson()).isEqualTo("{\"intent\":{},\"schema_version\":\"1\",\"state\":{}}");
    }

    @Test
    void verdictCanonicalFormCoversDecisionPredicatesAndBothHashes() {
        PolicyVerdict v = eval(intent(2_000_000L), state());
        String expectedInput = new PolicyInput(intent(2_000_000L), state()).hash();
        assertThat(v.inputHash()).isEqualTo(expectedInput);
        assertThat(v.canonicalJson()).isEqualTo(
                "{\"decision\":\"ESCALATE\",\"escalation\":\"REQUIRE_HUMAN_SIGNATURE\",\"evaluated_predicates\":[" + names() + "],"
                + "\"failed_predicates\":[],\"input_hash\":\"" + expectedInput + "\",\"policy_hash\":\"" + R.hash() + "\","
                + "\"policy_version\":\"paper-v1\",\"schema_version\":\"1\",\"tier\":\"HUMAN_SIGNATURE\"}");
        assertThat(v.hash()).matches("sha256:[0-9a-f]{64}");
        assertThat(v.allowed()).isFalse();
        assertThat(v.denied()).isFalse();
    }

    private static String names() {
        return String.join(",", List.of(PolicyPredicate.values()).stream().map(p -> "\"" + p.name() + "\"").toList());
    }
}
