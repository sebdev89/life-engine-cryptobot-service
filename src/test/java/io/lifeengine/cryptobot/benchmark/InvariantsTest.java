package io.lifeengine.cryptobot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Outcome;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Result;
import io.lifeengine.cryptobot.benchmark.BenchmarkRun.Row;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Case;
import io.lifeengine.cryptobot.domain.intent.IntentAction;
import io.lifeengine.cryptobot.domain.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyPredicate;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.policy.ReferencePolicyValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The invariants of paper §23 (I1–I5), §17 (I6) and §29 (I7) as executable properties over the
 * benchmark corpus plus targeted cases. Each one is universally quantified over every intent the
 * run saw, so a single counter-example fails the test:
 *
 * <pre>
 *   I1  trade limit      ∀I: Amount(I) > Limit            ⇒ ¬Execute(I)
 *   I2  replay           ∀I: ConsumedNonce(I)             ⇒ ¬Execute(I)
 *   I3  authorization    ∀I: ¬AuthorizedAgent(I)          ⇒ ¬Execute(I)
 *   I4  policy binding   Execute(I)                       ⇒ PolicyHash(I) = ActivePolicyHash
 *   I5  asset restriction Execute(I)                      ⇒ Asset(I) ∈ AllowedAssets
 *   I6  fail-closed      Unknown(I, S)                    ⇒ Deny                      (§17)
 *   I7  reproducibility  same (I, S, R)                   ⇒ same verdict, every validator (§29)
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvariantsTest {

    static final long SEED = 4401L;

    AgentRegistry agents;
    BenchmarkRun run;
    final Map<String, Object> evidence = new java.util.TreeMap<>();

    @BeforeAll
    void run() {
        agents = BenchmarkRun.agents();
        run = BenchmarkRun.of(SEED, 3_000, 1_500, agents);
    }

    @AfterAll
    void report() throws Exception {
        BenchmarkReport.writeSection("cryptobot invariants I1–I7 (KAN-440, paper §23/§17/§29)", "invariants", evidence, "invariants-v1");
    }

    private void record(String id, String statement, String checkedOver) {
        evidence.put(id, Map.of("statement", statement, "checked_over", checkedOver, "holds", true));
    }

    @Test
    void i1_tradeLimit_anAmountOverTheLimitNeverExecutes() {
        long max = run.rules.maxTradeValueCents();
        long autonomous = run.rules.autonomousUpToCents();
        int over = 0;
        for (Row r : run.rows) {
            Long value = r.o().input() == null ? null : r.o().input().intent().tradeValueCents();
            if (value == null || value > max) {
                if (r.o().intent() != null && r.o().intent().action() != IntentAction.HOLD && r.o().intent().action() != IntentAction.CANCEL) {
                    over++;
                    assertThat(r.o().executed()).as("#%s %s value=%s", r.c().id(), r.c().variant(), value).isFalse();
                    assertThat(r.o().authorized()).as("#%s over the cap must not even wait for a human", r.c().id()).isFalse();
                }
            }
            if (r.o().executed()) {
                // executing without a human means the autonomous band, not just under the cap
                assertThat(value).isNotNull().isLessThanOrEqualTo(autonomous);
            }
        }
        assertThat(over).isGreaterThan(100);
        record("I1", "Amount(I) > Limit ⇒ ¬Execute(I)", run.rows.size() + " intents, " + over + " over the cap or unpriceable");
    }

    @Test
    void i2_replay_aConsumedNonceNeverExecutes() {
        // (a) every row that arrived with a nonce already reserved did not execute
        int replays = 0;
        for (Row r : run.rows) {
            if (r.o().input() != null && Boolean.FALSE.equals(r.o().input().state().nonceUnused())) {
                replays++;
                assertThat(r.o().executed()).as("#%s", r.c().id()).isFalse();
                assertThat(r.o().verdict().failedPredicates()).contains(PolicyPredicate.NONCE_UNUSED);
            }
        }
        // (b) replay every executed intent verbatim — same bytes, same valid signature — after the fact
        int executedBefore = run.layer.executedCount();
        int replayed = 0;
        for (Row r : run.rows) {
            if (r.o().executed()) {
                run.layer.advanceSlots(1);
                Outcome again = run.layer.submit(r.c().json(), r.c().signature());
                replayed++;
                assertThat(again.executed()).as("replay of #%s", r.c().id()).isFalse();
                assertThat(again.result()).isEqualTo(Result.DENIED);
                assertThat(again.verdict().failedPredicates()).contains(PolicyPredicate.NONCE_UNUSED);
            }
        }
        assertThat(run.layer.executedCount()).isEqualTo(executedBefore);
        // (c) defence in depth: even if the nonce ledger lost the reservation, the executed-hash record refuses the duplicate
        // (the executed intent that expires last, so the slots the replays consumed above do not turn this into NOT_EXPIRED)
        Row first = run.rows.stream().filter(r -> r.o().executed())
                .max(java.util.Comparator.comparingLong(r -> r.o().intent().validUntilSlot())).orElseThrow();
        run.layer.forgetNonce(first.o().intent().agentId(), first.o().intent().nonce());
        Outcome dup = run.layer.submit(first.c().json(), first.c().signature());
        assertThat(dup.result()).as(dup.reason()).isEqualTo(Result.DUPLICATE);
        assertThat(run.layer.executedCount()).isEqualTo(executedBefore);
        assertThat(replays).isGreaterThan(50);
        record("I2", "ConsumedNonce(I) ⇒ ¬Execute(I)", replays + " replays in the corpus + " + replayed + " verbatim replays of executed intents + 1 ledger-loss duplicate");
    }

    @Test
    void i3_authorization_anUnauthorizedAgentNeverExecutes() {
        int unauthorized = 0;
        for (Row r : run.rows) {
            boolean authorizedAgent = r.o().signatureValid() && r.o().intent() != null && agents.isPermitted(r.o().intent().agentId());
            if (!authorizedAgent) {
                unauthorized++;
                assertThat(r.o().executed()).as("#%s %s", r.c().id(), r.c().variant()).isFalse();
                assertThat(r.o().authorized()).as("#%s", r.c().id()).isFalse();
            }
        }
        // revoking an agent mid-run turns its perfectly valid intents into denials, with no other change
        String victim = agents.permittedAgents().get(0);
        List<Case> fresh = new CorpusGenerator(SEED + 7, run.rules, agents, 1_000_000L).generate(400, 0).stream()
                .filter(c -> c.json().contains("\"" + victim + "\"")).toList();
        assertThat(fresh).isNotEmpty();
        agents.revoke(victim);
        try {
            for (Case c : fresh) {
                run.layer.advanceSlots(1);
                Outcome o = run.layer.submit(c.json(), c.signature());
                assertThat(o.signatureValid()).isTrue();
                assertThat(o.result()).isEqualTo(Result.DENIED);
                if (o.verdict() != null) {
                    assertThat(o.verdict().failedPredicates()).contains(PolicyPredicate.AGENT_PERMITTED);
                }
            }
        } finally {
            agents.permit(victim);
        }
        record("I3", "¬AuthorizedAgent(I) ⇒ ¬Execute(I)", unauthorized + " unauthenticated/unpermitted in the corpus + " + fresh.size() + " after revoking a live agent");
    }

    @Test
    void i4_policyBinding_executionOnlyUnderTheActivePolicyHash() {
        String active = run.rules.hash();
        int executed = 0;
        for (Row r : run.rows) {
            if (r.o().executed()) {
                executed++;
                assertThat(r.o().verdict().policyHash()).isEqualTo(active);
                assertThat(r.o().intent().policyVersion()).isEqualTo(run.rules.version());
            }
        }
        // rotate the policy: same limits, new version ⇒ new H_R. Intents bound to the old version stop, all of them.
        PolicyRules rotated = new PolicyRules("paper-v2", run.rules.allowedAssets(), run.rules.enabledStrategies(), run.rules.maxTradeValueCents(),
                run.rules.dailyLimitCents(), run.rules.maxAssetExposureBps(), run.rules.maxSlippageBps(), run.rules.maxOracleAgeSeconds(),
                run.rules.autonomousUpToCents(), run.rules.secondAgentUpToCents());
        assertThat(rotated.hash()).isNotEqualTo(active);
        List<Case> boundToOld = new CorpusGenerator(SEED + 11, run.rules, agents, 2_000_000L).generate(500, 0);
        int before = run.layer.executedCount();
        run.layer.rules(rotated);
        try {
            for (Case c : boundToOld) {
                run.layer.advanceSlots(1);
                Outcome o = run.layer.submit(c.json(), c.signature());
                assertThat(o.authorized()).as("#%s under rotated policy", c.id()).isFalse();
                if (o.verdict() != null) {
                    assertThat(o.verdict().failedPredicates()).contains(PolicyPredicate.POLICY_BOUND);
                    assertThat(o.verdict().policyHash()).isEqualTo(rotated.hash());
                }
            }
        } finally {
            run.layer.rules(run.rules);
        }
        assertThat(run.layer.executedCount()).isEqualTo(before);
        record("I4", "Execute(I) ⇒ PolicyHash(I) = ActivePolicyHash", executed + " executions checked + " + boundToOld.size() + " intents bound to R_v submitted under R_w");
    }

    @Test
    void i5_assetRestriction_everyExecutedLegIsAnAllowedAsset() {
        int executed = 0;
        for (Row r : run.rows) {
            if (r.o().executed()) {
                executed++;
                var i = r.o().intent();
                assertThat(run.rules.allowsAsset(r.o().input().intent().asset())).isTrue();
                if (i.inputAsset() != null) {
                    assertThat(run.rules.allowsAsset(i.inputAsset().value())).isTrue();
                }
                if (i.outputAsset() != null) {
                    assertThat(run.rules.allowsAsset(i.outputAsset().value())).isTrue();
                }
                if (i.targetWeightsBps() != null) {
                    i.targetWeightsBps().keySet().forEach(a -> assertThat(run.rules.allowsAsset(a.value())).isTrue());
                    assertThat(run.rules.allowsAsset(i.counterAsset().value())).isTrue();
                }
            }
        }
        // and the contrapositive over the whole corpus: any disallowed leg ⇒ not even authorized
        int disallowed = 0;
        for (Row r : run.rows) {
            var i = r.o().intent();
            if (i == null) {
                continue;
            }
            boolean bad = (i.inputAsset() != null && !run.rules.allowsAsset(i.inputAsset().value()))
                    || (i.outputAsset() != null && !run.rules.allowsAsset(i.outputAsset().value()))
                    || (i.counterAsset() != null && !run.rules.allowsAsset(i.counterAsset().value()))
                    || (i.targetWeightsBps() != null && i.targetWeightsBps().keySet().stream().anyMatch(a -> !run.rules.allowsAsset(a.value())));
            if (bad) {
                disallowed++;
                assertThat(r.o().authorized()).as("#%s", r.c().id()).isFalse();
            }
        }
        assertThat(disallowed).isGreaterThan(30);
        record("I5", "Execute(I) ⇒ Asset(I) ∈ AllowedAssets", executed + " executions + " + disallowed + " intents with a disallowed leg");
    }

    @Test
    void i6_failClosed_anyUnknownFactDenies() {
        // (a) the engine, directly: take every ALLOW/ESCALATE input of the run and blank one state fact at a time
        int inputs = 0;
        int blanked = 0;
        for (Row r : run.rows) {
            PolicyVerdict v = r.o().verdict();
            if (v == null || v.denied()) {
                continue;
            }
            inputs++;
            StateFacts s = r.o().input().state();
            List<StateFacts> unknowns = List.of(
                    new StateFacts(null, s.assetExposureAfterBps(), s.oracleAgeSeconds(), s.agentPermitted(), s.nonceUnused(), s.currentSlot()),
                    new StateFacts(s.dailyExposureCents(), null, s.oracleAgeSeconds(), s.agentPermitted(), s.nonceUnused(), s.currentSlot()),
                    new StateFacts(s.dailyExposureCents(), s.assetExposureAfterBps(), null, s.agentPermitted(), s.nonceUnused(), s.currentSlot()),
                    new StateFacts(s.dailyExposureCents(), s.assetExposureAfterBps(), s.oracleAgeSeconds(), null, s.nonceUnused(), s.currentSlot()),
                    new StateFacts(s.dailyExposureCents(), s.assetExposureAfterBps(), s.oracleAgeSeconds(), s.agentPermitted(), null, s.currentSlot()),
                    new StateFacts(s.dailyExposureCents(), s.assetExposureAfterBps(), s.oracleAgeSeconds(), s.agentPermitted(), s.nonceUnused(), null));
            for (StateFacts u : unknowns) {
                blanked++;
                assertThat(DeterministicPolicyEngine.evaluate(run.rules, new PolicyInput(r.o().input().intent(), u)).decision())
                        .isEqualTo(PolicyVerdict.Decision.DENY);
            }
            assertThat(DeterministicPolicyEngine.evaluate(run.rules, new PolicyInput(null, s)).decision()).isEqualTo(PolicyVerdict.Decision.DENY);
        }
        assertThat(inputs).isGreaterThan(500);
        // (b) the envelope: a valid intent under each unavailable dependency
        List<Case> fresh = new CorpusGenerator(SEED + 13, run.rules, agents, 3_000_000L).generate(60, 0).stream()
                .filter(c -> c.json().contains("\"BUY\"") || c.json().contains("\"SELL\"") || c.json().contains("\"SWAP\"")).toList();
        assertThat(fresh).isNotEmpty();
        int envelope = 0;
        for (Case c : fresh) {
            run.layer.advanceSlots(1);
            run.layer.oracle().ageSeconds(null);
            Outcome oracleOffline = run.layer.submit(c.json(), c.signature());
            run.layer.oracle().ageSeconds(5L);
            assertThat(oracleOffline.result()).isEqualTo(Result.DENIED);
            assertThat(oracleOffline.verdict().failedPredicates()).contains(PolicyPredicate.ORACLE_FRESH);

            run.layer.stateReadable(false);
            Outcome partition = run.layer.submit(c.json(), c.signature());
            run.layer.stateReadable(true);
            assertThat(partition.result()).isEqualTo(Result.DENIED);
            assertThat(partition.verdict().failedPredicates()).contains(PolicyPredicate.NONCE_UNUSED, PolicyPredicate.DAILY_LIMIT);

            run.layer.slotOnline(false);
            Outcome noSlot = run.layer.submit(c.json(), c.signature());
            run.layer.slotOnline(true);
            assertThat(noSlot.result()).isEqualTo(Result.DENIED);
            assertThat(noSlot.verdict().failedPredicates()).contains(PolicyPredicate.NOT_EXPIRED);

            run.layer.rules(null);
            Outcome noPolicy = run.layer.submit(c.json(), c.signature());
            run.layer.rules(run.rules);
            assertThat(noPolicy.result()).isEqualTo(Result.DENIED);
            assertThat(noPolicy.stage()).isEqualTo(AuthorityLayer.Stage.POLICY);
            envelope += 4;
        }
        record("I6", "Unknown ⇒ Deny", inputs + " authorized inputs × 6 blanked facts (" + blanked + " engine calls) + " + envelope + " envelope calls with a dependency down");
    }

    @Test
    void i7_reproducibility_sameInputSameVerdictInEveryValidator() {
        int compared = 0;
        for (Row r : run.rows) {
            if (r.o().verdict() == null) {
                continue;
            }
            compared++;
            PolicyInput in = r.o().input();
            PolicyVerdict engineAgain = DeterministicPolicyEngine.evaluate(run.rules, in);
            PolicyVerdict reference = ReferencePolicyValidator.verdict(run.rules, in);
            assertThat(engineAgain.hash()).isEqualTo(r.o().verdict().hash());
            assertThat(reference.hash()).as("#%s %s", r.c().id(), in.canonicalJson()).isEqualTo(r.o().verdict().hash());
            assertThat(reference.decision()).isEqualTo(r.o().verdict().decision());
            assertThat(reference.failedPredicates()).isEqualTo(r.o().verdict().failedPredicates());
        }
        // and a second run of the same corpus, on a fresh layer, reproduces every hash
        BenchmarkRun again = BenchmarkRun.of(SEED, 3_000, 1_500, agents);
        assertThat(again.verdictHashes()).isEqualTo(run.verdictHashes());
        assertThat(compared).isGreaterThan(2_000);
        record("I7", "same (I, S, R) ⇒ same verdict hash in the engine, in the independent validator and in a second run", compared + " verdicts × 3 evaluations");
    }
}
