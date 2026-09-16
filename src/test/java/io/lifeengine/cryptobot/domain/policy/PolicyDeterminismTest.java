package io.lifeengine.cryptobot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.domain.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict.Decision;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * "Same {@code (I, S, R)} ⇒ same decision, in two implementations and in two executions" — the
 * acceptance criterion of KAN-436 and paper §20 (independent validation).
 * {@link ReferencePolicyValidator} is a second implementation of the decision table, written as
 * a flat rule list with none of the engine's helpers; the two are compared over a seeded corpus
 * that covers every tier, every predicate, the boundaries, unknowns and out-of-range integers.
 */
class PolicyDeterminismTest {

    static final PolicyRules R = PolicyRulesTest.paper();

    /** Values that hit every band and every edge; nulls and out-of-range on purpose. */
    static final Long[] CENTS = {null, -1L, 0L, 1L, 99_999L, 100_000L, 100_001L, 999_999L, 1_000_000L, 1_000_001L, 4_999_999L, 5_000_000L, 5_000_001L,
        8_999_999L, 9_000_000L, 9_000_001L, 10_000_000L, 9_007_199_254_740_991L, 9_007_199_254_740_992L, Long.MAX_VALUE};
    static final Integer[] BPS = {null, -1, 0, 49, 50, 51, 5_999, 6_000, 6_001, 10_000, 10_001};
    static final Long[] SECONDS = {null, -1L, 0L, 59L, 60L, 61L, 3_600L};
    static final Long[] SLOTS = {null, -1L, 0L, 421_872_199L, 421_872_200L, 421_872_201L};
    static final Boolean[] BOOLS = {null, true, false};
    static final String[] ASSETS = {null, "", "SOL", "sol", " usdc ", "DOGE"};
    static final String[] STRATEGIES = {null, "", "momentum-v3", "REBALANCE", "rebalance", "yolo"};
    static final String[] VERSIONS = {null, "", "paper-v1", " paper-v1 ", "paper-v0", "PAPER-V1"};

    static List<PolicyInput> corpus(int size, long seed) {
        Random rnd = new Random(seed);
        List<PolicyInput> out = new ArrayList<>(size);
        for (int n = 0; n < size; n++) {
            // 60 % of the corpus is "mostly valid" so ALLOW/ESCALATE are well represented, not only DENY
            boolean mostlyValid = rnd.nextInt(10) < 6;
            IntentFacts i = new IntentFacts(
                    "agent-" + rnd.nextInt(3),
                    mostlyValid ? "momentum-v3" : pick(rnd, STRATEGIES),
                    mostlyValid ? "paper-v1" : pick(rnd, VERSIONS),
                    mostlyValid ? "SOL" : pick(rnd, ASSETS),
                    pick(rnd, CENTS),
                    mostlyValid ? 30 : pick(rnd, BPS),
                    mostlyValid ? 421_872_200L : pick(rnd, SLOTS));
            StateFacts s = new StateFacts(
                    mostlyValid ? 500_000L : pick(rnd, CENTS),
                    mostlyValid ? 4_500 : pick(rnd, BPS),
                    mostlyValid ? 12L : pick(rnd, SECONDS),
                    mostlyValid ? Boolean.TRUE : pick(rnd, BOOLS),
                    mostlyValid ? Boolean.TRUE : pick(rnd, BOOLS),
                    mostlyValid ? 421_872_118L : pick(rnd, SLOTS));
            out.add(new PolicyInput(i, s));
        }
        return out;
    }

    private static <T> T pick(Random rnd, T[] values) {
        return values[rnd.nextInt(values.length)];
    }

    @Test
    void twoImplementationsAgreeOnEveryInputOfTheCorpus() {
        List<PolicyInput> corpus = corpus(5_000, 436L);
        Map<Decision, Integer> seen = new EnumMap<>(Decision.class);
        for (PolicyInput in : corpus) {
            PolicyVerdict engine = DeterministicPolicyEngine.evaluate(R, in);
            ReferencePolicyValidator.Out ref = ReferencePolicyValidator.decide(R, in.intent(), in.state());
            assertThat(engine.decision()).as("decision for %s", in.canonicalJson()).isEqualTo(ref.decision());
            assertThat(engine.escalation()).as("escalation for %s", in.canonicalJson()).isEqualTo(ref.escalation());
            assertThat(engine.tier()).as("tier for %s", in.canonicalJson()).isEqualTo(ref.tier());
            assertThat(engine.failedPredicates()).as("failed for %s", in.canonicalJson()).isEqualTo(ref.failed());
            seen.merge(engine.decision(), 1, Integer::sum);
        }
        // the corpus exercised every outcome, not just DENY
        assertThat(seen).containsKeys(Decision.ALLOW, Decision.ESCALATE, Decision.DENY);
        assertThat(seen.get(Decision.ALLOW)).isGreaterThan(100);
        assertThat(seen.get(Decision.ESCALATE)).isGreaterThan(100);
    }

    @Test
    void twoExecutionsGiveTheSameVerdictHashEvenInParallel() throws Exception {
        List<PolicyInput> corpus = corpus(1_000, 7L);
        List<String> first = corpus.stream().map(in -> DeterministicPolicyEngine.evaluate(R, in).hash()).toList();
        // a fresh PolicyRules instance (same values) and a thread pool: no hidden state, no ordering effects
        PolicyRules again = PolicyRulesTest.paper();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> jobs = corpus.stream().<Callable<String>>map(in -> () -> DeterministicPolicyEngine.evaluate(again, in).hash()).toList();
            List<Future<String>> futures = pool.invokeAll(jobs);
            List<String> second = new ArrayList<>();
            for (Future<String> f : futures) {
                second.add(f.get());
            }
            assertThat(second).isEqualTo(first);
        } finally {
            pool.shutdownNow();
        }
        assertThat(first.stream().distinct().count()).isGreaterThan(50); // hashes do discriminate inputs
    }

    @Test
    void theVerdictHashBindsThePolicyAndTheInput() {
        PolicyInput in = corpus(1, 1L).get(0);
        PolicyVerdict base = DeterministicPolicyEngine.evaluate(R, in);
        PolicyRules relabelled = new PolicyRules("paper-v1-b", R.allowedAssets(), R.enabledStrategies(), R.maxTradeValueCents(), R.dailyLimitCents(),
                R.maxAssetExposureBps(), R.maxSlippageBps(), R.maxOracleAgeSeconds(), R.autonomousUpToCents(), R.secondAgentUpToCents());
        PolicyVerdict other = DeterministicPolicyEngine.evaluate(relabelled, in);
        assertThat(other.policyHash()).isNotEqualTo(base.policyHash());
        assertThat(other.hash()).isNotEqualTo(base.hash());
        PolicyInput tweaked = new PolicyInput(in.intent(), new StateFacts(in.state().dailyExposureCents(), in.state().assetExposureAfterBps(),
                in.state().oracleAgeSeconds(), in.state().agentPermitted(), in.state().nonceUnused(), 1L));
        assertThat(DeterministicPolicyEngine.evaluate(R, tweaked).inputHash()).isNotEqualTo(base.inputHash());
    }
}
