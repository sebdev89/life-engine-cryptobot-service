package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KAN-425: the business meters exist under the names the issue lists, their labels are bounded,
 * and the ones that wait for KAN-403 / KAN-390 are already registered at 0.
 */
class CryptobotMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL", "usdc", "BTCUSDT"));

    @Test
    @DisplayName("Prometheus renders exactly the names of KAN-425")
    void prometheusNamesMatchTheIssue() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        CryptobotMetrics m = new CryptobotMetrics(prom, Set.of("SOL"));
        m.marketAnalysis("started", "SOL");
        m.riskAnalysis("HIGH");
        m.strategyCreated("proposed", "SOL");
        m.tradeRequested("AWAITING_APPROVAL", "SOL");
        m.approval("APPROVED");
        m.tradeSubmitted("SOL");
        m.tradeConfirmed("confirmed", "SOL");
        m.tradeFailed(CryptobotMetrics.FailureStage.ONCHAIN, "SOL");
        m.solanaRpcError("sendTransaction", "devnet", "rpc");
        m.solanaConfirmationLatency(Duration.ofSeconds(2), "confirmed", "devnet");
        m.intelligenceReceipt("issued");

        String scrape = prom.scrape();
        assertThat(scrape)
                .contains("market_analysis_total{")
                .contains("risk_analysis_total{")
                .contains("strategies_total{") // strategy_created_total is impossible: the client strips _created
                .contains("approvals_total{")
                .contains("trade_requested_total{")
                .contains("trade_submitted_total{")
                .contains("trade_confirmed_total{")
                .contains("trade_failed_total{")
                .contains("trade_reconciled_total{")
                .contains("reconciliation_mismatch_total")
                .contains("duplicate_trade_suppressed_total")
                .contains("outbox_pending ")
                .contains("outbox_failed ")
                .contains("dlq_size ")
                .contains("solana_rpc_errors_total{")
                .contains("solana_confirmation_latency_seconds_count{")
                .contains("intelligence_receipts_total{")
                .contains("deterministic_inference_total")
                .contains("deterministic_mismatch_total");
        // Label values are lower-case and bounded: the enum name went in, the label came out normalised.
        assertThat(scrape).contains("result=\"awaiting_approval\"").contains("result=\"approved\"").contains("stage=\"onchain\"");
    }

    @Test
    @DisplayName("KAN-403 / KAN-390 series exist at 0 before anything feeds them")
    void placeholdersAreRegisteredAtZero() {
        assertThat(registry.get("dlq.size").gauge().value()).isZero();
        assertThat(registry.get("outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("outbox.failed").gauge().value()).isZero();
        assertThat(registry.get("trade.reconciled").tag("result", "matched").counter().count()).isZero();
        assertThat(registry.get("reconciliation.mismatch").counter().count()).isZero();
        assertThat(registry.get("duplicate.trade.suppressed").counter().count()).isZero();
        assertThat(registry.get("intelligence.receipts").tag("result", "issued").counter().count()).isZero();
        assertThat(registry.get("deterministic.inference").counter().count()).isZero();
        assertThat(registry.get("deterministic.mismatch").counter().count()).isZero();
        // The funnel too, so a ratio panel never divides "No data".
        assertThat(registry.get("trade.requested").tag("result", "blocked_by_policy").counter().count()).isZero();
        assertThat(registry.get("trade.confirmed").tag("result", "pending").counter().count()).isZero();
        assertThat(registry.get("trade.failed").tag("stage", "sign").counter().count()).isZero();
    }

    @Test
    @DisplayName("gauges owned here are settable by KAN-403 without touching the registry")
    void gaugesFollowTheSetters() {
        metrics.dlqSize(3);
        metrics.outboxPending(7);
        metrics.outboxFailed(1);
        assertThat(registry.get("dlq.size").gauge().value()).isEqualTo(3);
        assertThat(registry.get("outbox.pending").gauge().value()).isEqualTo(7);
        assertThat(registry.get("outbox.failed").gauge().value()).isEqualTo(1);
        metrics.dlqSize(0);
        assertThat(registry.get("dlq.size").gauge().value()).isZero();
    }

    @Test
    @DisplayName("asset label is allow-listed: anything else is 'other', never the raw input")
    void assetLabelIsBounded() {
        metrics.tradeSubmitted("sol");
        metrics.tradeSubmitted("SHIBAINU");
        metrics.marketAnalysis("started", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"); // a wallet address is never a label
        metrics.marketAnalysis("started", "btcusdt");
        metrics.strategyCreated("noop", null);

        assertThat(registry.get("trade.submitted").tag("asset", "SOL").counter().count()).isEqualTo(1);
        assertThat(registry.get("trade.submitted").tag("asset", "other").counter().count()).isEqualTo(1);
        assertThat(registry.get("market.analysis").tag("asset", "other").counter().count()).isEqualTo(1);
        assertThat(registry.get("market.analysis").tag("asset", "BTCUSDT").counter().count()).isEqualTo(1);
        assertThat(registry.get("strategies").tag("asset", "none").tag("result", "noop").counter().count()).isEqualTo(1);

        Set<String> assetValues = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .filter(t -> t.getKey().equals("asset"))
                .map(Tag::getValue)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(assetValues).containsExactlyInAnyOrder("SOL", "BTCUSDT", "other", "none");
    }

    @Test
    @DisplayName("confirmation latency is a timer by result and cluster")
    void confirmationLatencyIsATimer() {
        metrics.solanaConfirmationLatency(Duration.ofMillis(1500), "confirmed", "devnet");
        metrics.solanaConfirmationLatency(Duration.ofSeconds(30), "pending", "devnet");

        assertThat(registry.get("solana.confirmation.latency").tag("result", "confirmed").tag("cluster", "devnet").timer().count()).isEqualTo(1);
        assertThat(registry.get("solana.confirmation.latency").tag("result", "pending").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("null or blank label values degrade to 'unknown' (Micrometer rejects null)")
    void nullResultDegradesToUnknown() {
        metrics.riskAnalysis(null);
        metrics.tradeConfirmed(null, "SOL");
        assertThat(registry.get("risk.analysis").tag("result", "unknown").counter().count()).isEqualTo(1);
        assertThat(registry.get("trade.confirmed").tag("result", "pending").tag("asset", "SOL").counter().count()).isEqualTo(1);
    }
}
