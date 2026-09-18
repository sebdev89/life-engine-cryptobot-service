package io.lifeengine.cryptobot.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The golden file of the deterministic risk engine (KAN-392, Endgame §5): 200 canonical inputs
 * with the {@code inputHash} and {@code outputHash} the engine produced when the file was
 * written, plus the {@code weightsHash} and version it ran with. The test re-runs every input
 * on this JVM and demands the same 200 output hashes byte for byte.
 *
 * <p>Why it holds anywhere: the engine is integer arithmetic on {@code int}/{@code long}, with
 * no floating point, no clock, no randomness, no threads and no native code; Java specifies all
 * of that exactly, so the hash does not depend on the CPU, the OS or the JIT. The file was
 * generated on Pop!_OS x86_64 and the CI runner (also x86_64, {@code le-ci}) has to agree; a Mac
 * on ARM64 would too, for the same reason. If a hash ever differs, the model changed — a
 * threshold, a rule, a tie-break, the canonical form of the input or of the verdict — and
 * that is a new model: bump {@link DeterministicRiskEngine#VERSION} (and the weights file's
 * {@code version}), regenerate the golden ({@code -Drisk.golden.write=<path>}), and say so in the
 * PR. Silently regenerating without a bump makes every earlier {@code RISK_DECISION} receipt
 * claim a reproducibility it no longer has.
 *
 * <p>The inputs are inline (not regenerated from a seed at test time) so the file is the
 * evidence, independent of {@link Random}'s algorithm; {@link #randomInput(Random)} is only the
 * generator used to write it, and the first cases are hand-picked edge cases (empty portfolio,
 * exact thresholds, ties, unpriced and dust positions).
 */
class RiskEngineGoldenTest {

    static final String GOLDEN_RESOURCE = "risk-engine/golden-v1.json";
    static final int CASES = 200;
    static final long SEED = 392L;

    static final String SOL = "So11111111111111111111111111111111111111112";
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    static final String JUP = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN";
    static final String BONK = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";
    static final String RAY = "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R";
    static final String WIF = "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm";
    static final String UNKNOWN_A = "Unknown1111111111111111111111111111111111A";
    static final String UNKNOWN_B = "Unknown1111111111111111111111111111111111B";

    /** {symbol, mint, stable} — the pool the generator draws from. */
    static final String[][] POOL = {
            {"SOL", SOL, "false"}, {"USDC", USDC, "true"}, {"USDT", USDT, "true"}, {"JUP", JUP, "false"},
            {"BONK", BONK, "false"}, {"RAY", RAY, "false"}, {"WIF", WIF, "false"},
    };

    final DeterministicRiskEngine engine = DeterministicRiskEngine.v1();
    final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("the shipped weights are the ones the golden was written with (a changed hash needs a version bump and a new golden)")
    void goldenNamesTheShippedModel() throws IOException {
        Map<String, Object> golden = readGolden();
        assertThat(golden.get("engine")).isEqualTo(DeterministicRiskEngine.ID);
        assertThat(golden.get("version"))
                .as("engine version drifted from the golden: bump DeterministicRiskEngine.VERSION together with a regenerated golden")
                .isEqualTo(DeterministicRiskEngine.VERSION);
        assertThat(golden.get("weightsHash"))
                .as("weights-v1.json changed but the engine version did not: that is a new model, bump the version and regenerate the golden")
                .isEqualTo(engine.weightsHash());
        assertThat(golden.get("inputSchema")).isEqualTo(RiskInput.SCHEMA);
        assertThat(golden.get("outputSchema")).isEqualTo(RiskVerdict.SCHEMA);
    }

    @Test
    @DisplayName("200 golden inputs → 200 identical input hashes and output hashes on this JVM")
    @SuppressWarnings("unchecked")
    void everyGoldenCaseReproduces() throws IOException {
        Map<String, Object> golden = readGolden();
        List<Map<String, Object>> cases = (List<Map<String, Object>>) golden.get("cases");
        assertThat(cases).hasSize(CASES);
        List<String> drift = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            RiskInput input = RiskInput.fromMap((Map<String, Object>) c.get("input"));
            String inputHash = input.hash();
            DeterministicDecision d = engine.decide(input);
            if (!inputHash.equals(c.get("inputHash"))) {
                drift.add(c.get("id") + ": input " + c.get("inputHash") + " → " + inputHash);
            }
            if (!d.outputHash().equals(c.get("outputHash"))) {
                drift.add(c.get("id") + ": output " + c.get("outputHash") + " → " + d.outputHash());
            }
            // The verdict summary is stored too, so a drift is readable without decoding hashes.
            assertThat(d.output().action().name()).as("action of " + c.get("id")).isEqualTo(c.get("action"));
            assertThat(d.output().riskBucket()).as("riskBucket of " + c.get("id")).isEqualTo(c.get("riskBucket"));
        }
        assertThat(drift).as("golden drift — the model changed without a version bump").isEmpty();
    }

    @Test
    @DisplayName("running the golden twice in the same JVM, and once through the stored trees, gives the same bytes")
    @SuppressWarnings("unchecked")
    void reExecutionIsIdempotent() throws IOException {
        Map<String, Object> golden = readGolden();
        for (Map<String, Object> c : (List<Map<String, Object>>) golden.get("cases")) {
            Map<String, Object> tree = (Map<String, Object>) c.get("input");
            RiskInput a = RiskInput.fromMap(tree);
            RiskInput b = RiskInput.fromMap(RiskInput.fromMap(tree).toMap());
            assertThat(engine.verdict(a).canonicalBytes()).isEqualTo(engine.verdict(b).canonicalBytes());
        }
    }

    /**
     * Writes the golden file. Not a test of anything: enabled only with
     * {@code -Drisk.golden.write=/abs/path/golden-v1.json}, and only after a deliberate version bump.
     */
    @Test
    @EnabledIfSystemProperty(named = "risk.golden.write", matches = ".+")
    void writeGolden() throws IOException {
        Path out = Path.of(System.getProperty("risk.golden.write"));
        Map<String, Object> golden = new LinkedHashMap<>();
        golden.put("engine", DeterministicRiskEngine.ID);
        golden.put("version", DeterministicRiskEngine.VERSION);
        golden.put("weightsHash", engine.weightsHash());
        golden.put("inputSchema", RiskInput.SCHEMA);
        golden.put("outputSchema", RiskVerdict.SCHEMA);
        golden.put("generator", "RiskEngineGoldenTest.randomInput(Random(" + SEED + ")) after " + edgeCases().size() + " hand-picked edge cases");
        List<Map<String, Object>> cases = new ArrayList<>();
        List<RiskInput> inputs = new ArrayList<>(edgeCases());
        Random rnd = new Random(SEED);
        while (inputs.size() < CASES) {
            inputs.add(randomInput(rnd));
        }
        for (int i = 0; i < inputs.size(); i++) {
            RiskInput in = inputs.get(i);
            DeterministicDecision d = engine.decide(in);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", String.format("g%03d", i));
            c.put("input", in.toMap());
            c.put("inputHash", d.inputHash());
            c.put("outputHash", d.outputHash());
            c.put("action", d.output().action().name());
            c.put("riskBucket", d.output().riskBucket());
            c.put("reasons", d.output().reasons());
            cases.add(c);
        }
        golden.put("cases", cases);
        Files.createDirectories(out.getParent());
        Files.write(out, json.writeValueAsBytes(golden));
    }

    // ---- generator ---------------------------------------------------------------------------------

    static List<RiskInput> edgeCases() {
        List<RiskInput> l = new ArrayList<>();
        // empty portfolio, with and without history
        l.add(new RiskInput(List.of(), 0, null, null));
        l.add(new RiskInput(List.of(), 0, -10_000, "SOL"));
        // exact thresholds: 60 % / 40 % concentration, 10 % stables, ±5 % move, $1 dust
        l.add(new RiskInput(List.of(pos("SOL", SOL, 6000, 600_000_000L, false), pos("USDC", USDC, 4000, 400_000_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 5999, 599_900_000L, false), pos("USDC", USDC, 4001, 400_100_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 4000, 400_000_000L, false), pos("USDC", USDC, 6000, 600_000_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 3999, 399_900_000L, false), pos("USDC", USDC, 6001, 600_100_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 9000, 900_000_000L, false), pos("USDC", USDC, 1000, 100_000_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 9001, 900_100_000L, false), pos("USDC", USDC, 999, 99_900_000L, true)), 1_000_000_000L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 3000, 300L, false), pos("USDC", USDC, 7000, 700L, true)), 1_000L, 500, "SOL"));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 3000, 300L, false), pos("USDC", USDC, 7000, 700L, true)), 1_000L, -500, "SOL"));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 3000, 300L, false), pos("USDC", USDC, 7000, 700L, true)), 1_000L, 499, "SOL"));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 3000, 300L, false), pos("USDC", USDC, 7000, 700L, true)), 1_000L, -499, "SOL"));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 5000, 100_000_000L, false), pos("USDC", USDC, 5000, 100_000_000L, true),
                pos("JUP", JUP, 0, 999_999L, false)), 200_999_999L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 5000, 100_000_000L, false), pos("USDC", USDC, 5000, 100_000_000L, true),
                pos("JUP", JUP, 0, 1_000_000L, false)), 201_000_000L, null, null));
        // ties: two assets at the same HIGH exposure; three at the same MEDIUM exposure
        l.add(new RiskInput(List.of(pos("SOL", SOL, 6000, 6L, false), pos("JUP", JUP, 6000, 6L, false)), 12L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 4000, 4L, false), pos("JUP", JUP, 4000, 4L, false), pos("RAY", RAY, 4000, 4L, false)), 12L, null, null));
        // all stable; all unpriced; one held-but-zero-value; a stable that is itself concentrated (ignored by the rule)
        l.add(new RiskInput(List.of(pos("USDC", USDC, 5000, 50L, true), pos("USDT", USDT, 5000, 50L, true)), 100L, 0, "USDC"));
        l.add(new RiskInput(List.of(new RiskInput.PositionInput("UNKNOWN-A", UNKNOWN_A, 0, 0, true, false, false),
                new RiskInput.PositionInput("UNKNOWN-B", UNKNOWN_B, 0, 0, true, false, false)), 0L, null, null));
        l.add(new RiskInput(List.of(pos("SOL", SOL, 10_000, 1L, false), new RiskInput.PositionInput("JUP", JUP, 0, 0, false, true, false)), 1L, null, null));
        l.add(new RiskInput(List.of(pos("USDC", USDC, 9500, 9500L, true), pos("SOL", SOL, 500, 500L, false)), 10_000L, 12_345, "USDC"));
        // the maximum penalty stack: five unpriced, no history, HIGH concentration, sharp drop
        l.add(new RiskInput(List.of(pos("SOL", SOL, 10_000, 100L, false),
                new RiskInput.PositionInput("U1", "U1111111111111111111111111111111111111111", 0, 0, true, false, false),
                new RiskInput.PositionInput("U2", "U2111111111111111111111111111111111111111", 0, 0, true, false, false),
                new RiskInput.PositionInput("U3", "U3111111111111111111111111111111111111111", 0, 0, true, false, false),
                new RiskInput.PositionInput("U4", "U4111111111111111111111111111111111111111", 0, 0, true, false, false),
                new RiskInput.PositionInput("U5", "U5111111111111111111111111111111111111111", 0, 0, true, false, false)), 100L, -9_999, "SOL"));
        return l;
    }

    /** A plausible quantised portfolio: 1–7 positions from the pool, exposures that sum to ≤ 10 000 bps, some dust / unpriced / empty. */
    static RiskInput randomInput(Random rnd) {
        int n = 1 + rnd.nextInt(7);
        List<Integer> picks = new ArrayList<>();
        while (picks.size() < n) {
            int p = rnd.nextInt(POOL.length);
            if (!picks.contains(p)) {
                picks.add(p);
            }
        }
        long totalMicros = switch (rnd.nextInt(4)) {
            case 0 -> 1_000_000L + rnd.nextInt(50_000_000);           // $1 – $51
            case 1 -> 100_000_000L + rnd.nextInt(1_000_000_000);      // $100 – $1 100
            case 2 -> 10_000_000_000L + rnd.nextInt(1_000_000_000);   // $10 000 – $11 000
            default -> 1_000_000_000_000L + rnd.nextInt(1_000_000);   // $1 000 000
        };
        // exposures: random weights normalised to 10 000 bps, remainder to the first position
        int[] raw = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            raw[i] = 1 + rnd.nextInt(1000);
            sum += raw[i];
        }
        int[] bps = new int[n];
        int assigned = 0;
        for (int i = 1; i < n; i++) {
            bps[i] = (int) ((long) raw[i] * 10_000 / sum);
            assigned += bps[i];
        }
        bps[0] = 10_000 - assigned;
        List<RiskInput.PositionInput> positions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String[] p = POOL[picks.get(i)];
            boolean stable = Boolean.parseBoolean(p[2]);
            long micros = totalMicros * bps[i] / 10_000;
            if (rnd.nextInt(10) == 0) {
                micros = rnd.nextInt(1_000_000); // force dust regardless of share
            }
            boolean held = rnd.nextInt(12) != 0;
            positions.add(new RiskInput.PositionInput(p[0], p[1], bps[i], micros, held, true, stable));
        }
        int unpriced = rnd.nextInt(4) == 0 ? 1 + rnd.nextInt(3) : 0;
        for (int u = 0; u < unpriced; u++) {
            String mint = "Unpriced" + u + "11111111111111111111111111111111111" + rnd.nextInt(10);
            positions.add(new RiskInput.PositionInput("UNK-" + u, mint, 0, 0, rnd.nextInt(5) != 0, false, false));
        }
        Integer delta = rnd.nextInt(3) == 0 ? null : rnd.nextInt(6001) - 3000;
        String largest = delta == null ? null : positions.get(rnd.nextInt(positions.size())).asset();
        return new RiskInput(positions, totalMicros, delta, largest);
    }

    static RiskInput.PositionInput pos(String asset, String mint, int bps, long micros, boolean stable) {
        return new RiskInput.PositionInput(asset, mint, bps, micros, true, true, stable);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readGolden() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(GOLDEN_RESOURCE)) {
            assertThat(in).as("golden file " + GOLDEN_RESOURCE + " on the test classpath").isNotNull();
            return json.readValue(in, Map.class);
        }
    }
}
