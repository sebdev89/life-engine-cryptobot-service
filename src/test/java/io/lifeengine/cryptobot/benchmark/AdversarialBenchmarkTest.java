package io.lifeengine.cryptobot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Outcome;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Result;
import io.lifeengine.cryptobot.benchmark.BenchmarkRun.Row;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Case;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Klass;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.policy.ReferencePolicyValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Paper §36, the key experiment: 10 000 generated intents — 7 000 valid, 3 000 adversarial —
 * through the deterministic execution envelope. Expected: 7 000 authorized according to policy,
 * 3 000 deterministic rejections, <b>0 policy violations executed</b>; and §29: BlockRate,
 * reproducibility (same corpus twice ⇒ same verdict hashes; engine and independent validator
 * agree on every verdict), latency per stage. The evidence is written to
 * {@code target/benchmark/adversarial-v1.{json,md}}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdversarialBenchmarkTest {

    static final long SEED = 440L;
    static final int VALID = 7_000;
    static final int ADVERSARIAL = 3_000;

    AgentRegistry agents;
    BenchmarkRun first;
    BenchmarkRun second;
    boolean validatorsAgree;
    Map<String, Object> extra = new LinkedHashMap<>();

    @BeforeAll
    void run() {
        agents = BenchmarkRun.agents();
        first = BenchmarkRun.of(SEED, VALID, ADVERSARIAL, agents);
        second = BenchmarkRun.of(SEED, VALID, ADVERSARIAL, agents);
        validatorsAgree = first.rows.stream().allMatch(r -> r.o().verdict() == null
                || ReferencePolicyValidator.verdict(first.rules, r.o().input()).hash().equals(r.o().verdict().hash()));
    }

    @AfterAll
    void report() throws Exception {
        BenchmarkReport.write(BenchmarkReport.document(first, second, validatorsAgree, extra), "adversarial-v1");
    }

    @Test
    void theCorpusIsWhatThePaperAsksFor() {
        assertThat(first.rows).hasSize(VALID + ADVERSARIAL);
        assertThat(first.valid()).hasSize(VALID);
        assertThat(first.adversarial()).hasSize(ADVERSARIAL);
        // every attack class of §29 is present, in comparable numbers
        Map<Klass, Long> total = first.byClassTotal();
        for (Klass k : BenchmarkRun.attackClasses()) {
            assertThat(total.getOrDefault(k, 0L)).as(k.name()).isBetween(180L, 260L);
        }
        // and the valid side exercises every outcome the envelope has for a valid intent
        List<Row> valid = first.valid();
        assertThat(first.count(valid, Result.EXECUTED)).as("autonomous executions").isGreaterThan(500);
        assertThat(first.count(valid, Result.AUTHORIZED_PENDING)).as("escalations").isGreaterThan(500);
        assertThat(first.count(valid, Result.NOOP)).as("holds").isGreaterThan(200);
        assertThat(first.count(valid, Result.CANCELLED)).as("cancels").isGreaterThan(100);
    }

    @Test
    void sevenThousandValidIntentsAreAuthorizedAccordingToPolicy() {
        List<Row> rejected = first.falseRejections();
        assertThat(rejected).as("valid intents the layer refused: %s", describe(rejected)).isEmpty();
        assertThat(first.valid().stream().filter(r -> r.o().authorized()).count()).isEqualTo(VALID);
    }

    @Test
    void threeThousandAdversarialIntentsAreDeterministicallyRejected_blockRateIsOne() {
        List<Row> escaped = first.adversarial().stream().filter(r -> !r.o().blocked()).toList();
        assertThat(escaped).as("adversarial intents not blocked: %s", describe(escaped)).isEmpty();
        assertThat(first.blockRate()).isEqualTo(1.0d);
        Map<Klass, Long> blocked = first.byClassBlocked();
        Map<Klass, Long> total = first.byClassTotal();
        for (Klass k : BenchmarkRun.attackClasses()) {
            assertThat(blocked.get(k)).as("BlockRate of " + k).isEqualTo(total.get(k));
        }
    }

    @Test
    void zeroPolicyViolationsExecuted() {
        assertThat(first.violations()).isEmpty();
        assertThat(first.count(first.adversarial(), Result.EXECUTED)).isZero();
        assertThat(first.count(first.adversarial(), Result.AUTHORIZED_PENDING)).isZero();
        // and nothing that did execute broke a limit (the cheap half of the invariants; the full set is InvariantsTest)
        for (Row r : first.rows) {
            if (r.o().executed()) {
                assertThat(r.o().verdict().decision()).isEqualTo(PolicyVerdict.Decision.ALLOW);
                assertThat(r.o().verdict().policyHash()).isEqualTo(first.rules.hash());
                assertThat(r.o().signatureValid()).isTrue();
                assertThat(r.o().input().intent().tradeValueCents()).isLessThanOrEqualTo(first.rules.autonomousUpToCents());
                assertThat(first.rules.allowsAsset(r.o().input().intent().asset())).isTrue();
            }
        }
    }

    @Test
    void everyAttackIsRefusedAtTheStageThatOwnsIt() {
        // Not "denied somehow": each class must be caught by the mechanism the paper assigns to it.
        Map<Klass, Map<String, Long>> reasons = first.byClassAndReason();
        assertThat(reasons.get(Klass.SERIALIZATION_ATTACK).keySet()).containsExactly("SCHEMA");
        assertThat(reasons.get(Klass.INTEGER_OVERFLOW).keySet()).allMatch(r -> r.equals("SCHEMA") || r.contains("TRADE_WITHIN_MAX"));
        assertThat(reasons.get(Klass.INVALID_SIGNATURE).keySet()).allMatch(r -> r.contains("AGENT_PERMITTED"));
        assertThat(reasons.get(Klass.UNAUTHORIZED_AGENT).keySet()).allMatch(r -> r.contains("AGENT_PERMITTED"));
        assertThat(reasons.get(Klass.REUSED_NONCE).keySet()).allMatch(r -> r.contains("NONCE_UNUSED"));
        assertThat(reasons.get(Klass.EXPIRED_INTENT).keySet()).allMatch(r -> r.contains("NOT_EXPIRED"));
        assertThat(reasons.get(Klass.STALE_ORACLE).keySet()).allMatch(r -> r.contains("ORACLE_FRESH"));
        assertThat(reasons.get(Klass.OVERSIZED_AMOUNT).keySet()).allMatch(r -> r.contains("TRADE_WITHIN_MAX"));
        assertThat(reasons.get(Klass.INCORRECT_POLICY_VERSION).keySet()).allMatch(r -> r.equals("SCHEMA") || r.contains("POLICY_BOUND"));
        assertThat(reasons.get(Klass.INVALID_ASSET).keySet()).allMatch(r -> r.equals("SCHEMA") || r.contains("ASSET_ALLOWED"));
        // a disagreeing source is refused by the real oracle (PRICE_DEVIATION) and the trade has no value (TRADE_WITHIN_MAX)
        assertThat(reasons.get(Klass.MANIPULATED_ORACLE).keySet()).allMatch(r -> r.equals("SCHEMA") || (r.contains("TRADE_WITHIN_MAX") && r.contains("PRICE_DEVIATION")));
        assertThat(reasons.get(Klass.STALE_PRICE_SOURCES).keySet()).isNotEmpty().allMatch(r -> r.contains("TRADE_WITHIN_MAX") && r.contains("PRICE_STALE"));
        assertThat(reasons.get(Klass.SINGLE_PRICE_SOURCE).keySet()).isNotEmpty().allMatch(r -> r.contains("TRADE_WITHIN_MAX") && r.contains("PRICE_QUORUM"));
        assertThat(reasons.get(Klass.ROUNDING_ATTACK).keySet()).allMatch(r -> r.equals("SCHEMA") || r.contains("TRADE_WITHIN_MAX") || r.contains("SLIPPAGE_WITHIN_MAX"));
        assertThat(reasons.get(Klass.PROMPT_INJECTED_ACTION).keySet()).allMatch(r -> r.equals("SCHEMA") || r.contains("STRATEGY_ENABLED") || r.contains("AGENT_PERMITTED"));
    }

    @Test
    void reproducibility_sameCorpusTwiceGivesTheSameVerdictHashes() {
        assertThat(second.verdictHashes()).isEqualTo(first.verdictHashes());
        assertThat(second.rows.stream().map(r -> r.o().result()).toList()).isEqualTo(first.rows.stream().map(r -> r.o().result()).toList());
        assertThat(first.verdictHashes().stream().filter(h -> !"-".equals(h)).distinct().count()).isGreaterThan(5_000); // the hashes discriminate
    }

    @Test
    void reproducibility_engineAndIndependentValidatorAgreeOnEveryVerdict() {
        // F(I, S, R) is the same function in both implementations: same verdict hash for all 10 000 inputs that reached the policy.
        assertThat(validatorsAgree).isTrue();
        assertThat(first.rows.stream().filter(r -> r.o().result() == Result.PAUSED).count()).as("validator disagreements would PAUSE").isZero();
        assertThat(first.registry.get("deterministic.mismatch").counter().count()).isZero();
        assertThat(first.registry.get("deterministic.inference").counter().count()).isGreaterThan(5_000);
    }

    @Test
    void theFunnelMetersTellTheSameStory() {
        double blocked = first.registry.get("trade.requested").tag("result", "blocked_by_policy").counters().stream().mapToDouble(c -> c.count()).sum();
        double awaiting = first.registry.get("trade.requested").tag("result", "awaiting_approval").counters().stream().mapToDouble(c -> c.count()).sum();
        double deny = first.registry.get("policy.verdicts").tag("decision", "deny").counter().count();
        double allow = first.registry.get("policy.verdicts").tag("decision", "allow").counter().count();
        double escalate = first.registry.get("policy.verdicts").tag("decision", "escalate").counters().stream().mapToDouble(c -> c.count()).sum();
        long tradesDenied = first.rows.stream().filter(r -> r.o().verdict() != null && r.o().verdict().denied()).count();
        assertThat(deny).isEqualTo(tradesDenied);
        assertThat(blocked).isEqualTo(tradesDenied + first.rows.stream().filter(r -> r.o().stage() == AuthorityLayer.Stage.POLICY).count());
        assertThat(allow).isEqualTo(first.count(first.rows, Result.EXECUTED) + first.count(first.rows, Result.DUPLICATE));
        assertThat(escalate).isEqualTo(first.count(first.rows, Result.AUTHORIZED_PENDING));
        assertThat(awaiting).isEqualTo(allow + escalate);
        assertThat(first.registry.get("trade.submitted").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(first.count(first.rows, Result.EXECUTED));
    }

    @Test
    void compromisedAgent_randomMutationsOfValidIntentsNeverExecuteOutsidePolicy() {
        // The AI is fully compromised (§29): it emits random byte-level mutations of otherwise valid documents.
        // Some survive the schema by chance; none may execute unless it is, in fact, inside the policy.
        Random rnd = new Random(SEED + 1);
        AgentRegistry reg = agents;
        BenchmarkRun base = BenchmarkRun.of(SEED + 2, 300, 0, reg);
        AuthorityLayer layer = base.layer;
        int mutated = 0;
        int executed = 0;
        int schemaRefused = 0;
        int denied = 0;
        List<Case> seeds = new CorpusGenerator(SEED + 3, base.rules, reg, 5_000_000L).generate(2_000, 0);
        for (Case c : seeds) {
            String json = mutate(c.json(), rnd);
            byte[] sig = c.signature();
            if (rnd.nextInt(4) == 0) {
                sig = sig.clone();
                sig[rnd.nextInt(sig.length)] ^= (byte) (1 << rnd.nextInt(8));
            }
            layer.advanceSlots(1);
            Outcome o = layer.submit(json, sig);
            mutated++;
            if (o.result() == Result.EXECUTED) {
                executed++;
                assertThat(o.signatureValid()).isTrue();
                assertThat(o.verdict().decision()).isEqualTo(PolicyVerdict.Decision.ALLOW);
                assertThat(o.verdict().policyHash()).isEqualTo(base.rules.hash());
                assertThat(o.input().intent().tradeValueCents()).isLessThanOrEqualTo(base.rules.autonomousUpToCents());
                assertThat(base.rules.allowsAsset(o.input().intent().asset())).isTrue();
                assertThat(o.intent().policyVersion()).isEqualTo(base.rules.version());
            } else if (o.stage() == AuthorityLayer.Stage.SCHEMA) {
                schemaRefused++;
            } else if (o.blocked()) {
                denied++;
            }
        }
        assertThat(mutated).isEqualTo(2_000);
        extra.put("compromised_agent_fuzz", Map.of("mutated_documents", mutated, "schema_refused", schemaRefused, "policy_denied", denied,
                "executed_inside_policy", executed, "executed_outside_policy", 0));
    }

    /** One random edit: a character flipped, a field duplicated, a value replaced, a key renamed, bytes appended. */
    static String mutate(String json, Random rnd) {
        StringBuilder sb = new StringBuilder(json);
        switch (rnd.nextInt(6)) {
            case 0 -> {
                int i = rnd.nextInt(sb.length());
                sb.setCharAt(i, (char) ('0' + rnd.nextInt(10)));
            }
            case 1 -> {
                int i = rnd.nextInt(sb.length());
                sb.deleteCharAt(i);
            }
            case 2 -> sb.insert(sb.length() - 1, ",\"" + pick(rnd, "input_amount", "nonce", "policy_version", "x") + "\":" + rnd.nextInt(1_000_000));
            case 3 -> sb.append(pick(rnd, "", " ", "{}", "]", "\u0000", "\n\n"));
            case 4 -> {
                int i = sb.indexOf("\"" + pick(rnd, "agent_id", "action", "input_asset", "output_asset", "strategy_id") + "\"");
                if (i >= 0) {
                    sb.insert(i + 1, "_");
                }
            }
            default -> {
                String from = "\"" + pick(rnd, "BUY", "SELL", "SWAP", "HOLD", "REBALANCE", "CANCEL") + "\"";
                int i = sb.indexOf(from);
                if (i >= 0) {
                    sb.replace(i, i + from.length(), "\"" + pick(rnd, "TRANSFER", "buy", "SELL", "WITHDRAW") + "\"");
                }
            }
        }
        return sb.toString();
    }

    private static String pick(Random rnd, String... values) {
        return values[rnd.nextInt(values.length)];
    }

    private static String describe(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row r : rows.subList(0, Math.min(10, rows.size()))) {
            out.add("#" + r.c().id() + " " + r.c().klass() + "/" + r.c().variant() + " → " + r.o().result() + " @" + r.o().stage() + ": " + r.o().reason());
        }
        return out.toString();
    }
}
