package io.lifeengine.cryptobot.validator;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.validator.crypto.SolanaKeypair;
import io.lifeengine.cryptobot.validator.observability.ValidatorMetrics;
import io.lifeengine.cryptobot.validator.policy.PolicyPredicate;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * (HK-5b): the validator publishes its own series. What the demo dashboard will read
 * ({@code validator_attestations_total{outcome,rule}} with {@code service="cryptobot-validator"})
 * exists from boot with the build-identity common tags, moves with real requests, and no name is
 * registered under two label sets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// @SpringBootTest disables every metrics exporter unless told otherwise: without this the endpoint is a 404.
@AutoConfigureObservability
@AutoConfigureWebTestClient
@TestPropertySource(properties = {"lifeengine.deployment.env=citest"})
class PrometheusMeterNamesTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final String POLICY_HASH = new PolicyStore(PolicyStoreTest.props(PolicyStoreTest.policy(""))).hash();
    private static final Pattern METER_NAME = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("validator.keypair-json", () -> ValidationServiceTest.keyJson(KEY));
        r.add("validator.token", () -> "test-token");
        r.add("validator.policy.expected-hash", () -> POLICY_HASH);
    }

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("issued, denied-by-rule and malformed are scraped as validator_attestations_total{outcome,rule} with common tags")
    void attestationsAreScrapedByOutcomeAndRule() {
        // issued (ESCALATE on the fixture)
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p-1", "policyHash", POLICY_HASH, "messageHash", "b".repeat(64),
                        "intent", ValidationServiceTest.intent(), "state", ValidationServiceTest.state(), "cluster", "devnet"))
                .exchange().expectStatus().isOk();
        // denied by the validator's own rule: the claimed policy hash is not the pinned one
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p-2", "policyHash", "sha256:" + "f".repeat(64), "messageHash", "b".repeat(64),
                        "intent", ValidationServiceTest.intent(), "state", ValidationServiceTest.state(), "cluster", "devnet"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("DENY");
        // malformed: no message hash
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p-3", "policyHash", POLICY_HASH, "cluster", "devnet"))
                .exchange().expectStatus().isBadRequest();

        String scrape = web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).isNotNull();

        List<String> names = scrape.lines().filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(METER_NAME::matcher).filter(java.util.regex.Matcher::find).map(m -> m.group(1)).distinct().toList();
        assertThat(names).noneMatch(n -> n.endsWith("_total_total"));
        assertThat(names).contains("validator_attestations_total", "validator_predicate_failed_total",
                "validator_validate_latency_seconds_bucket", "validator_validate_latency_seconds_count");

        assertThat(value(sample(scrape, "validator_attestations_total", "outcome=\"issued\"", "rule=\"none\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "validator_attestations_total", "outcome=\"denied\"", "rule=\"policy_hash_mismatch\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "validator_attestations_total", "outcome=\"malformed\"", "rule=\"missing_or_invalid_message_hash\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "validator_validate_latency_seconds_count", ""))).isEqualTo(3.0);
        // placeholders: every rule the dashboard may group by exists at 0
        for (String r : ValidatorMetrics.REFUSAL_RULES) {
            assertThat(sample(scrape, "validator_attestations_total", "outcome=\"denied\"", "rule=\"" + r + "\"")).isNotNull();
        }
        for (PolicyPredicate p : PolicyPredicate.values()) {
            String rule = p.name().toLowerCase(Locale.ROOT);
            assertThat(sample(scrape, "validator_attestations_total", "outcome=\"denied\"", "rule=\"" + rule + "\"")).isNotNull();
            assertThat(sample(scrape, "validator_predicate_failed_total", "predicate=\"" + rule + "\"")).isNotNull();
        }
        assertThat(sample(scrape, "validator_attestations_total", "outcome=\"issued\""))
                .contains("environment=\"citest\"").contains("service=\"cryptobot-validator\"").contains("version=\"").contains("commit=\"");
    }

    @Test
    @DisplayName("no meter is registered under two label sets")
    void noMeterNameHasTwoLabelSets() {
        Map<String, Set<Set<String>>> keySetsByName = registry.getMeters().stream()
                .collect(Collectors.groupingBy(m -> m.getId().getName(), Collectors.mapping(PrometheusMeterNamesTest::tagKeys, Collectors.toSet())));
        Map<String, Set<Set<String>>> collisions = keySetsByName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(collisions).isEmpty();
    }

    @Test
    @DisplayName("the rule label is bounded: the validator's refusals, the predicates and the malformed reasons; anything else is 'other'")
    void ruleLabelIsBounded() {
        assertThat(ValidatorMetrics.rule(ValidationService.REFUSAL_POLICY_HASH)).isEqualTo("policy_hash_mismatch");
        assertThat(ValidatorMetrics.rule(ValidationService.REFUSAL_DISABLED)).isEqualTo("validator_disabled");
        assertThat(ValidatorMetrics.rule(ValidationService.REFUSAL_DISAGREEMENT)).isEqualTo("verdict_disagreement");
        assertThat(ValidatorMetrics.rule("TRADE_WITHIN_MAX")).isEqualTo("trade_within_max");
        assertThat(ValidatorMetrics.rule("missing_body")).isEqualTo("missing_body");
        assertThat(ValidatorMetrics.rule("proposal p-1 something free text")).isEqualTo("other");
        assertThat(ValidatorMetrics.rule(null)).isEqualTo("other");
    }

    private static String sample(String scrape, String name, String... labels) {
        return scrape.lines().filter(l -> l.startsWith(name + "{") && java.util.Arrays.stream(labels).allMatch(l::contains))
                .findFirst().orElse(null);
    }

    private static double value(String sample) {
        assertThat(sample).isNotNull();
        return Double.parseDouble(sample.trim().split("\\s+")[1]);
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toCollection(TreeSet::new));
    }
}
