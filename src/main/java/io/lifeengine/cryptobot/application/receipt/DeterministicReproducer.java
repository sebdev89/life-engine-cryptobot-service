package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.domain.receipt.DeterministicInference;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.lifeengine.cryptobot.domain.receipt.ReceiptInput;
import io.lifeengine.cryptobot.domain.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.receipt.ReproducibilityLevel;
import io.lifeengine.cryptobot.domain.risk.DeterministicRiskEngine;
import io.lifeengine.cryptobot.domain.risk.RiskInput;
import io.lifeengine.cryptobot.domain.risk.RiskVerdict;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * L1 re-execution (Endgame §10): given a receipt that claims {@code L1_REPRODUCIBLE} and the
 * inference the store kept next to it, run the named engine again and compare. Every check is
 * reported on its own so a UI can say exactly which one failed:
 *
 * <ol>
 *   <li>the engine id + version is one this build can run ({@code risk-engine 1.0.0}; since KAN-572
 *       also {@code policy-engine R_v}, the policy in force in this process);
 *   <li>the weights hash the receipt names is the one this build ships (an older weights file
 *       cannot be re-run: honest {@code false}, not a guess);
 *   <li>the stored input tree hashes to the receipt's {@code RISK_INPUT} and the stored output
 *       tree to the receipt's {@code output.hash} (the row is consistent with the receipt);
 *   <li>the engine, run on the stored input, produces the receipt's {@code output.hash}.
 * </ol>
 *
 * <p>A receipt that claims L1 but names an engine this build does not know (the rebalance
 * planner of KAN-391, for now) is neither confirmed nor refuted: {@code reproduced = null},
 * reason {@code ENGINE_UNKNOWN}. A receipt that claims L1 from a known engine but has no stored
 * inference is {@code false}: a reproducibility claim nobody can check is not verified.
 */
@Component
public class DeterministicReproducer {

    /** The outcome of one re-execution, with the hashes that were compared. */
    public record Reproduction(
            Boolean reproduced,
            String reason,
            String engineId,
            String engineVersion,
            String weightsHash,
            String inputHash,
            String expectedOutputHash,
            String actualOutputHash) {

        static Reproduction unknown(String reason, ReceiptBody.Engine e) {
            return new Reproduction(null, reason, e == null ? null : e.id(), e == null ? null : e.version(), e == null ? null : e.weightsHash(), null, null, null);
        }

        static Reproduction failed(String reason, ReceiptBody.Engine e, String inputHash, String expected, String actual) {
            return new Reproduction(false, reason, e.id(), e.version(), e.weightsHash(), inputHash, expected, actual);
        }
    }

    public static final String REASON_NOT_L1 = "NOT_L1";
    public static final String REASON_ENGINE_UNKNOWN = "ENGINE_UNKNOWN";
    public static final String REASON_INFERENCE_MISSING = "INFERENCE_MISSING";
    public static final String REASON_WEIGHTS_UNAVAILABLE = "WEIGHTS_UNAVAILABLE";
    public static final String REASON_INPUT_MISMATCH = "INPUT_HASH_MISMATCH";
    public static final String REASON_STORED_OUTPUT_MISMATCH = "STORED_OUTPUT_HASH_MISMATCH";
    public static final String REASON_OUTPUT_MISMATCH = "OUTPUT_HASH_MISMATCH";
    public static final String REASON_INPUT_INVALID = "INPUT_INVALID";
    public static final String REASON_OK = "REPRODUCED";

    private final DeterministicRiskEngine riskEngine;
    /** {@code R_v} in force in this process; {@code null} = this build cannot re-run policy receipts (tests without a policy). */
    private final PolicyRules policyRules;

    public DeterministicReproducer() {
        this(DeterministicRiskEngine.v1(), null);
    }

    public DeterministicReproducer(DeterministicRiskEngine riskEngine) {
        this(riskEngine, null);
    }

    /** KAN-572: the policy engine's Decision Receipts are re-run under the rules this service starts with. */
    @Autowired
    public DeterministicReproducer(io.lifeengine.cryptobot.application.controlplane.PolicyEngine policy) {
        this(DeterministicRiskEngine.v1(), policy.rules());
    }

    public DeterministicReproducer(DeterministicRiskEngine riskEngine, PolicyRules policyRules) {
        this.riskEngine = Objects.requireNonNull(riskEngine, "riskEngine");
        this.policyRules = policyRules;
    }

    /** Whether this build can re-run receipts of {@code engine}. */
    public boolean knows(ReceiptBody.Engine engine) {
        return engine != null && (DeterministicRiskEngine.ID.equals(engine.id()) || (policyRules != null && DeterministicPolicyEngine.ID.equals(engine.id())));
    }

    /** {@code inference} may be {@code null} (nothing stored). Never throws: a broken row is a {@code false} with its reason. */
    public Reproduction reproduce(IntelligenceReceipt r, DeterministicInference inference) {
        ReceiptBody body = r.body();
        ReceiptBody.Engine engine = body.engine();
        if (body.reproducibility() != ReproducibilityLevel.L1_REPRODUCIBLE) {
            return Reproduction.unknown(REASON_NOT_L1, engine);
        }
        if (!knows(engine)) {
            return Reproduction.unknown(REASON_ENGINE_UNKNOWN, engine);
        }
        String expected = body.output().hash();
        if (inference == null) {
            return Reproduction.failed(REASON_INFERENCE_MISSING, engine, null, expected, null);
        }
        boolean policy = DeterministicPolicyEngine.ID.equals(engine.id());
        String version = policy ? policyRules.version() : DeterministicRiskEngine.VERSION;
        String weights = policy ? policyRules.hash() : riskEngine.weightsHash();
        if (!version.equals(engine.version()) || !weights.equals(engine.weightsHash())
                || !engine.weightsHash().equals(inference.weightsHash()) || !engine.version().equals(inference.engineVersion())) {
            return Reproduction.failed(REASON_WEIGHTS_UNAVAILABLE, engine, inference.inputHash(), expected, null);
        }
        // The stored trees must hash to what the receipt names — the row is not trusted, it is recomputed.
        String inputHash = DeterministicInference.hashOf(inference.input());
        String inputType = policy ? ReceiptInput.POLICY_INPUT : ReceiptInput.RISK_INPUT;
        String declaredInput = body.inputs().stream().filter(i -> inputType.equals(i.type())).map(ReceiptInput::hash).findFirst().orElse(null);
        if (!inputHash.equals(inference.inputHash()) || declaredInput == null || !declaredInput.equals(inputHash)) {
            return Reproduction.failed(REASON_INPUT_MISMATCH, engine, inputHash, expected, null);
        }
        String storedOutput = DeterministicInference.hashOf(inference.output());
        if (!storedOutput.equals(inference.outputHash()) || !storedOutput.equals(expected)) {
            return Reproduction.failed(REASON_STORED_OUTPUT_MISMATCH, engine, inputHash, expected, storedOutput);
        }
        // Re-execute.
        String actual;
        try {
            actual = policy
                    ? DeterministicPolicyEngine.evaluate(policyRules, PolicyInput.fromMap(inference.input())).hash()
                    : riskEngine.verdict(RiskInput.fromMap(inference.input())).hash();
        } catch (RuntimeException ex) {
            return Reproduction.failed(REASON_INPUT_INVALID, engine, inputHash, expected, null);
        }
        boolean same = actual.equals(expected);
        return new Reproduction(same, same ? REASON_OK : REASON_OUTPUT_MISMATCH, engine.id(), engine.version(), engine.weightsHash(), inputHash, expected, actual);
    }

    /** Convenience for tests and tooling: the verdict this build computes for a stored input tree. */
    public RiskVerdict run(Map<String, Object> inputTree) {
        return riskEngine.verdict(RiskInput.fromMap(inputTree));
    }

    /** KAN-572: the policy verdict this build computes for a stored {@code (I, S)} tree. */
    public PolicyVerdict runPolicy(Map<String, Object> inputTree) {
        if (policyRules == null) {
            throw new IllegalStateException("this reproducer has no policy rules");
        }
        return DeterministicPolicyEngine.evaluate(policyRules, PolicyInput.fromMap(inputTree));
    }
}
