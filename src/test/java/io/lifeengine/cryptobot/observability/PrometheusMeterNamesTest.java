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
            "duplicate_trade_suppressed_total",
            // KAN-582: the 409 by reason and the per-stage histograms
            "cryptobot_execution_refused_total",
            "cryptobot_stage_latency_seconds_bucket",
            "cryptobot_stage_latency_seconds_count",
            "cryptobot_stage_latency_seconds_sum");

    /**
     * KAN-595 (TAE phase 1, audit §21 item 4, §13): the full 27-series set the two dashboards
     * ({@code life-engine-cryptobot-demo.json}, {@code -negocio.json}) and the 3 alert rules query,
     * per {@code CryptobotMetrics}'s own naming table — the audit's list, verbatim. A subset of
     * {@link #DEMO_SERIES} above (which this list also repeats) plus the market/risk/strategy funnel,
     * the legacy {@code trade_reconciled_total}, the outbox gauges, the policy predicate breakdown,
     * the Solana RPC/confirmation series and the receipt/anchor/determinism series — none of them
     * pinned before this story.
     */
    static final List<String> DASHBOARD_SERIES = List.of(
            "market_analysis_total",
            "risk_analysis_total",
            "strategies_total",
            "approvals_total",
            "trade_requested_total",
            "trade_submitted_total",
            "trade_confirmed_total",
            "trade_failed_total",
            "trade_reconciled_total",
            "reconciliation_mismatch_total",
            "duplicate_trade_suppressed_total",
            "outbox_pending",
            "outbox_failed",
            "dlq_size",
            "cryptobot_reconciliation_total",
            "cryptobot_dead_letter_total",
            "cryptobot_dead_letter_open",
            "policy_verdicts_total",
            "policy_predicate_failed_total",
            "solana_rpc_errors_total",
            "solana_confirmation_latency_seconds_count",
            "intelligence_receipts_total",
            "deterministic_inference_total",
            "deterministic_mismatch_total",
            "validator_attestations_total",
            "receipt_anchors_total",
            "anchored_receipts_total",
            "anchor_pending",
            "anchor_finality_latency_seconds_count");

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
    @DisplayName("KAN-582: cada reason del 409 existe en 0 y cada etapa del demo path expone un histograma con sus buckets")
    void refusalReasonsAndStageHistogramsAreScraped() {
        metrics.executionRefused(CryptobotMetrics.RefusalReason.MAINNET);
        metrics.stageLatency(CryptobotMetrics.Stage.CONFIRM, java.time.Duration.ofSeconds(12));

        String scrape = webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).isNotNull();

        for (CryptobotMetrics.RefusalReason r : CryptobotMetrics.RefusalReason.values()) {
            assertThat(scrape.lines().filter(l -> l.startsWith("cryptobot_execution_refused_total{") && l.contains("reason=\"" + r.label() + "\"")).findFirst())
                    .as("cryptobot_execution_refused_total{reason=%s} existe desde el arranque", r.label())
                    .isPresent();
        }
        assertThat(value(scrape.lines().filter(l -> l.startsWith("cryptobot_execution_refused_total{") && l.contains("reason=\"mainnet\"")).findFirst().orElseThrow()))
                .isEqualTo(1.0);

        for (CryptobotMetrics.Stage s : CryptobotMetrics.Stage.values()) {
            List<String> buckets = scrape.lines()
                    .filter(l -> l.startsWith("cryptobot_stage_latency_seconds_bucket{") && l.contains("stage=\"" + s.label() + "\""))
                    .toList();
            // one line per explicit bucket + the +Inf one
            assertThat(buckets).as("buckets de la etapa %s", s.label()).hasSize(s.buckets().length + 1);
            assertThat(buckets).anyMatch(l -> l.contains("le=\"+Inf\""));
        }
        // 12 s in confirm: inside the 15 s bucket, outside the 10 s one.
        assertThat(value(bucket(scrape, "confirm", "15"))).isEqualTo(1.0);
        assertThat(value(bucket(scrape, "confirm", "10"))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("KAN-595: las 27 series de los dos dashboards (demo + negocio) y las 3 alertas existen en el scrape")
    void dashboardSeriesAreScraped() {
        // A few series are only registered on first use (not in CryptobotMetrics#registerPlaceholders):
        // trigger them once so this test proves they exist under the names the dashboards query,
        // not just that the code compiles.
        metrics.marketAnalysis("completed", "SOL");
        metrics.riskAnalysis("LOW");
        metrics.strategyCreated("proposed", "SOL");
        metrics.solanaRpcError("getBalance", "devnet", "rpc");
        metrics.solanaConfirmationLatency(java.time.Duration.ofSeconds(2), "confirmed", "devnet");
        metrics.anchorFinalityLatency(java.time.Duration.ofSeconds(5));
        metrics.policyPredicateFailed("ASSET_ALLOWED");

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
        assertThat(names)
                .as("las 27 series que life-engine-cryptobot-demo.json, -negocio.json y las 3 alertas consultan"
                        + " (audit TAE §13) siguen existiendo bajo el mismo nombre tras el movimiento de paquetes de KAN-595")
                .contains(DASHBOARD_SERIES.toArray(String[]::new));
    }

    private static String bucket(String scrape, String stage, String le) {
        // Prometheus sorts labels alphabetically (le before stage): match each independently.
        Pattern lePattern = Pattern.compile("le=\"" + le + "(\\.0)?\"");
        return scrape.lines()
                .filter(l -> l.startsWith("cryptobot_stage_latency_seconds_bucket{") && l.contains("stage=\"" + stage + "\"") && lePattern.matcher(l).find())
                .findFirst().orElseThrow(() -> new AssertionError("sin bucket le=" + le + " para " + stage));
    }

    private static double value(String sample) {
        String[] parts = sample.trim().split("\\s+");
        return Double.parseDouble(parts[1]);
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
