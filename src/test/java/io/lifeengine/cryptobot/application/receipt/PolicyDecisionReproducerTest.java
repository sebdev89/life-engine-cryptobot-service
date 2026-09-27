package io.lifeengine.cryptobot.application.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.core.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.core.policy.PolicyRules;
import io.lifeengine.cryptobot.core.policy.PolicyRulesTest;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.trading.risk.DeterministicRiskEngine;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KAN-572: the policy layer's Decision Receipt is L1 for real — {@code verify} re-runs
 * {@link DeterministicPolicyEngine} on the stored {@code (I, S)} under the {@code R_v} this
 * build holds and compares the verdict hash. A receipt under another policy is honestly
 * "cannot re-run"; a tampered input or output is refuted, never repaired.
 */
class PolicyDecisionReproducerTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    static final String TENANT = OWNER.toString();
    static final Instant T0 = Instant.parse("2026-09-20T12:00:00Z");
    static final PolicyRules R = PolicyRulesTest.paper();

    final DeterministicReproducer reproducer = new DeterministicReproducer(DeterministicRiskEngine.v1(), R);
    final ReceiptSigningKey key = ReceiptSigningKey.generate("unit-key");
    ReceiptService service;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        service = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), key, reproducer, io.lifeengine.cryptobot.observability.CryptobotMetrics.noop());
    }

    static PolicyInput input(long tradeValueCents, Long oracleAge) {
        return new PolicyInput(new IntentFacts("crypto-agent-42", "momentum-v3", R.version(), "SOL", tradeValueCents, 30, 421_872_200L),
                new StateFacts(500_000L, 4_500, oracleAge, true, true, 421_872_118L));
    }

    static ReceiptBody body(PolicyVerdict v, PolicyInput in, String nonce, ReceiptBody.Engine engine, String outputHash) {
        return new ReceiptBody(null, ReceiptKind.RISK_DECISION, TENANT, OWNER.toString(), "policy-engine@" + v.policyVersion(), List.of(),
                List.of(new ReceiptInput(ReceiptInput.POLICY_INPUT, in.hash())), null, null, engine, null,
                Map.of("status", "BLOCKED_BY_POLICY", "blockedBy", "PRICE_DEVIATION,AUTHORIZATION", "rule.PRICE_DEVIATION", "BLOCKED: SOL: sources disagree"),
                new ReceiptBody.Output(outputHash, "policy-verdict/1", null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L1_REPRODUCIBLE, T0, T0.plusMillis(2), nonce, null);
    }

    static ReceiptDraft draft(PolicyInput in, String nonce) {
        PolicyVerdict v = DeterministicPolicyEngine.evaluate(R, in);
        return ReceiptDraft.of(body(v, in, nonce, new ReceiptBody.Engine(DeterministicPolicyEngine.ID, R.version(), R.hash()), v.hash()))
                .withInference(DeterministicInference.unbound(TENANT, DeterministicPolicyEngine.ID, R.version(), R.hash(), in.canonicalMap(), v.canonicalMap()));
    }

    @Test
    @DisplayName("the two canonicalizers agree: PolicyInput.hash() is the hash the inference store computes for the same tree")
    void policyInputHashIsTheInferenceInputHash() {
        PolicyInput in = input(50_000L, 12L);
        assertThat(DeterministicInference.hashOf(in.canonicalMap())).isEqualTo(in.hash());
        assertThat(PolicyInput.fromMap(in.canonicalMap()).hash()).isEqualTo(in.hash());
        // and a stale-oracle input (null age = absent key) round-trips too
        PolicyInput unknownAge = input(50_000L, null);
        assertThat(PolicyInput.fromMap(unknownAge.canonicalMap()).state().oracleAgeSeconds()).isNull();
        assertThat(PolicyInput.fromMap(unknownAge.canonicalMap()).hash()).isEqualTo(unknownAge.hash());
    }

    @Test
    @DisplayName("a blocked decision (DENY on ORACLE_FRESH) verifies: hash, signature and the policy engine re-run give the same verdict hash")
    void reproducesABlockedDecision() {
        PolicyInput in = input(50_000L, null); // no accepted price ⇒ no oracle age ⇒ ORACLE_FRESH fails ⇒ DENY
        IntelligenceReceipt r = service.issue(draft(in, "policy-1")).block();
        assertThat(r.body().params()).containsEntry("blockedBy", "PRICE_DEVIATION,AUTHORIZATION");
        ReceiptService.Verification v = service.verify(r).block();
        assertThat(v.valid()).isTrue();
        assertThat(v.reproduced()).isTrue();
        assertThat(v.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_OK);
        assertThat(v.reproduction().engineId()).isEqualTo("policy-engine");
        assertThat(v.reproduction().engineVersion()).isEqualTo(R.version());
        assertThat(v.reproduction().weightsHash()).isEqualTo(R.hash());
        assertThat(v.reproduction().actualOutputHash()).isEqualTo(r.body().output().hash());
        assertThat(reproducer.runPolicy(in.canonicalMap()).decision()).isEqualTo(PolicyVerdict.Decision.DENY);
    }

    @Test
    @DisplayName("an allowed decision reproduces the same way")
    void reproducesAnAllowedDecision() {
        IntelligenceReceipt r = service.issue(draft(input(50_000L, 12L), "policy-2")).block();
        ReceiptService.Verification v = service.verify(r).block();
        assertThat(v.valid()).isTrue();
        assertThat(v.reproduced()).isTrue();
    }

    @Test
    @DisplayName("a tampered stored input is refuted (INPUT_HASH_MISMATCH); a tampered output too")
    void tamperedRowsAreRefuted() {
        PolicyInput in = input(50_000L, 12L);
        IntelligenceReceipt r = service.issue(draft(in, "policy-3")).block();
        DeterministicInference stored = InMemoryControlPlaneRepositories.INFERENCES.get(r.receiptHash());
        Map<String, Object> intent = new HashMap<>((Map<String, Object>) stored.input().get("intent"));
        intent.put("trade_value_cents", 5L); // pretend the trade was tiny
        Map<String, Object> tamperedInput = new HashMap<>(stored.input());
        tamperedInput.put("intent", intent);
        DeterministicReproducer.Reproduction bad = reproducer.reproduce(r, new DeterministicInference(r.receiptHash(), TENANT, stored.engineId(),
                stored.engineVersion(), stored.weightsHash(), tamperedInput, stored.inputHash(), stored.output(), stored.outputHash()));
        assertThat(bad.reproduced()).isFalse();
        assertThat(bad.reason()).isEqualTo(DeterministicReproducer.REASON_INPUT_MISMATCH);
        Map<String, Object> tamperedOutput = new HashMap<>(stored.output());
        tamperedOutput.put("decision", "DENY"); // it was ALLOW
        DeterministicReproducer.Reproduction badOut = reproducer.reproduce(r, new DeterministicInference(r.receiptHash(), TENANT, stored.engineId(),
                stored.engineVersion(), stored.weightsHash(), stored.input(), stored.inputHash(), tamperedOutput, stored.outputHash()));
        assertThat(badOut.reproduced()).isFalse();
        assertThat(badOut.reason()).isEqualTo(DeterministicReproducer.REASON_STORED_OUTPUT_MISMATCH);
    }

    @Test
    @DisplayName("a receipt under another R_v is not re-run by this build: WEIGHTS_UNAVAILABLE, valid=false (an unverifiable L1 claim)")
    void anotherPolicyIsHonestlyNotReproducible() {
        PolicyRules other = new PolicyRules("other-v9", R.allowedAssets(), R.enabledStrategies(), R.maxTradeValueCents(), R.dailyLimitCents(),
                R.maxAssetExposureBps(), R.maxSlippageBps(), R.maxOracleAgeSeconds(), R.autonomousUpToCents(), R.secondAgentUpToCents());
        PolicyInput in = new PolicyInput(new IntentFacts("crypto-agent-42", "momentum-v3", other.version(), "SOL", 50_000L, 30, 421_872_200L),
                new StateFacts(500_000L, 4_500, 12L, true, true, 421_872_118L));
        PolicyVerdict v = DeterministicPolicyEngine.evaluate(other, in);
        IntelligenceReceipt r = service.issue(ReceiptDraft.of(body(v, in, "policy-4", new ReceiptBody.Engine(DeterministicPolicyEngine.ID, other.version(), other.hash()), v.hash()))
                .withInference(DeterministicInference.unbound(TENANT, DeterministicPolicyEngine.ID, other.version(), other.hash(), in.canonicalMap(), v.canonicalMap()))).block();
        ReceiptService.Verification ver = service.verify(r).block();
        assertThat(ver.reproduced()).isFalse();
        assertThat(ver.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_WEIGHTS_UNAVAILABLE);
        assertThat(ver.valid()).isFalse();
    }

    @Test
    @DisplayName("a build without policy rules does not know the engine: neither confirmed nor refuted")
    void withoutRulesTheEngineIsUnknown() {
        DeterministicReproducer blind = new DeterministicReproducer();
        assertThat(blind.knows(new ReceiptBody.Engine(DeterministicPolicyEngine.ID, R.version(), R.hash()))).isFalse();
        assertThatThrownBy(() -> blind.runPolicy(input(1L, 1L).canonicalMap())).isInstanceOf(IllegalStateException.class);
    }
}
