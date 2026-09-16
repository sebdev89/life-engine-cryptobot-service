package io.lifeengine.cryptobot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business metrics of CryptoBot (KAN-425, Platform-Baseline-v1 §Observabilidad · nivel 3).
 *
 * <p>The single place where the product's meters are named. Every service that emits a business
 * event calls one method here; nobody else touches the {@link MeterRegistry} for business data.
 *
 * <h2>Names</h2>
 *
 * Micrometer names are dotted; the Prometheus exporter renders them with underscores and appends
 * {@code _total} to counters and {@code _seconds} to timers. The right column is what the
 * dashboard and the alerts query:
 *
 * <pre>
 *   market.analysis               → market_analysis_total{result,asset}
 *   risk.analysis                 → risk_analysis_total{result}
 *   strategies                    → strategies_total{result,asset}          proposed | noop
 *       (the issue says strategy_created_total; the Prometheus 1.x client reserves the _created
 *        suffix for counter creation timestamps and strips it, which would render strategy_total)
 *   approvals                     → approvals_total{result}                approved | rejected | expired
 *   trade.requested               → trade_requested_total{result,asset}    awaiting_approval | blocked_by_policy
 *   trade.submitted               → trade_submitted_total{asset}
 *   trade.confirmed               → trade_confirmed_total{result,asset}    confirmed | finalized | pending
 *   trade.failed                  → trade_failed_total{stage,asset}        preflight | sign | broadcast | onchain | rpc | other
 *   solana.rpc.errors             → solana_rpc_errors_total{method,cluster,kind}
 *   solana.confirmation.latency   → solana_confirmation_latency_seconds{result,cluster}
 *   --- KAN-403 (outbox / reconciliación / duplicados) ---
 *   trade.reconciled              → trade_reconciled_total{result}         matched (still pending, consistent) | corrected (row moved by the chain's verdict)
 *   reconciliation.mismatch       → reconciliation_mismatch_total          a SUBMITTED row the chain never saw (blockhash expired)
 *   duplicate.trade.suppressed    → duplicate_trade_suppressed_total       same operationId replayed: no new transaction
 *   outbox.pending                → outbox_pending          (gauge)       PENDING outbox rows, refreshed every publisher tick
 *   outbox.failed                 → outbox_failed           (gauge)       retries exhausted (each one has a dead letter)
 *   dlq.size                      → dlq_size                (gauge)       unresolved dead letters — > 0 is the alert
 *   --- registered at 0 until KAN-390 (Decision Receipts / determinismo) feeds them ---
 *   intelligence.receipts         → intelligence_receipts_total{result}    issued | verified
 *   deterministic.inference       → deterministic_inference_total
 *   deterministic.mismatch        → deterministic_mismatch_total
 * </pre>
 *
 * <h2>Labels</h2>
 *
 * Bounded on purpose. {@code environment}/{@code service}/{@code version}/{@code commit} come from
 * {@link BuildIdentityConfig} as common tags. {@code asset} is only ever a value from the
 * configured allow-list, otherwise {@code other}: the market-review symbol is user input and would
 * otherwise be an unbounded series. Never a wallet, a proposal id, a signature or a message.
 */
public class CryptobotMetrics {

    public static final String ASSET_OTHER = "other";
    public static final String ASSET_NONE = "none";

    static final String MARKET_ANALYSIS = "market.analysis";
    static final String RISK_ANALYSIS = "risk.analysis";
    static final String STRATEGY_CREATED = "strategies";
    static final String APPROVALS = "approvals";
    static final String TRADE_REQUESTED = "trade.requested";
    static final String TRADE_SUBMITTED = "trade.submitted";
    static final String TRADE_CONFIRMED = "trade.confirmed";
    static final String TRADE_FAILED = "trade.failed";
    static final String TRADE_RECONCILED = "trade.reconciled";
    static final String RECONCILIATION_MISMATCH = "reconciliation.mismatch";
    static final String DUPLICATE_TRADE_SUPPRESSED = "duplicate.trade.suppressed";
    static final String OUTBOX_PENDING = "outbox.pending";
    static final String OUTBOX_FAILED = "outbox.failed";
    static final String DLQ_SIZE = "dlq.size";
    static final String SOLANA_RPC_ERRORS = "solana.rpc.errors";
    static final String SOLANA_CONFIRMATION_LATENCY = "solana.confirmation.latency";
    static final String INTELLIGENCE_RECEIPTS = "intelligence.receipts";
    static final String DETERMINISTIC_INFERENCE = "deterministic.inference";
    static final String DETERMINISTIC_MISMATCH = "deterministic.mismatch";

    /** Stages of {@code ExecutionService.run}; the one reached when it failed is the label. */
    public enum FailureStage {
        PREFLIGHT,
        SIGN,
        BROADCAST,
        ONCHAIN,
        RPC,
        OTHER;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Set<String> knownAssets;

    // Gauges owned here so KAN-403 only has to call the setters.
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong dlqSize = new AtomicLong();

    public CryptobotMetrics(MeterRegistry registry, Collection<String> knownAssets) {
        this.registry = registry;
        Set<String> assets = new LinkedHashSet<>();
        for (String a : knownAssets) {
            if (a != null && !a.isBlank()) {
                assets.add(a.trim().toUpperCase(Locale.ROOT));
            }
        }
        this.knownAssets = Set.copyOf(assets);
        registerPlaceholders();
    }

    /** For tests and for constructors that must not depend on the Spring context. Nothing is exported. */
    public static CryptobotMetrics noop() {
        return new CryptobotMetrics(new SimpleMeterRegistry(), Set.of());
    }

    // ---- market → risk → strategy -------------------------------------------------------------

    /** One market review run started (or its terminal outcome once reconciled). */
    public void marketAnalysis(String result, String symbol) {
        counter(MARKET_ANALYSIS, "result", low(result), "asset", asset(symbol)).increment();
    }

    /** One deterministic risk evaluation; {@code overall} is LOW | MEDIUM | HIGH. */
    public void riskAnalysis(String overall) {
        counter(RISK_ANALYSIS, "result", low(overall)).increment();
    }

    /** A rebalance plan materialised into a proposal. */
    public void strategyCreated(String result, String symbol) {
        counter(STRATEGY_CREATED, "result", low(result), "asset", asset(symbol)).increment();
    }

    // ---- trade funnel: requested → approved → submitted → confirmed ----------------------------

    /** A proposal reached the human: {@code awaiting_approval}; or was stopped before: {@code blocked_by_policy}. */
    public void tradeRequested(String result, String symbol) {
        counter(TRADE_REQUESTED, "result", low(result), "asset", asset(symbol)).increment();
    }

    /** Human decision: {@code approved} | {@code rejected}; or the clock's: {@code expired}. */
    public void approval(String result) {
        counter(APPROVALS, "result", low(result)).increment();
    }

    /** {@code sendTransaction} returned a signature. */
    public void tradeSubmitted(String symbol) {
        counter(TRADE_SUBMITTED, "asset", asset(symbol)).increment();
    }

    /** Terminal on-chain status: {@code confirmed} | {@code finalized}; {@code pending} = gave up waiting (KAN-403 reconciles). */
    public void tradeConfirmed(String confirmationStatus, String symbol) {
        counter(TRADE_CONFIRMED, "result", low(confirmationStatus == null ? "pending" : confirmationStatus), "asset", asset(symbol)).increment();
    }

    public void tradeFailed(FailureStage stage, String symbol) {
        counter(TRADE_FAILED, "stage", stage.label(), "asset", asset(symbol)).increment();
    }

    // ---- Solana ------------------------------------------------------------------------------

    /** {@code kind}: {@code rpc} (JSON-RPC error object) | {@code timeout} | {@code transport}. */
    public void solanaRpcError(String method, String cluster, String kind) {
        counter(SOLANA_RPC_ERRORS, "method", low(method), "cluster", low(cluster), "kind", low(kind)).increment();
    }

    /** Time from {@code sendTransaction} to the terminal signature status. */
    public void solanaConfirmationLatency(Duration elapsed, String result, String cluster) {
        Timer.builder(SOLANA_CONFIRMATION_LATENCY)
                .description("Time from sendTransaction to a terminal signature status")
                .tags("result", low(result), "cluster", low(cluster))
                .register(registry)
                .record(elapsed);
    }

    // ---- KAN-403: outbox / reconciliación / duplicados (registered now, fed later) -------------

    public void tradeReconciled(String result) {
        counter(TRADE_RECONCILED, "result", low(result)).increment();
    }

    public void reconciliationMismatch() {
        counter(RECONCILIATION_MISMATCH).increment();
    }

    public void duplicateTradeSuppressed() {
        counter(DUPLICATE_TRADE_SUPPRESSED).increment();
    }

    public void outboxPending(long size) {
        outboxPending.set(size);
    }

    public void outboxFailed(long size) {
        outboxFailed.set(size);
    }

    public void dlqSize(long size) {
        dlqSize.set(size);
    }

    // ---- KAN-390: Decision Receipts / determinismo (registered now, fed later) -----------------

    public void intelligenceReceipt(String result) {
        counter(INTELLIGENCE_RECEIPTS, "result", low(result)).increment();
    }

    public void deterministicInference() {
        counter(DETERMINISTIC_INFERENCE).increment();
    }

    public void deterministicMismatch() {
        counter(DETERMINISTIC_MISMATCH).increment();
    }

    // ---- plumbing -----------------------------------------------------------------------------

    /**
     * Series that must exist at 0 so the dashboard panels and the alerts ({@code dlq_size > 0},
     * outbox growing, mismatches, duplicates > baseline) evaluate from day one instead of "No data".
     */
    private void registerPlaceholders() {
        Gauge.builder(OUTBOX_PENDING, outboxPending, AtomicLong::doubleValue)
                .description("Trade outbox entries waiting to be published (KAN-403)").register(registry);
        Gauge.builder(OUTBOX_FAILED, outboxFailed, AtomicLong::doubleValue)
                .description("Trade outbox entries that exhausted retries (KAN-403)").register(registry);
        Gauge.builder(DLQ_SIZE, dlqSize, AtomicLong::doubleValue)
                .description("Dead-letter queue depth (KAN-403)").register(registry);
        counter(TRADE_RECONCILED, "result", "matched");
        counter(TRADE_RECONCILED, "result", "corrected");
        counter(RECONCILIATION_MISMATCH);
        counter(DUPLICATE_TRADE_SUPPRESSED);
        counter(INTELLIGENCE_RECEIPTS, "result", "issued");
        counter(INTELLIGENCE_RECEIPTS, "result", "verified");
        counter(DETERMINISTIC_INFERENCE);
        counter(DETERMINISTIC_MISMATCH);
        // The funnel and its failure modes also start at 0 for the asset-less series, so the ratio
        // panels divide by something and the "dónde se cae" panel lists every stage.
        for (String r : new String[] {"awaiting_approval", "blocked_by_policy"}) {
            counter(TRADE_REQUESTED, "result", r, "asset", ASSET_NONE);
        }
        for (String r : new String[] {"approved", "rejected", "expired"}) {
            counter(APPROVALS, "result", r);
        }
        counter(TRADE_SUBMITTED, "asset", ASSET_NONE);
        for (String r : new String[] {"confirmed", "finalized", "pending"}) {
            counter(TRADE_CONFIRMED, "result", r, "asset", ASSET_NONE);
        }
        for (FailureStage s : FailureStage.values()) {
            counter(TRADE_FAILED, "stage", s.label(), "asset", ASSET_NONE);
        }
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(Tags.of(tags)).register(registry);
    }

    /** Allow-listed symbol or {@code other}; never the raw input. */
    String asset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ASSET_NONE;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return knownAssets.contains(s) ? s : ASSET_OTHER;
    }

    private static String low(String v) {
        return v == null || v.isBlank() ? "unknown" : v.trim().toLowerCase(Locale.ROOT);
    }
}
