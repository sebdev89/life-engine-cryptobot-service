package io.lifeengine.cryptobot.trading.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic core (KAN-392, Endgame §5) as a pure function: integer frontier, canonical
 * input/output, fixed tie-breaks, and the weights file hashed as the model's identity.
 */
class DeterministicRiskEngineTest {

    static final String SOL = "So11111111111111111111111111111111111111112";
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String JUP = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN";
    static final String UNKNOWN = "Unknown111111111111111111111111111111111111";

    final DeterministicRiskEngine engine = DeterministicRiskEngine.v1();

    static RiskInput.PositionInput pos(String asset, String mint, int bps, long micros, boolean stable) {
        return new RiskInput.PositionInput(asset, mint, bps, micros, true, true, stable);
    }

    @Test
    @DisplayName("the weights file is integer-only, names the engine, and its hash is the canonical JCS hash of the file")
    void weightsAreVersionedAndHashed() {
        RiskWeights w = engine.weights();
        assertThat(w.engine()).isEqualTo(DeterministicRiskEngine.ID);
        assertThat(w.version()).isEqualTo(DeterministicRiskEngine.VERSION);
        assertThat(w.hash()).isEqualTo(Digests.sha256(JsonCanonicalizer.canonicalBytes(w.tree())));
        assertThat(w.concentrationHighBps()).isEqualTo(6000);
        assertThat(w.concentrationMediumBps()).isEqualTo(4000);
        assertThat(w.minStableBps()).isEqualTo(1000);
        assertThat(w.dustUsdMicros()).isEqualTo(1_000_000L);
        assertThat(w.sharpMoveBps()).isEqualTo(500);
        // An edited threshold is a different model.
        Map<String, Object> edited = new HashMap<>(w.tree());
        Map<String, Object> thresholds = new HashMap<>((Map<String, Object>) w.tree().get("thresholds"));
        thresholds.put("concentrationHighBps", 6500);
        edited.put("thresholds", thresholds);
        assertThat(RiskWeights.fromTree(edited).hash()).isNotEqualTo(w.hash());
        // A float in the file never loads: the model has no floating point anywhere.
        thresholds.put("concentrationHighBps", 65.5);
        assertThatThrownBy(() -> RiskWeights.fromTree(edited)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the input is canonical: position order does not matter, optionals are omitted, duplicates and floats are refused")
    void inputIsCanonical() {
        List<RiskInput.PositionInput> a = List.of(pos("SOL", SOL, 7000, 700_000_000L, false), pos("USDC", USDC, 3000, 300_000_000L, true));
        List<RiskInput.PositionInput> b = new ArrayList<>(a);
        Collections.reverse(b);
        RiskInput x = new RiskInput(a, 1_000_000_000L, null, null);
        RiskInput y = new RiskInput(b, 1_000_000_000L, null, null);
        assertThat(y.hash()).isEqualTo(x.hash());
        String canonical = new String(x.canonicalBytes(), StandardCharsets.UTF_8);
        assertThat(canonical).doesNotContain("deltaBps").doesNotContain("largestMoveAsset").doesNotContain(" ").doesNotContain("null");
        assertThat(canonical).startsWith("{\"positions\":[{\"asset\":\"USDC\"");  // sorted by mint: EPjF… < So11…
        assertThat(RiskInput.fromMap(x.toMap()).hash()).isEqualTo(x.hash());
        assertThatThrownBy(() -> new RiskInput(List.of(pos("SOL", SOL, 5000, 1, false), pos("SOL2", SOL, 5000, 1, false)), 2, null, null))
                .hasMessageContaining("duplicate mint");
        assertThatThrownBy(() -> new RiskInput.PositionInput("SOL", SOL, 10_001, 1, true, true, false)).hasMessageContaining("exposureBps");
    }

    @Test
    @DisplayName("SOL 70 % / USDC 30 %: SELL SOL, CONCENTRATION_HIGH, risk bucket from the score, max position lowered")
    void concentratedPortfolioSells() {
        RiskInput in = new RiskInput(List.of(pos("SOL", SOL, 7000, 700_000_000L, false), pos("USDC", USDC, 3000, 300_000_000L, true)), 1_000_000_000L, null, null);
        RiskVerdict v = engine.verdict(in);
        assertThat(v.action()).isEqualTo(RiskVerdict.Action.SELL);
        assertThat(v.asset()).isEqualTo("SOL");
        assertThat(v.overall()).isEqualTo(RiskSeverity.HIGH);
        assertThat(v.score()).isEqualTo(40);
        assertThat(v.riskBucket()).isEqualTo(4);
        assertThat(v.confidenceBucket()).isEqualTo(8); // no history
        assertThat(v.maxPositionBucket()).isEqualTo(3); // 4 − 1 for HIGH
        assertThat(v.reasons()).containsExactly("CONCENTRATION_HIGH:SOL");
        assertThat(v.signals()).hasSize(1);
        assertThat(v.signals().get(0).metric()).isEqualTo(7000);
        assertThat(v.signals().get(0).threshold()).isEqualTo(6000);
        assertThat(RiskVerdict.fromMap(v.toMap()).hash()).isEqualTo(v.hash());
        assertThat(new String(v.canonicalBytes(), StandardCharsets.UTF_8)).doesNotContain("%").doesNotContain("portfolio");
    }

    @Test
    @DisplayName("balanced: HOLD with no signals; no stables: BUY; sharp drop: HOLD and a tighter max position; empty: AVOID")
    void actionTable() {
        RiskInput balanced = new RiskInput(List.of(pos("SOL", SOL, 3000, 300_000_000L, false), pos("USDC", USDC, 3500, 350_000_000L, true),
                pos("JUP", JUP, 3500, 350_000_000L, false)), 1_000_000_000L, 100, "JUP");
        RiskVerdict v = engine.verdict(balanced);
        assertThat(v.action()).isEqualTo(RiskVerdict.Action.HOLD);
        assertThat(v.signals()).isEmpty();
        assertThat(v.score()).isZero();
        assertThat(v.riskBucket()).isZero();
        assertThat(v.confidenceBucket()).isEqualTo(9);
        assertThat(v.maxPositionBucket()).isEqualTo(4);

        RiskInput noStables = new RiskInput(List.of(pos("SOL", SOL, 5000, 500_000_000L, false), pos("JUP", JUP, 5000, 500_000_000L, false)), 1_000_000_000L, null, null);
        RiskVerdict n = engine.verdict(noStables);
        assertThat(n.action()).isEqualTo(RiskVerdict.Action.BUY);
        assertThat(n.asset()).isNull();
        assertThat(n.reasons()).containsExactly("CONCENTRATION_MEDIUM:JUP", "CONCENTRATION_MEDIUM:SOL", "NO_STABLES");
        assertThat(n.overall()).isEqualTo(RiskSeverity.MEDIUM);
        assertThat(n.score()).isEqualTo(60);

        RiskInput drop = new RiskInput(balanced.positions(), 900_000_000L, -1000, "SOL");
        RiskVerdict d = engine.verdict(drop);
        assertThat(d.action()).isEqualTo(RiskVerdict.Action.HOLD);
        assertThat(d.reasons()).containsExactly("SHARP_MOVE_DOWN");
        assertThat(d.overall()).isEqualTo(RiskSeverity.HIGH);
        assertThat(d.maxPositionBucket()).isEqualTo(2); // 4 − 1 (HIGH) − 1 (drop)
        assertThat(d.signals().get(0).asset()).isEqualTo("SOL");
        assertThat(d.signals().get(0).metric()).isEqualTo(-1000);

        RiskVerdict e = engine.verdict(new RiskInput(List.of(), 0, null, null));
        assertThat(e.action()).isEqualTo(RiskVerdict.Action.AVOID);
        assertThat(e.reasons()).containsExactly("EMPTY_PORTFOLIO");
        assertThat(e.overall()).isEqualTo(RiskSeverity.LOW);
    }

    @Test
    @DisplayName("ties are broken by the input's fixed order: two equally concentrated assets always pick the same SELL target")
    void tieBreakIsFixed() {
        RiskInput in = new RiskInput(List.of(pos("SOL", SOL, 6000, 1, false), pos("JUP", JUP, 6000, 1, false)), 2, null, null);
        // Both are HIGH at the same exposure; positions sort by mint (EPjF… < JUPy… < So11…), so JUP wins.
        assertThat(engine.verdict(in).asset()).isEqualTo("JUP");
        assertThat(engine.verdict(new RiskInput(List.of(pos("JUP", JUP, 6000, 1, false), pos("SOL", SOL, 6000, 1, false)), 2, null, null)).asset()).isEqualTo("JUP");
    }

    @Test
    @DisplayName("unpriced and dust: counted, confidence lowered, and the unpriced share is not exposure")
    void unpricedAndDust() {
        RiskInput in = new RiskInput(List.of(
                pos("SOL", SOL, 5000, 100_000_000L, false),
                pos("USDC", USDC, 5000, 100_000_000L, true),
                new RiskInput.PositionInput("JUP", JUP, 0, 50_000L, true, true, false),        // 5 cents: dust
                new RiskInput.PositionInput("UNKNOWN-Unkn", UNKNOWN, 0, 0, true, false, false)), // no price
                200_050_000L, null, null);
        RiskVerdict v = engine.verdict(in);
        assertThat(v.reasons()).containsExactly("CONCENTRATION_MEDIUM:SOL", "DUST_POSITIONS", "UNKNOWN_TOKENS");
        assertThat(v.confidenceBucket()).isEqualTo(9 - 2 - 1);
        assertThat(v.action()).isEqualTo(RiskVerdict.Action.HOLD);
    }

    @Test
    @DisplayName("same input, same bytes: 500 random inputs round-trip through their canonical maps to identical output hashes")
    void roundTripsAreStable() {
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            RiskInput in = RiskEngineGoldenTest.randomInput(rnd);
            DeterministicDecision d = engine.decide(in);
            assertThat(engine.decide(RiskInput.fromMap(in.toMap())).outputHash()).isEqualTo(d.outputHash());
            assertThat(RiskVerdict.fromMap(d.output().toMap()).hash()).isEqualTo(d.outputHash());
            assertThat(d.weightsHash()).isEqualTo(engine.weightsHash());
        }
    }
}
