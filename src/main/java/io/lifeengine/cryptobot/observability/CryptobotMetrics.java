package io.lifeengine.cryptobot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
import java.util.concurrent.ConcurrentHashMap;
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
 *   --- KAN-571 (reconciliación + recovery visibles; KAN-501 DLQ resolve/requeue) ---
 *   cryptobot.reconciliation      → cryptobot_reconciliation_total{outcome}  matched | corrected | retried | dead_lettered | skipped (one per row looked at)
 *   cryptobot.dead.letter         → cryptobot_dead_letter_total{reason}      ambiguous | retries_exhausted | inconsistent | outbox (created) — and resolved | requeued (closed)
 *   cryptobot.dead.letter.open    → cryptobot_dead_letter_open (gauge)       = dlq_size under the product's name; falls when a letter is resolved or requeued
 *   --- KAN-440 (authority layer: the deterministic verdict, what the funnel's "blocked" is made of) ---
 *   policy.verdicts               → policy_verdicts_total{decision,escalation}  allow | deny | escalate × none | require_second_agent | require_human_signature
 *   policy.predicate.failed       → policy_predicate_failed_total{predicate}    one increment per failed predicate of a DENY (a verdict may count several)
 *   --- KAN-391 (Decision Receipts: fed by ReceiptService; mismatch waits for the L1 re-execution of KAN-392) ---
 *   intelligence.receipts         → intelligence_receipts_total{result}    issued | verified | invalid (verify failed a check) | failed (could not be written)
 *   deterministic.inference       → deterministic_inference_total          one per L1 receipt (risk engine, planner)
 *   deterministic.mismatch        → deterministic_mismatch_total
 *   --- KAN-394 (anchoring on devnet: fed by AnchorService) ---
 *   receipt.anchors               → receipt_anchors_total{result}         submitted | finalized | failed | abandoned (one per batch transition)
 *   anchored.receipts             → anchored_receipts_total               receipts stamped by a FINALIZED batch
 *   anchor.pending                → anchor_pending          (gauge)       receipts without a finalized anchor (Endgame §24)
 *   anchor.finality.latency       → anchor_finality_latency_seconds       broadcast → finalized, per batch
 *   --- KAN-393 (provenance DAG: lineage API + REUSES edge) ---
 *   artifact.reuse                → artifact_reuse_total{external}       a STRATEGY that declared REUSES over an earlier MARKET_ANALYSIS; external=false until public receipts exist
 *   provenance.depth              → provenance_depth (distribution)       max depth of the graph a lineage query returned
 *   --- KAN-582 (HK-5b: the 409 of /execute says WHY, and every stage of the demo path has a histogram) ---
 *   cryptobot.execution.refused   → cryptobot_execution_refused_total{reason}   mainnet | timelock | policy | state | cooldown | oracle — one per refused
 *                                                                              POST /execute (409), before any state change; see {@link RefusalReason}
 *   cryptobot.stage.latency       → cryptobot_stage_latency_seconds{stage}      histogram (_bucket/_sum/_count) per stage of the demo path:
 *                                                                              simulate | policy | validate | sign | submit | confirm | reconcile;
 *                                                                              buckets per stage in {@link Stage#buckets()}
 *   --- KAN-353 (glossary usage: fed by GlossaryEventsService from the UI's batched events) ---
 *   cryptobot.glossary.term       → cryptobot_glossary_term_total{term,action}  open | search | copy — one series per (term, action)
 *   cryptobot.glossary.search     → cryptobot_glossary_search_total{hit}        true | false (a search with no result is the product signal)
 * </pre>
 *
 * <h2>Labels</h2>
 *
 * Bounded on purpose. {@code environment}/{@code service}/{@code version}/{@code commit} come from
 * {@link BuildIdentityConfig} as common tags. {@code asset} is only ever a value from the
 * configured allow-list, otherwise {@code other}: the market-review symbol is user input and would
 * otherwise be an unbounded series. Never a wallet, a proposal id, a signature or a message.
 *
 * <p>{@code term} (KAN-353) is the glossary term as the UI names it — the service does not carry a
 * copy of the 864-entry list, so it cannot allow-list it. Instead the number of distinct terms is
 * capped at {@link #MAX_GLOSSARY_TERMS}: past the cap every new term is reported as {@code other}.
 * The caller ({@code GlossaryEventsService}) has already validated shape and length. Never a user,
 * a tenant or a session: usage of the glossary is measured per term, not per person.
 */
public class CryptobotMetrics {

    public static final String ASSET_OTHER = "other";
    public static final String ASSET_NONE = "none";
    public static final String TERM_OTHER = "other";
    /** Distinct {@code term} label values kept (864 in the glossary today, room to grow); beyond it: {@code other}. */
    public static final int MAX_GLOSSARY_TERMS = 1200;

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
    static final String RECONCILIATION = "cryptobot.reconciliation";
    static final String DEAD_LETTER = "cryptobot.dead.letter";
    static final String DEAD_LETTER_OPEN = "cryptobot.dead.letter.open";
    static final String POLICY_VERDICTS = "policy.verdicts";
    static final String POLICY_PREDICATE_FAILED = "policy.predicate.failed";
    static final String ORACLE_EXECUTION_REFUSED = "oracle.execution.refused";
    static final String SOLANA_RPC_ERRORS = "solana.rpc.errors";
    static final String SOLANA_CONFIRMATION_LATENCY = "solana.confirmation.latency";
    static final String INTELLIGENCE_RECEIPTS = "intelligence.receipts";
    static final String DETERMINISTIC_INFERENCE = "deterministic.inference";
    static final String DETERMINISTIC_MISMATCH = "deterministic.mismatch";
    static final String VALIDATOR_ATTESTATIONS = "validator.attestations";
    static final String RECEIPT_ANCHORS = "receipt.anchors";
    static final String ANCHORED_RECEIPTS = "anchored.receipts";
    static final String ANCHOR_PENDING = "anchor.pending";
    static final String ANCHOR_FINALITY_LATENCY = "anchor.finality.latency";
    static final String ARTIFACT_REUSE = "artifact.reuse";
    static final String PROVENANCE_DEPTH = "provenance.depth";
    static final String EXECUTION_REFUSED = "cryptobot.execution.refused";
    static final String STAGE_LATENCY = "cryptobot.stage.latency";

    /**
     * KAN-582: why {@code POST /execute} answered 409 before touching the proposal. Bounded and
     * ordered by specificity: when several preconditions fail at once (a BLOCKED_BY_POLICY proposal
     * is also "not APPROVED"), the <em>first</em> in this order is the one counted, so the dashboard
     * says "policy", not "state". {@code COOLDOWN} is a proposal-time blocking rule today
     * ({@code PolicyEngine.RULE_COOLDOWN}); it is counted here when someone tries to execute a
     * proposal that rule blocked, so the label exists and reads 0 when it never happened.
     */
    public enum RefusalReason {
        /** Wallet or proposal on mainnet with {@code allow-mainnet=false} (KAN-493). */
        MAINNET,
        /** The approval's timelock has not elapsed (paper §19). */
        TIMELOCK,
        /** The policy blocked it by cooldown ({@code COOLDOWN} rule on the recorded decision). */
        COOLDOWN,
        /** Kill switch, not executable, DENY, policy hash changed, no (I, S) or no oracle reading on the decision. */
        POLICY,
        /** The fresh oracle reading refused the trade at execution time (KAN-439). */
        ORACLE,
        /** Not APPROVED, in flight under another operation, expired, no approval record, cluster mismatch. */
        STATE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * KAN-582: the stages of the demo path, each with the buckets its latency lives in. Simulation,
     * policy, validation and signing are local/RPC round-trips (tens of ms to seconds); submit is one
     * {@code sendTransaction}; confirm waits for the chain (seconds to a couple of minutes);
     * reconcile is one row of the sweep (an RPC lookup, sometimes a retry).
     */
    public enum Stage {
        SIMULATE(0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
        POLICY(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
        VALIDATE(0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
        SIGN(0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
        SUBMIT(0.1, 0.25, 0.5, 1, 2.5, 5, 10),
        CONFIRM(1, 2.5, 5, 10, 15, 30, 60, 90, 120),
        RECONCILE(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60);

        private final double[] bucketsSeconds;

        Stage(double... bucketsSeconds) {
            this.bucketsSeconds = bucketsSeconds;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Upper bounds of the histogram buckets, in seconds, ascending. */
        public Duration[] buckets() {
            Duration[] out = new Duration[bucketsSeconds.length];
            for (int i = 0; i < bucketsSeconds.length; i++) {
                out[i] = Duration.ofMillis(Math.round(bucketsSeconds[i] * 1000));
            }
            return out;
        }
    }
    static final String GLOSSARY_TERM = "cryptobot.glossary.term";
    static final String GLOSSARY_SEARCH = "cryptobot.glossary.search";

    /** Stages of {@code ExecutionService.run}; the one reached when it failed is the label. */
    public enum FailureStage {
        PREFLIGHT,
        /** The independent validator refused, disagreed or could not be reached (KAN-438). */
        VALIDATE,
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
    private final AtomicLong anchorPending = new AtomicLong();

    // KAN-353: the distinct glossary terms seen so far, so the `term` label stays bounded.
    private final Set<String> glossaryTerms = ConcurrentHashMap.newKeySet();

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

    // ---- KAN-440: the deterministic verdict (paper §18) ---------------------------------------

    /** One {@code DeterministicPolicyEngine} verdict: {@code decision} ALLOW | DENY | ESCALATE, {@code escalation} NONE | REQUIRE_SECOND_AGENT | REQUIRE_HUMAN_SIGNATURE. */
    public void policyVerdict(String decision, String escalation) {
        counter(POLICY_VERDICTS, "decision", low(decision), "escalation", low(escalation)).increment();
    }

    /** One failed predicate of a DENY verdict; a verdict with three failures increments three series. */
    public void policyPredicateFailed(String predicate) {
        counter(POLICY_PREDICATE_FAILED, "predicate", low(predicate)).increment();
    }

    // ---- KAN-439: oracle integrity at execution (paper §22) ------------------------------------

    /** The fresh reading right before signing refused the trade: no consensus, breaker tripped, or the plan's price drifted. */
    public void oracleExecutionRefused() {
        counter(ORACLE_EXECUTION_REFUSED).increment();
    }

    // ---- KAN-582: the 409 of /execute, by reason; the demo path, by stage --------------------

    /** {@code POST /execute} refused before any state change; {@code reason} is the most specific one that applied. */
    public void executionRefused(RefusalReason reason) {
        counter(EXECUTION_REFUSED, "reason", (reason == null ? RefusalReason.STATE : reason).label()).increment();
    }

    /** One completed (or failed) pass through a stage of the demo path. */
    public void stageLatency(Stage stage, Duration elapsed) {
        stageTimer(stage).record(elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    /** Starts the clock for {@link #stageLatency(Stage, Duration)}; the caller records on completion or error. */
    public Timer.Sample stageStart() {
        return Timer.start(registry);
    }

    /** Stops a {@link #stageStart()} sample against the stage's histogram. */
    public void stageStop(Stage stage, Timer.Sample sample) {
        if (sample != null) {
            sample.stop(stageTimer(stage));
        }
    }

    private Timer stageTimer(Stage stage) {
        return Timer.builder(STAGE_LATENCY)
                .description("Latency of one stage of the demo path (KAN-582): histogram buckets per stage")
                .tag("stage", stage.label())
                .serviceLevelObjectives(stage.buckets())
                .register(registry);
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

    /** Unresolved dead letters: feeds both {@code dlq_size} (KAN-403 dashboards) and {@code cryptobot_dead_letter_open} (KAN-571). */
    public void dlqSize(long size) {
        dlqSize.set(Math.max(0, size));
    }

    // ---- KAN-571: reconciliation + recovery, visible ------------------------------------------

    /** One reconciled row: {@code matched | corrected | retried | dead_lettered | skipped}. */
    public void reconciliation(String outcome) {
        counter(RECONCILIATION, "outcome", low(outcome)).increment();
    }

    /**
     * One dead letter created ({@code ambiguous | retries_exhausted | inconsistent | outbox}) or
     * closed ({@code resolved | requeued}). Bounded: never the free-text reason.
     */
    public void deadLetter(String reason) {
        counter(DEAD_LETTER, "reason", low(reason)).increment();
    }

    // ---- KAN-391: Decision Receipts / determinismo --------------------------------------------

    public void intelligenceReceipt(String result) {
        counter(INTELLIGENCE_RECEIPTS, "result", low(result)).increment();
    }

    public void deterministicInference() {
        counter(DETERMINISTIC_INFERENCE).increment();
    }

    public void deterministicMismatch() {
        counter(DETERMINISTIC_MISMATCH).increment();
    }

    // ---- KAN-438: independent validator (paper §20) --------------------------------------------

    /** {@code result}: {@code issued} (attestation obtained) | {@code refused} (DENY, disagreement, unreachable). */
    public void validatorAttestation(String result) {
        counter(VALIDATOR_ATTESTATIONS, "result", low(result)).increment();
    }

    // ---- KAN-394: anchoring on devnet ---------------------------------------------------------

    /** {@code submitted | finalized | failed | abandoned}: one per transition of a batch. */
    public void receiptAnchor(String result) {
        counter(RECEIPT_ANCHORS, "result", low(result)).increment();
    }

    /** Receipts stamped by a FINALIZED batch. */
    public void anchoredReceipts(long count) {
        if (count > 0) {
            counter(ANCHORED_RECEIPTS).increment(count);
        }
    }

    /** Receipts still without a finalized anchor, refreshed every sweep. */
    public void anchorPending(long size) {
        anchorPending.set(Math.max(0, size));
    }

    /** From {@code sendTransaction} to {@code finalized}, per batch. */
    public void anchorFinalityLatency(Duration elapsed) {
        Timer.builder(ANCHOR_FINALITY_LATENCY)
                .description("Time from memo broadcast to finalized, per anchoring batch (KAN-394)")
                .register(registry)
                .record(elapsed);
    }

    // ---- KAN-393: provenance DAG ------------------------------------------------------------------

    /** A receipt reused an earlier artifact ({@code REUSES} edge) instead of recomputing it. {@code external}: the artifact came from another tenant (P1). */
    public void artifactReuse(boolean external) {
        counter(ARTIFACT_REUSE, "external", Boolean.toString(external)).increment();
    }

    /** How deep the graph a lineage query returned was (0 = only the roots). */
    public void provenanceDepth(int depth) {
        DistributionSummary.builder(PROVENANCE_DEPTH).description("Max depth of a returned lineage graph (KAN-393)")
                .register(registry).record(Math.max(0, depth));
    }

    // ---- KAN-353: glossary usage (the UI batches, GlossaryEventsService validates) --------------

    /**
     * One glossary interaction on a term: {@code action} is {@code open | search | copy} (already
     * validated by the caller). The term is kept verbatim while fewer than
     * {@link #MAX_GLOSSARY_TERMS} distinct values were seen; after that it is {@code other}.
     */
    public void glossaryTerm(String term, String action) {
        counter(GLOSSARY_TERM, "term", glossaryLabel(term), "action", low(action)).increment();
    }

    /** One search in the glossary: {@code hit=false} means the query matched nothing (a missing term, a product signal). */
    public void glossarySearch(boolean hit) {
        counter(GLOSSARY_SEARCH, "hit", Boolean.toString(hit)).increment();
    }

    /** Verbatim while under the cap, {@code other} beyond it; a term already seen keeps its series forever. */
    String glossaryLabel(String term) {
        if (term == null || term.isBlank()) {
            return TERM_OTHER;
        }
        String t = term.trim();
        if (glossaryTerms.contains(t)) {
            return t;
        }
        if (glossaryTerms.size() >= MAX_GLOSSARY_TERMS) {
            return TERM_OTHER;
        }
        glossaryTerms.add(t);
        return t;
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
        Gauge.builder(DEAD_LETTER_OPEN, dlqSize, AtomicLong::doubleValue)
                .description("Unresolved dead letters (KAN-571); resolve or requeue them via /api/cryptobot/dead-letters").register(registry);
        for (String o : new String[] {"matched", "corrected", "retried", "dead_lettered", "skipped"}) {
            counter(RECONCILIATION, "outcome", o);
        }
        for (String r : new String[] {"ambiguous", "retries_exhausted", "inconsistent", "outbox", "resolved", "requeued"}) {
            counter(DEAD_LETTER, "reason", r);
        }
        counter(TRADE_RECONCILED, "result", "matched");
        counter(TRADE_RECONCILED, "result", "corrected");
        counter(RECONCILIATION_MISMATCH);
        counter(DUPLICATE_TRADE_SUPPRESSED);
        counter(INTELLIGENCE_RECEIPTS, "result", "issued");
        counter(INTELLIGENCE_RECEIPTS, "result", "verified");
        counter(DETERMINISTIC_INFERENCE);
        counter(DETERMINISTIC_MISMATCH);
        counter(VALIDATOR_ATTESTATIONS, "result", "issued");
        counter(VALIDATOR_ATTESTATIONS, "result", "refused");
        // KAN-394: the anchoring batch, so "0 abandoned" is measured and the gauge exists before the first sweep.
        Gauge.builder(ANCHOR_PENDING, anchorPending, AtomicLong::doubleValue)
                .description("Receipts without a finalized devnet anchor (KAN-394)").register(registry);
        for (String r : new String[] {"submitted", "finalized", "failed", "abandoned"}) {
            counter(RECEIPT_ANCHORS, "result", r);
        }
        counter(ANCHORED_RECEIPTS);
        counter(ARTIFACT_REUSE, "external", "false");
        DistributionSummary.builder(PROVENANCE_DEPTH).description("Max depth of a returned lineage graph (KAN-393)").register(registry);
        // The funnel and its failure modes also start at 0 for the asset-less series, so the ratio
        // panels divide by something and the "dónde se cae" panel lists every stage.
        for (String r : new String[] {"awaiting_approval", "blocked_by_policy"}) {
            counter(TRADE_REQUESTED, "result", r, "asset", ASSET_NONE);
        }
        for (String r : new String[] {"approved", "rejected", "expired", "cancelled"}) {
            counter(APPROVALS, "result", r);
        }
        counter(TRADE_SUBMITTED, "asset", ASSET_NONE);
        for (String r : new String[] {"confirmed", "finalized", "pending"}) {
            counter(TRADE_CONFIRMED, "result", r, "asset", ASSET_NONE);
        }
        for (FailureStage s : FailureStage.values()) {
            counter(TRADE_FAILED, "stage", s.label(), "asset", ASSET_NONE);
        }
        // The verdict panel (KAN-440): every decision at 0 so "0 DENY" reads as measured, not missing.
        counter(POLICY_VERDICTS, "decision", "allow", "escalation", "none");
        counter(POLICY_VERDICTS, "decision", "deny", "escalation", "none");
        counter(POLICY_VERDICTS, "decision", "escalate", "escalation", "require_second_agent");
        counter(POLICY_VERDICTS, "decision", "escalate", "escalation", "require_human_signature");
        // KAN-439: "0 refused by the oracle at execution" is measured, not missing.
        counter(ORACLE_EXECUTION_REFUSED);
        // KAN-582: every reason of the 409 at 0, and every stage histogram registered with its buckets,
        // so "Mainnet bloqueado · 409" reads a real series and the latency row is never "No data".
        for (RefusalReason r : RefusalReason.values()) {
            counter(EXECUTION_REFUSED, "reason", r.label());
        }
        for (Stage s : Stage.values()) {
            stageTimer(s);
        }
        // KAN-353: the "búsquedas sin resultado" panel reads 0, not "No data", before the first search.
        counter(GLOSSARY_SEARCH, "hit", "true");
        counter(GLOSSARY_SEARCH, "hit", "false");
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
