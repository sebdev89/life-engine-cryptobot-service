package io.lifeengine.cryptobot.application.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.trading.risk.DeterministicDecision;
import io.lifeengine.cryptobot.trading.risk.DeterministicRiskEngine;
import io.lifeengine.cryptobot.trading.risk.RiskInput;
import io.lifeengine.cryptobot.trading.risk.RiskVerdict;
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
 * L1 re-execution (KAN-392, Endgame §10): {@code verify} runs the engine again on the stored
 * input and says, per reason code, why a receipt is or is not reproduced. A receipt is never
 * marked reproduced because it says so — every hash is recomputed from the trees.
 */
class DeterministicReproducerTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final String TENANT = OWNER.toString();
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");
    static final String SOL = "So11111111111111111111111111111111111111112";
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    final DeterministicRiskEngine engine = DeterministicRiskEngine.v1();
    final DeterministicReproducer reproducer = new DeterministicReproducer(engine);
    final ReceiptSigningKey key = ReceiptSigningKey.generate("unit-key");
    ReceiptService service;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        service = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), key, reproducer, io.lifeengine.cryptobot.observability.CryptobotMetrics.noop());
    }

    static RiskInput concentrated() {
        return new RiskInput(List.of(
                new RiskInput.PositionInput("SOL", SOL, 7000, 700_000_000L, true, true, false),
                new RiskInput.PositionInput("USDC", USDC, 3000, 300_000_000L, true, true, true)), 1_000_000_000L, null, null);
    }

    /** A RISK_DECISION body exactly as {@code Receipts.riskDecision} builds it, for a decision {@code d}. */
    static ReceiptBody riskBody(DeterministicDecision d, String nonce, String outputHash, ReceiptBody.Engine engine, ReproducibilityLevel level) {
        return new ReceiptBody(null, ReceiptKind.RISK_DECISION, TENANT, OWNER.toString(), "risk-engine@1.0.0", List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot")), new ReceiptInput(ReceiptInput.RISK_INPUT, d.inputHash())),
                null, null, engine, null, Map.of(), new ReceiptBody.Output(outputHash, RiskVerdict.SCHEMA, null), new ReceiptBody.Compute(null, null, 1, null), null,
                level, T0, T0.plusMillis(2), nonce, null);
    }

    static ReceiptBody riskBody(DeterministicDecision d, String nonce) {
        return riskBody(d, nonce, d.outputHash(), new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()), ReproducibilityLevel.L1_REPRODUCIBLE);
    }

    static DeterministicInference inference(DeterministicDecision d) {
        return DeterministicInference.unbound(TENANT, d.engineId(), d.engineVersion(), d.weightsHash(), d.input().toMap(), d.output().toMap());
    }

    @Test
    @DisplayName("an issued RISK_DECISION re-executes to the same output hash: reproduced = true, reason REPRODUCED, hashes reported")
    void reproducesAnIssuedDecision() {
        DeterministicDecision d = engine.decide(concentrated());
        IntelligenceReceipt r = service.issue(ReceiptDraft.of(riskBody(d, "risk-1")).withInference(inference(d))).block();
        assertThat(InMemoryControlPlaneRepositories.INFERENCES).containsKey(r.receiptHash());

        ReceiptService.Verification v = service.verify(OWNER, r.receiptHash()).block();
        assertThat(v.valid()).isTrue();
        assertThat(v.level()).isEqualTo(ReproducibilityLevel.L1_REPRODUCIBLE);
        assertThat(v.reproduced()).isTrue();
        DeterministicReproducer.Reproduction rep = v.reproduction();
        assertThat(rep.reason()).isEqualTo(DeterministicReproducer.REASON_OK);
        assertThat(rep.engineId()).isEqualTo("risk-engine");
        assertThat(rep.engineVersion()).isEqualTo(DeterministicRiskEngine.VERSION);
        assertThat(rep.weightsHash()).isEqualTo(engine.weightsHash());
        assertThat(rep.inputHash()).isEqualTo(d.inputHash());
        assertThat(rep.expectedOutputHash()).isEqualTo(d.outputHash());
        assertThat(rep.actualOutputHash()).isEqualTo(d.outputHash());
    }

    @Test
    @DisplayName("issue refuses an L1 receipt whose inference does not match it: wrong output hash, undeclared input, other engine, other tenant, or L0")
    void issueRefusesInconsistentInference() {
        DeterministicDecision d = engine.decide(concentrated());
        DeterministicInference inf = inference(d);
        // output hash in the body is not the hash of the stored output tree
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(riskBody(d, "n1", Digests.sha256("forged"), new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()),
                ReproducibilityLevel.L1_REPRODUCIBLE)).withInference(inf)).block()).hasMessageContaining("output hash");
        // engine differs
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(riskBody(d, "n2", d.outputHash(), new ReceiptBody.Engine(d.engineId(), "9.9.9", d.weightsHash()),
                ReproducibilityLevel.L1_REPRODUCIBLE)).withInference(inf)).block()).hasMessageContaining("engine");
        // not L1
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(riskBody(d, "n3", d.outputHash(), new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()),
                ReproducibilityLevel.L0_SIGNED)).withInference(inf)).block()).hasMessageContaining("L1_REPRODUCIBLE");
        // input not declared as RISK_INPUT
        ReceiptBody undeclared = new ReceiptBody(null, ReceiptKind.RISK_DECISION, TENANT, OWNER.toString(), "risk-engine@1.0.0", List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot"))), null, null,
                new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()), null, Map.of(), new ReceiptBody.Output(d.outputHash(), RiskVerdict.SCHEMA, null),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L1_REPRODUCIBLE, T0, T0, "n4", null);
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(undeclared).withInference(inf)).block()).hasMessageContaining("RISK_INPUT");
        // other tenant
        DeterministicInference foreign = DeterministicInference.unbound("someone-else", d.engineId(), d.engineVersion(), d.weightsHash(), d.input().toMap(), d.output().toMap());
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(riskBody(d, "n5")).withInference(foreign)).block()).hasMessageContaining("tenant");
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).isEmpty();
    }

    @Test
    @DisplayName("an L1 claim from a known engine with nothing stored is false (INFERENCE_MISSING); L0 and unknown engines are null, not false")
    void missingOrUnknown() {
        DeterministicDecision d = engine.decide(concentrated());
        IntelligenceReceipt noRow = service.issue(ReceiptDraft.of(riskBody(d, "risk-1"))).block(); // no inference attached
        ReceiptService.Verification v = service.verify(OWNER, noRow.receiptHash()).block();
        assertThat(v.reproduced()).isFalse();
        assertThat(v.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_INFERENCE_MISSING);
        assertThat(v.hashMatchesCanonical() && v.signatureValid() && v.parentsPresent()).as("hash, body, signature and parents still hold").isTrue();
        assertThat(v.valid()).as("but a false L1 claim makes the receipt invalid as a whole").isFalse();

        ReceiptBody planner = riskBody(d, "plan-1", d.outputHash(), new ReceiptBody.Engine("rebalance-planner", "1.0.0", Digests.sha256("planner")), ReproducibilityLevel.L1_REPRODUCIBLE);
        IntelligenceReceipt p = service.issue(ReceiptDraft.of(planner)).block();
        ReceiptService.Verification vp = service.verify(OWNER, p.receiptHash()).block();
        assertThat(vp.reproduced()).isNull();
        assertThat(vp.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_ENGINE_UNKNOWN);
        assertThat(vp.reproduction().engineId()).isEqualTo("rebalance-planner");

        ReceiptBody l0 = riskBody(d, "l0-1", d.outputHash(), null, ReproducibilityLevel.L0_SIGNED);
        IntelligenceReceipt z = service.issue(ReceiptDraft.of(l0)).block();
        ReceiptService.Verification vz = service.verify(OWNER, z.receiptHash()).block();
        assertThat(vz.reproduced()).isNull();
        assertThat(vz.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_NOT_L1);
    }

    @Test
    @DisplayName("the stored row is recomputed, never trusted: a swapped input, a swapped output, or a forged verdict each fail on its own reason")
    void tamperedRowsFailOnTheirOwnCheck() {
        DeterministicDecision d = engine.decide(concentrated());
        IntelligenceReceipt r = service.issue(ReceiptDraft.of(riskBody(d, "risk-1")).withInference(inference(d))).block();
        DeterministicInference stored = InMemoryControlPlaneRepositories.INFERENCES.get(r.receiptHash());

        // 1. Another input under the same receipt: the tree no longer hashes to the receipt's RISK_INPUT.
        DeterministicDecision other = engine.decide(new RiskInput(List.of(new RiskInput.PositionInput("USDC", USDC, 10_000, 1_000_000_000L, true, true, true)), 1_000_000_000L, null, null));
        DeterministicInference swappedInput = new DeterministicInference(r.receiptHash(), TENANT, d.engineId(), d.engineVersion(), d.weightsHash(),
                other.input().toMap(), stored.inputHash(), stored.output(), stored.outputHash());
        DeterministicReproducer.Reproduction a = reproducer.reproduce(r, swappedInput);
        assertThat(a.reproduced()).isFalse();
        assertThat(a.reason()).isEqualTo(DeterministicReproducer.REASON_INPUT_MISMATCH);

        // 2. Another output tree: hashes to something the receipt does not name.
        DeterministicInference swappedOutput = new DeterministicInference(r.receiptHash(), TENANT, d.engineId(), d.engineVersion(), d.weightsHash(),
                stored.input(), stored.inputHash(), other.output().toMap(), stored.outputHash());
        DeterministicReproducer.Reproduction b = reproducer.reproduce(r, swappedOutput);
        assertThat(b.reproduced()).isFalse();
        assertThat(b.reason()).isEqualTo(DeterministicReproducer.REASON_STORED_OUTPUT_MISMATCH);
        assertThat(b.actualOutputHash()).isEqualTo(other.outputHash());

        // 3. A forged verdict that the receipt *does* name (HOLD instead of SELL): consistent row, but the engine disagrees.
        Map<String, Object> forgedTree = new HashMap<>(d.output().toMap());
        forgedTree.put("action", "HOLD");
        forgedTree.remove("asset");
        RiskVerdict forged = RiskVerdict.fromMap(forgedTree);
        ReceiptBody forgedBody = riskBody(d, "risk-forged", forged.hash(), new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()), ReproducibilityLevel.L1_REPRODUCIBLE);
        DeterministicInference forgedInference = DeterministicInference.unbound(TENANT, d.engineId(), d.engineVersion(), d.weightsHash(), d.input().toMap(), forged.toMap());
        IntelligenceReceipt f = service.issue(ReceiptDraft.of(forgedBody).withInference(forgedInference)).block();
        ReceiptService.Verification vf = service.verify(OWNER, f.receiptHash()).block();
        assertThat(vf.reproduced()).isFalse();
        assertThat(vf.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_OUTPUT_MISMATCH);
        assertThat(vf.reproduction().expectedOutputHash()).isEqualTo(forged.hash());
        assertThat(vf.reproduction().actualOutputHash()).isEqualTo(d.outputHash());
        assertThat(vf.signatureValid() && vf.hashMatchesCanonical()).as("signature and hash are fine: the receipt is authentic").isTrue();
        assertThat(vf.valid()).as("its L1 claim is false, so the receipt is not valid").isFalse();

        // 4. A weights hash this build does not ship: cannot be re-run, honest false.
        ReceiptBody oldWeights = riskBody(d, "risk-old", d.outputHash(), new ReceiptBody.Engine(d.engineId(), d.engineVersion(), Digests.sha256("weights-v0")), ReproducibilityLevel.L1_REPRODUCIBLE);
        IntelligenceReceipt o = service.seal(oldWeights);
        DeterministicInference oldRow = new DeterministicInference(o.receiptHash(), TENANT, d.engineId(), d.engineVersion(), Digests.sha256("weights-v0"),
                stored.input(), stored.inputHash(), stored.output(), stored.outputHash());
        DeterministicReproducer.Reproduction w = reproducer.reproduce(o, oldRow);
        assertThat(w.reproduced()).isFalse();
        assertThat(w.reason()).isEqualTo(DeterministicReproducer.REASON_WEIGHTS_UNAVAILABLE);
    }
}
