package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Los nombres y los label sets que salen por {@code /actuator/prometheus} (KAN-573, como en Runtime
 * KAN-489): ningún {@code _total_total}, las series del funnel y de HK-3 existen desde el arranque
 * con los common tags de la identidad del build, y ningún nombre se registra con dos conjuntos de
 * labels distintos (Prometheus exige un solo label set por nombre; Micrometer descarta el segundo
 * en silencio y el panel queda en "No data").
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {"lifeengine.deployment.env=citest"})
class PrometheusMeterNamesTest {

    private static final Pattern METER_NAME = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)");

    /** Lo que el tablero del demo (deploy/observability, life-engine-cryptobot-demo.json) y la alerta CryptoBotDlqNotEmpty consultan. */
    static final List<String> DEMO_SERIES = List.of(
            "trade_requested_total",
            "policy_verdicts_total",
            "approvals_total",
            "validator_attestations_total",
            "trade_submitted_total",
            "trade_confirmed_total",
            "trade_failed_total",
            "cryptobot_reconciliation_total",
            "cryptobot_dead_letter_total",
            "cryptobot_dead_letter_open",
            "dlq_size",
            "reconciliation_mismatch_total",
            "duplicate_trade_suppressed_total");

    @Autowired private WebTestClient webTestClient;
    @Autowired private CryptobotMetrics metrics;
    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("el scrape tiene las series del demo, con un solo sufijo y los common tags de la identidad del build")
    void demoSeriesAreScrapedWithCommonTags() {
        metrics.reconciliation("retried");
        metrics.deadLetter("retries_exhausted");
        metrics.dlqSize(1);

        String scrape = webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).isNotNull();

        List<String> names = scrape.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(METER_NAME::matcher)
                .filter(java.util.regex.Matcher::find)
                .map(m -> m.group(1))
                .distinct()
                .toList();
        assertThat(names).noneMatch(name -> name.endsWith("_total_total"));
        assertThat(names).contains(DEMO_SERIES.toArray(String[]::new));

        for (String series : DEMO_SERIES) {
            String sample = scrape.lines().filter(l -> l.startsWith(series + "{")).findFirst().orElseThrow();
            assertThat(sample)
                    .as("%s lleva los common tags de BuildIdentityConfig", series)
                    .contains("environment=\"citest\"")
                    .contains("service=\"cryptobot-service\"")
                    .contains("version=\"")
                    .contains("commit=\"");
        }
        assertThat(scrape)
                .as("HK-3: outcome/reason viajan como label, no como parte del nombre")
                .contains("cryptobot_reconciliation_total{")
                .contains("outcome=\"retried\"")
                .contains("cryptobot_dead_letter_total{")
                .contains("reason=\"retries_exhausted\"");
        assertThat(scrape.lines().filter(l -> l.startsWith("cryptobot_dead_letter_open{")).findFirst().orElseThrow())
                .as("la alerta CryptoBotDlqNotEmpty lee este gauge")
                .endsWith("1.0");
    }

    @Test
    @DisplayName("ningún meter se registra con dos label sets distintos")
    void noMeterNameHasTwoLabelSets() {
        Map<String, Set<Set<String>>> keySetsByName = registry.getMeters().stream()
                .collect(Collectors.groupingBy(
                        m -> m.getId().getName(),
                        Collectors.mapping(PrometheusMeterNamesTest::tagKeys, Collectors.toSet())));
        Map<String, Set<Set<String>>> collisions = keySetsByName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(collisions)
                .as("un nombre con dos conjuntos de labels: Prometheus descarta uno y el panel queda vacío")
                .isEmpty();
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toCollection(TreeSet::new));
    }
}
