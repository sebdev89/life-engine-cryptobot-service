package io.lifeengine.cryptobot.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.core.policy.PolicyInput.StateFacts;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden vectors ({@code policy/vectors-v1.json}). The canonical strings in the file were written
 * by hand and every hash in it was produced by {@code sha256sum}, so this test is the check that
 * an implementation written from the schema — not from this code — agrees with this code on
 * {@code H_R}, on the input hash, on the decision and on the verdict hash.
 */
class PolicyVectorsTest {

    private static JsonNode load() throws Exception {
        try (InputStream in = PolicyVectorsTest.class.getResourceAsStream("/policy/vectors-v1.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    static PolicyRules rules(JsonNode r) {
        return new PolicyRules(
                r.path("version").asText(),
                strings(r.path("allowed_assets")),
                strings(r.path("enabled_strategies")),
                r.path("max_trade_value_cents").asLong(),
                r.path("daily_limit_cents").asLong(),
                r.path("max_asset_exposure_bps").asInt(),
                r.path("max_slippage_bps").asInt(),
                r.path("max_oracle_age_seconds").asLong(),
                r.path("autonomous_up_to_cents").asLong(),
                r.path("second_agent_up_to_cents").asLong());
    }

    static PolicyInput input(JsonNode intent, JsonNode state) {
        IntentFacts i = new IntentFacts(
                text(intent, "agent_id"), text(intent, "strategy_id"), text(intent, "policy_version"), text(intent, "asset"),
                longOrNull(intent, "trade_value_cents"), intOrNull(intent, "max_slippage_bps"), longOrNull(intent, "valid_until_slot"));
        StateFacts s = new StateFacts(
                longOrNull(state, "daily_exposure_cents"), intOrNull(state, "asset_exposure_after_bps"), longOrNull(state, "oracle_age_seconds"),
                boolOrNull(state, "agent_permitted"), boolOrNull(state, "nonce_unused"), longOrNull(state, "current_slot"));
        return new PolicyInput(i, s);
    }

    @Test
    void policyHashMatchesTheOneComputedOutsideTheCode() throws Exception {
        JsonNode file = load();
        PolicyRules rules = rules(file.path("rules"));
        assertThat(rules.canonicalJson()).isEqualTo(file.path("canonical_rules").asText());
        assertThat(rules.hash()).isEqualTo(file.path("policy_hash").asText());
        assertThat(file.path("vectors")).hasSizeGreaterThanOrEqualTo(8);
    }

    @TestFactory
    List<DynamicTest> everyVectorReproduces() throws Exception {
        JsonNode file = load();
        PolicyRules rules = rules(file.path("rules"));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : file.path("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.path("name").asText(), () -> {
                PolicyInput in = input(v.path("intent"), v.path("state"));
                assertThat(in.canonicalJson()).isEqualTo(v.path("canonical_input").asText());
                assertThat(in.hash()).isEqualTo(v.path("input_hash").asText());

                PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(rules, in);
                JsonNode expected = v.path("expected");
                assertThat(verdict.decision().name()).isEqualTo(expected.path("decision").asText());
                assertThat(verdict.escalation().name()).isEqualTo(expected.path("escalation").asText());
                assertThat(verdict.tier().name()).isEqualTo(expected.path("tier").asText());
                assertThat(verdict.failedPredicates().stream().map(Enum::name).toList()).isEqualTo(strings(expected.path("failed_predicates")));
                assertThat(verdict.policyHash()).isEqualTo(file.path("policy_hash").asText());
                assertThat(verdict.canonicalJson()).isEqualTo(v.path("canonical_verdict").asText());
                assertThat(verdict.hash()).isEqualTo(v.path("verdict_hash").asText());

                // second run, same everything: reproducibility L1
                PolicyVerdict again = DeterministicPolicyEngine.evaluate(rules(file.path("rules")), input(v.path("intent"), v.path("state")));
                assertThat(again).isEqualTo(verdict);
                assertThat(again.hash()).isEqualTo(verdict.hash());
            }));
        }
        return tests;
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static Long longOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asLong() : null;
    }

    private static Integer intOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    private static Boolean boolOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asBoolean() : null;
    }
}
