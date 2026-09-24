package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.signer.observability.SignerMetrics;
import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Instant;
import java.util.List;
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
 * KAN-582 (HK-5b): the signer publishes its own series, so "firmados" on the demo dashboard is
 * {@code signer_signatures_total{outcome="signed",kind="transfer"}} and not a derived value.
 * Signed, refused-by-rule (own cap, mainnet, attestation) and anchors are scraped with the
 * build-identity common tags; every known rule exists at 0; no name has two label sets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// @SpringBootTest disables every metrics exporter unless told otherwise: without this the endpoint is a 404.
@AutoConfigureObservability
@AutoConfigureWebTestClient
@TestPropertySource(properties = {"lifeengine.deployment.env=citest"})
class PrometheusMeterNamesTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";
    private static final Pattern METER_NAME = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.allowed-destinations", () -> VAULT);
        r.add("signer.max-lamports", () -> "1000000");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
    }

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry registry;

    static Map<String, Object> attestation(AttestationVerifier.Attestation a) {
        return Map.of("payload", a.payload(), "signature", a.signature());
    }

    @Test
    @DisplayName("signed, refused by own cap, refused by mainnet, refused without attestation and an anchor: each is its own series")
    void signaturesAreScrapedByOutcomeRuleAndKind() {
        String me = KEY.publicKeyBase58();
        long now = Instant.now().getEpochSecond();
        LegacyTransaction ok = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        LegacyTransaction big = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 5_000_000L)));

        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", ok.unsignedBase64(), "expectedFeePayer", me, "cluster", "devnet",
                        "attestation", attestation(Attestations.fresh(VALIDATOR, "p1", ok.serializeMessage(), now))))
                .exchange().expectStatus().isOk();
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p2", "unsignedTransactionBase64", big.unsignedBase64(), "cluster", "devnet",
                        "attestation", attestation(Attestations.fresh(VALIDATOR, "p2", big.serializeMessage(), now))))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("amount_over_cap");
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p3", "unsignedTransactionBase64", ok.unsignedBase64(), "cluster", "mainnet-beta",
                        "attestation", attestation(Attestations.fresh(VALIDATOR, "p3", ok.serializeMessage(), now, "mainnet-beta"))))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("mainnet_disabled");
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p4", "unsignedTransactionBase64", ok.unsignedBase64(), "cluster", "devnet"))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_missing");
        String root = "sha256:" + "ef".repeat(32);
        String memo = "ir/1 root=" + root + " n=2 ts=2026-09-18T03:00:00Z";
        LegacyTransaction anchor = new LegacyTransaction(me, BLOCKHASH, List.of(new LegacyTransaction.Instruction(SigningPolicy.MEMO_PROGRAM_ID, List.of(), memo.getBytes())));
        web.post().uri("/api/signer/sign-anchor").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("root", root, "receiptCount", 2, "unsignedTransactionBase64", anchor.unsignedBase64(), "expectedFeePayer", me))
                .exchange().expectStatus().isOk();
        // a 401 is not a signing decision: it must not move the series
        web.post().uri("/api/signer/sign").bodyValue(Map.of("proposalId", "p5", "unsignedTransactionBase64", ok.unsignedBase64()))
                .exchange().expectStatus().isUnauthorized();

        String scrape = web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).isNotNull();

        List<String> names = scrape.lines().filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(METER_NAME::matcher).filter(java.util.regex.Matcher::find).map(m -> m.group(1)).distinct().toList();
        assertThat(names).noneMatch(n -> n.endsWith("_total_total"));
        assertThat(names).contains("signer_signatures_total", "signer_sign_latency_seconds_bucket", "signer_sign_latency_seconds_count");

        assertThat(value(sample(scrape, "signer_signatures_total", "kind=\"transfer\"", "outcome=\"signed\"", "rule=\"none\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "signer_signatures_total", "kind=\"transfer\"", "outcome=\"refused\"", "rule=\"amount_over_cap\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "signer_signatures_total", "kind=\"transfer\"", "outcome=\"refused\"", "rule=\"mainnet_disabled\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "signer_signatures_total", "kind=\"transfer\"", "outcome=\"refused\"", "rule=\"attestation_missing\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "signer_signatures_total", "kind=\"anchor\"", "outcome=\"signed\"", "rule=\"none\""))).isEqualTo(1.0);
        assertThat(value(sample(scrape, "signer_sign_latency_seconds_count", "kind=\"transfer\""))).isEqualTo(4.0);
        assertThat(value(sample(scrape, "signer_sign_latency_seconds_count", "kind=\"anchor\""))).isEqualTo(1.0);
        double refusedTotal = scrape.lines().filter(l -> l.startsWith("signer_signatures_total{") && l.contains("outcome=\"refused\""))
                .mapToDouble(PrometheusMeterNamesTest::value).sum();
        assertThat(refusedTotal).as("the 401 did not count as a refusal").isEqualTo(3.0);
        for (String rule : SignerMetrics.KNOWN_RULES) {
            assertThat(sample(scrape, "signer_signatures_total", "kind=\"transfer\"", "outcome=\"refused\"", "rule=\"" + rule + "\""))
                    .as("placeholder for %s", rule).isNotNull();
        }
        assertThat(sample(scrape, "signer_signatures_total", "outcome=\"signed\""))
                .contains("environment=\"citest\"").contains("service=\"cryptobot-signer\"").contains("version=\"").contains("commit=\"");
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
    @DisplayName("the rule label is bounded: a known reason as is, free text stripped after ':', unknown → other")
    void ruleLabelIsBounded() {
        assertThat(SignerMetrics.rule("amount_over_cap")).isEqualTo("amount_over_cap");
        assertThat(SignerMetrics.rule("undecodable_transaction: Unexpected end of input at byte 12")).isEqualTo("undecodable_transaction");
        assertThat(SignerMetrics.rule("Attestation_Expired")).isEqualTo("attestation_expired");
        assertThat(SignerMetrics.rule("something-new")).isEqualTo("other");
        assertThat(SignerMetrics.rule("")).isEqualTo("other");
        assertThat(SignerMetrics.rule(null)).isEqualTo("other");
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
