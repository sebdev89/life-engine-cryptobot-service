package io.lifeengine.cryptobot.application.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.domain.advisor.AdvisorAnswer;
import io.lifeengine.cryptobot.domain.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.portfolio.Position;
import io.lifeengine.cryptobot.domain.receipt.Digests;
import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.lifeengine.cryptobot.domain.receipt.ReceiptEdge;
import io.lifeengine.cryptobot.domain.receipt.ReceiptInput;
import io.lifeengine.cryptobot.domain.receipt.ReceiptKind;
import io.lifeengine.cryptobot.domain.receipt.ReproducibilityLevel;
import io.lifeengine.cryptobot.domain.risk.RiskFinding;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.SimulationOutcome;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import io.lifeengine.cryptobot.observability.BuildIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the receipt of each of the seven steps that already exist in the control plane (Endgame
 * §19, MVP step 1) from the domain records those steps produce. Everything here is a pure
 * mapping: <b>which hashes go into which body</b>. Nothing is persisted, nothing is signed.
 *
 * <h2>Output schemas</h2>
 *
 * Every output hash is {@code sha256:…} of the RFC 8785 canonical form of a small value tree
 * with a named schema, so a verifier can rebuild it from the stored aggregate:
 *
 * <ul>
 *   <li>{@code portfolio-snapshot/1} — the valued positions (amounts, prices, weights as decimal strings).
 *   <li>{@code risk-report/1} — codes, severities, metrics, thresholds, overall, score. No text, no timestamp: L1.
 *   <li>{@code user-text-commitment/1} — {@code H(salt_tenant ‖ 0x00 ‖ text)}: the question never leaves.
 *   <li>{@code advisor-answer/1} — the structured answer the LLM returned.
 *   <li>{@code rebalance-plan/1} — the legs, weights before/after, turnover. Deterministic planner: L1.
 *   <li>{@code simulation/1} — economic expectation + on-chain ok/units (logs excluded).
 *   <li>{@code execution/1} — status, signature, confirmation, error.
 * </ul>
 *
 * <p>Tenancy: CryptoBot's tenancy key is the owner user id from the JWT {@code sub} (V4), so
 * {@code tenantId = ownerId} until CryptoBot has multi-user tenants.
 */
@Component
public class Receipts {

    static final String ENGINE_RISK = "risk-engine";
    static final String ENGINE_PLANNER = "rebalance-planner";
    static final String ENGINE_VERSION = "1.0.0";
    static final String AGENT_PORTFOLIO = "portfolio-agent@1.0.0";
    static final String AGENT_RISK = "risk-engine@" + ENGINE_VERSION;
    static final String AGENT_HUMAN = "human";
    static final String AGENT_STRATEGY = "strategy-agent@1.0.0";
    static final String AGENT_EXECUTION = "execution-agent@1.0.0";
    static final String PROVIDER_RUNTIME = "life-engine-runtime";

    private final TenantSalts salts;
    private final RiskRulesProperties riskRules;
    private final BuildIdentity build;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public Receipts(TenantSalts salts, RiskRulesProperties riskRules, BuildIdentity build, ObjectMapper objectMapper) {
        this(salts, riskRules, build, objectMapper, Clock.systemUTC());
    }

    public Receipts(TenantSalts salts, RiskRulesProperties riskRules, BuildIdentity build, ObjectMapper objectMapper, Clock clock) {
        this.salts = salts;
        this.riskRules = riskRules;
        this.build = build;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public static String tenantOf(UUID ownerUserId) {
        return ownerUserId.toString();
    }

    // ---- 1. WALLET_SNAPSHOT ---------------------------------------------------------------------

    public ReceiptDraft walletSnapshot(Wallet wallet, PortfolioSnapshot snapshot) {
        List<ReceiptInput> inputs = List.of(
                new ReceiptInput(ReceiptInput.WALLET, hash(ordered("address", wallet.address(), "cluster", wallet.cluster().id()))),
                new ReceiptInput(ReceiptInput.CHAIN_HOLDINGS, hash(holdings(snapshot))),
                new ReceiptInput(ReceiptInput.PRICE_QUOTES, hash(quotes(snapshot))));
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, tenantOf(wallet.ownerUserId()), wallet.ownerUserId().toString(), AGENT_PORTFOLIO,
                List.of(), inputs, null, null, null, runtimeRef(null), Map.of("priceSource", snapshot.priceSource()),
                new ReceiptBody.Output(snapshotHash(snapshot), "portfolio-snapshot/1", "portfolio_snapshot:" + snapshot.id()),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, snapshot.capturedAt(), clock.instant(),
                snapshot.id().toString(), new ReceiptBody.Refs(wallet.id().toString(), null, snapshot.id().toString()));
        return ReceiptDraft.of(body).withArtifact("PORTFOLIO_SNAPSHOT", "portfolio-snapshot/1", "portfolio_snapshot:" + snapshot.id());
    }

    // ---- 2. RISK_DECISION ------------------------------------------------------------------------

    /** The engine's verdict over a stored snapshot (parent: its WALLET_SNAPSHOT receipt, if it has one). */
    public ReceiptDraft riskDecision(Wallet wallet, PortfolioSnapshot snapshot, PortfolioDiff diff, RiskReport report, String snapshotReceipt) {
        List<ReceiptInput> inputs = new ArrayList<>();
        inputs.add(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, snapshotHash(snapshot)));
        if (diff != null) {
            inputs.add(new ReceiptInput(ReceiptInput.PORTFOLIO_DIFF, hash(diffTree(diff))));
        }
        List<String> parents = snapshotReceipt == null ? List.of() : List.of(snapshotReceipt);
        ReceiptBody body = riskBody(wallet, report, parents, inputs, "risk:" + snapshot.id(), new ReceiptBody.Refs(wallet.id().toString(), null, snapshot.id().toString()),
                "risk_report:portfolio_snapshot:" + snapshot.id());
        return ReceiptDraft.of(body);
    }

    /** The engine's verdict over the portfolio a plan would leave (parent: the STRATEGY receipt, role VALIDATES). */
    public ReceiptDraft riskDecisionAfter(Wallet wallet, ActionProposal proposal, PortfolioSnapshot projected, RiskReport report, String strategyReceipt) {
        List<ReceiptInput> inputs = List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, snapshotHash(projected)));
        ReceiptBody body = riskBody(wallet, report, List.of(strategyReceipt), inputs, "risk-after:" + proposal.id(),
                new ReceiptBody.Refs(wallet.id().toString(), proposal.id().toString(), null), "risk_report:action_proposal:" + proposal.id() + "#riskAfter");
        return ReceiptDraft.of(body).withRole(strategyReceipt, ReceiptEdge.Role.VALIDATES);
    }

    private ReceiptBody riskBody(Wallet wallet, RiskReport report, List<String> parents, List<ReceiptInput> inputs, String nonce, ReceiptBody.Refs refs, String ref) {
        return new ReceiptBody(null, ReceiptKind.RISK_DECISION, tenantOf(wallet.ownerUserId()), wallet.ownerUserId().toString(), AGENT_RISK,
                parents, inputs, null, null, new ReceiptBody.Engine(ENGINE_RISK, ENGINE_VERSION, riskWeightsHash()), runtimeRef(null), Map.of(),
                new ReceiptBody.Output(riskHash(report), "risk-report/1", ref), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L1_REPRODUCIBLE, report.evaluatedAt(), clock.instant(), nonce, refs);
    }

    /** {@code weightsHash} of the risk engine: the thresholds it runs with, canonicalised. Same rules ⇒ same hash on any host. */
    public String riskWeightsHash() {
        return hash(ordered("concentrationHighPct", dec(riskRules.concentrationHighPct()), "concentrationMediumPct", dec(riskRules.concentrationMediumPct()),
                "minStablePct", dec(riskRules.minStablePct()), "dustUsd", dec(riskRules.dustUsd()), "sharpMovePct", dec(riskRules.sharpMovePct())));
    }

    // ---- 3. HUMAN_IDEA ---------------------------------------------------------------------------

    public ReceiptDraft humanIdea(Wallet wallet, UUID messageId, String question, Instant askedAt, String snapshotReceipt) {
        String commitment = salts.commit(tenantOf(wallet.ownerUserId()), question);
        List<String> parents = snapshotReceipt == null ? List.of() : List.of(snapshotReceipt);
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.HUMAN_IDEA, tenantOf(wallet.ownerUserId()), wallet.ownerUserId().toString(), AGENT_HUMAN,
                parents, List.of(new ReceiptInput(ReceiptInput.USER_TEXT, commitment)), null, null, null, runtimeRef(null), Map.of(),
                new ReceiptBody.Output(commitment, "user-text-commitment/1", "advisor_message:" + messageId), null, null,
                ReproducibilityLevel.L0_SIGNED, askedAt, clock.instant(), messageId.toString(), new ReceiptBody.Refs(wallet.id().toString(), null, null));
        return ReceiptDraft.of(body);
    }

    // ---- 4. MARKET_ANALYSIS (the advisor's LLM answer) ---------------------------------------------

    /**
     * @param inputJson the exact payload sent to the Runtime workflow: committed under the tenant salt, never stored in the receipt
     * @param parents HUMAN_IDEA, WALLET_SNAPSHOT and RISK_DECISION receipts that were available (any may be absent)
     */
    public ReceiptDraft marketAnalysis(Wallet wallet, String workflowId, String locale, String inputJson, String questionCommitment, AdvisorAnswer answer,
            RuntimeRunDetail run, UUID messageId, List<String> parents, Instant startedAt) {
        String tenant = tenantOf(wallet.ownerUserId());
        List<ReceiptInput> inputs = new ArrayList<>();
        inputs.add(new ReceiptInput(ReceiptInput.USER_TEXT, questionCommitment));
        for (String p : parents) {
            inputs.add(ReceiptInput.receipt(p));
        }
        Usage usage = usageOf(run);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", workflowId);
        params.put("locale", locale);
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.MARKET_ANALYSIS, tenant, wallet.ownerUserId().toString(), "market-agent@" + workflowId,
                parents, inputs, salts.commit(tenant, inputJson), new ReceiptBody.Model(answer.model() == null ? "unknown" : answer.model(), PROVIDER_RUNTIME, null, null),
                null, runtimeRef(run.runId()), params,
                new ReceiptBody.Output(hash(answerTree(answer)), "advisor-answer/1", "advisor_message:" + messageId),
                usage.compute(), null, ReproducibilityLevel.L0_SIGNED,
                run.startedAt() == null ? startedAt : run.startedAt(), run.finishedAt() == null ? clock.instant() : run.finishedAt(),
                run.runId().toString(), new ReceiptBody.Refs(wallet.id().toString(), null, null));
        return ReceiptDraft.of(body).withArtifact("ADVISOR_ANSWER", "advisor-answer/1", "advisor_message:" + messageId);
    }

    // ---- 5. STRATEGY -----------------------------------------------------------------------------

    public ReceiptDraft strategy(Wallet wallet, ActionProposal proposal, PortfolioSnapshot snapshot, RebalanceIntent intent, RebalancePlan plan,
            String snapshotReceipt, String analysisReceipt) {
        List<String> parents = new ArrayList<>();
        if (snapshotReceipt != null) {
            parents.add(snapshotReceipt);
        }
        if (analysisReceipt != null) {
            parents.add(analysisReceipt);
        }
        List<ReceiptInput> inputs = List.of(
                new ReceiptInput(ReceiptInput.INTENT, hash(intentTree(intent))),
                new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, snapshotHash(snapshot)));
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.STRATEGY, tenantOf(wallet.ownerUserId()), wallet.ownerUserId().toString(), AGENT_STRATEGY,
                parents, inputs, null, null, new ReceiptBody.Engine(ENGINE_PLANNER, ENGINE_VERSION, null), runtimeRef(proposal.runtimeRunId()),
                Map.of("kind", proposal.kind()), new ReceiptBody.Output(hash(planTree(plan)), "rebalance-plan/1", "action_proposal:" + proposal.id() + "#plan"),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L1_REPRODUCIBLE, proposal.createdAt(), clock.instant(),
                proposal.id().toString(), new ReceiptBody.Refs(wallet.id().toString(), proposal.id().toString(), snapshot.id().toString()));
        return ReceiptDraft.of(body).withArtifact("REBALANCE_PLAN", "rebalance-plan/1", "action_proposal:" + proposal.id() + "#plan");
    }

    // ---- 6. SIMULATION ---------------------------------------------------------------------------

    public ReceiptDraft simulation(ActionProposal proposal, SimulationOutcome outcome, PreparedTransaction tx, String strategyReceipt, Instant startedAt) {
        List<ReceiptInput> inputs = new ArrayList<>();
        if (tx != null) {
            inputs.add(new ReceiptInput(ReceiptInput.TRANSACTION, messageHash(tx)));
        }
        List<String> parents = strategyReceipt == null ? List.of() : List.of(strategyReceipt);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cluster", proposal.cluster());
        params.put("sigVerify", false);
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.SIMULATION, tenantOf(proposal.ownerUserId()), proposal.ownerUserId().toString(), AGENT_EXECUTION,
                parents, inputs, null, null, null, runtimeRef(null), params,
                new ReceiptBody.Output(hash(simulationTree(outcome)), "simulation/1", "action_proposal:" + proposal.id() + "#simulation"),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, startedAt, clock.instant(),
                "sim:" + proposal.id(), new ReceiptBody.Refs(proposal.walletId().toString(), proposal.id().toString(), proposal.snapshotId() == null ? null : proposal.snapshotId().toString()));
        return ReceiptDraft.of(body);
    }

    // ---- 7. EXECUTION ----------------------------------------------------------------------------

    /**
     * Issued once per execution attempt, at its terminal state ({@code EXECUTED} or {@code FAILED}).
     * Nonce = the operation id: the idempotency key of KAN-403 is also the replay guard of the receipt.
     */
    public ReceiptDraft execution(ActionProposal proposal, String simulationReceipt, String strategyReceipt, Instant startedAt) {
        ExecutionRecord exec = proposal.execution();
        List<ReceiptInput> inputs = new ArrayList<>();
        if (proposal.transaction() != null) {
            inputs.add(new ReceiptInput(ReceiptInput.TRANSACTION, messageHash(proposal.transaction())));
        }
        if (proposal.policy() != null && proposal.policy().authorization() != null) {
            inputs.add(new ReceiptInput(ReceiptInput.POLICY_VERDICT, proposal.policy().authorization().hash()));
        }
        if (proposal.approval() != null) {
            inputs.add(new ReceiptInput(ReceiptInput.APPROVAL, hash(ordered("decision", proposal.approval().decision().name(), "by", proposal.approval().by(),
                    "at", proposal.approval().at().toString()))));
        }
        List<String> parents = new ArrayList<>();
        if (simulationReceipt != null) {
            parents.add(simulationReceipt);
        }
        if (strategyReceipt != null) {
            parents.add(strategyReceipt);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", exec == null ? ExecutionRecord.FAILED : exec.status());
        out.put("cluster", proposal.cluster());
        if (proposal.transaction() != null) {
            out.put("lamports", proposal.transaction().lamports());
        }
        if (exec != null) {
            putIfPresent(out, "signature", exec.signature());
            putIfPresent(out, "signerPublicKey", exec.signerPublicKey());
            putIfPresent(out, "confirmationStatus", exec.confirmationStatus());
            putIfPresent(out, "error", exec.error());
            putIfPresent(out, "recentBlockhash", exec.recentBlockhash());
        }
        Long wallMs = startedAt == null ? null : Math.max(0, clock.instant().toEpochMilli() - startedAt.toEpochMilli());
        String nonce = "exec:" + (proposal.operationId() == null ? proposal.id() : proposal.operationId());
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.EXECUTION, tenantOf(proposal.ownerUserId()), proposal.ownerUserId().toString(), AGENT_EXECUTION,
                parents, inputs, null, null, null, runtimeRef(null), Map.of("cluster", proposal.cluster()),
                new ReceiptBody.Output(hash(out), "execution/1", "action_proposal:" + proposal.id() + "#execution"),
                new ReceiptBody.Compute(null, null, 1, wallMs), null, ReproducibilityLevel.L0_SIGNED, startedAt == null ? clock.instant() : startedAt,
                clock.instant(), nonce, new ReceiptBody.Refs(proposal.walletId().toString(), proposal.id().toString(), proposal.snapshotId() == null ? null : proposal.snapshotId().toString()));
        ReceiptDraft draft = ReceiptDraft.of(body);
        return strategyReceipt == null ? draft : draft.withRole(strategyReceipt, ReceiptEdge.Role.EXECUTES);
    }

    // ---- canonical trees of the domain records --------------------------------------------------

    /** {@code portfolio-snapshot/1}. */
    public static String snapshotHash(PortfolioSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshotId", s.id().toString());
        m.put("walletId", s.walletId().toString());
        m.put("capturedAt", s.capturedAt().toString());
        m.put("totalUsd", dec(s.totalUsd()));
        m.put("priceSource", s.priceSource() == null ? "none" : s.priceSource());
        List<Map<String, Object>> positions = new ArrayList<>();
        for (Position p : s.positions()) {
            positions.add(ordered("mint", p.mint(), "symbol", p.symbol(), "amount", dec(p.amount()), "decimals", p.decimals(), "priceUsd", dec(p.priceUsd()),
                    "valueUsd", dec(p.valueUsd()), "weightPct", dec(p.weightPct()), "stable", p.stable(), "nativeSol", p.nativeSol()));
        }
        m.put("positions", positions);
        return hash(m);
    }

    private static Map<String, Object> holdings(PortfolioSnapshot s) {
        List<Map<String, Object>> h = new ArrayList<>();
        for (Position p : s.positions()) {
            h.add(ordered("mint", p.mint(), "amount", dec(p.amount()), "decimals", p.decimals()));
        }
        return ordered("holdings", h);
    }

    private static Map<String, Object> quotes(PortfolioSnapshot s) {
        List<Map<String, Object>> q = new ArrayList<>();
        for (Position p : s.positions()) {
            if (p.priced()) {
                q.add(ordered("mint", p.mint(), "priceUsd", dec(p.priceUsd()), "source", p.priceSource()));
            }
        }
        return ordered("quotes", q);
    }

    private static Map<String, Object> diffTree(PortfolioDiff d) {
        return ordered("previousCapturedAt", d.previousCapturedAt() == null ? null : d.previousCapturedAt().toString(),
                "currentCapturedAt", d.currentCapturedAt() == null ? null : d.currentCapturedAt().toString(),
                "totalUsdBefore", dec(d.totalUsdBefore()), "totalUsdAfter", dec(d.totalUsdAfter()), "totalUsdDeltaPct", dec(d.totalUsdDeltaPct()),
                "largestMoveSymbol", d.largestMoveSymbol(), "largestWeightPctDelta", dec(d.largestWeightPctDelta()));
    }

    /** {@code risk-report/1}: the discrete verdict, no prose, no timestamp — what an L1 verifier recomputes. */
    public static String riskHash(RiskReport r) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (RiskFinding f : r.findings()) {
            findings.add(ordered("code", f.code(), "severity", f.severity().name(), "asset", f.asset(), "metric", dec(f.metric()), "threshold", dec(f.threshold())));
        }
        return hash(ordered("overall", r.overall().name(), "score", r.score(), "findings", findings));
    }

    private static Map<String, Object> answerTree(AdvisorAnswer a) {
        List<Map<String, Object>> risks = new ArrayList<>();
        for (AdvisorAnswer.KeyRisk k : a.keyRisks()) {
            risks.add(ordered("title", k.title(), "severity", k.severity(), "why", k.why()));
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (AdvisorAnswer.SuggestedAction s : a.suggestedActions()) {
            actions.add(ordered("action", s.action(), "asset", s.asset(), "targetWeightPct", dec(s.targetWeightPct()), "rationale", s.rationale()));
        }
        return ordered("answer", a.answer(), "keyRisks", risks, "suggestedActions", actions, "confidence", dec(a.confidence()),
                "disclaimer", a.disclaimer(), "promptVersion", a.promptVersion());
    }

    private static Map<String, Object> intentTree(RebalanceIntent i) {
        Map<String, Object> weights = new TreeMap<>();
        i.targetWeights().forEach((k, v) -> weights.put(k, dec(v)));
        return ordered("targetWeights", weights, "counterAsset", i.counterAsset());
    }

    /** {@code rebalance-plan/1}. */
    public static String planHash(RebalancePlan p) {
        return hash(planTree(p));
    }

    private static Map<String, Object> planTree(RebalancePlan p) {
        List<Map<String, Object>> legs = new ArrayList<>();
        for (RebalanceLeg l : p.legs()) {
            legs.add(ordered("action", l.action().name(), "symbol", l.symbol(), "mint", l.mint(), "amount", dec(l.amount()), "estimatedUsd", dec(l.estimatedUsd()),
                    "weightPctBefore", dec(l.weightPctBefore()), "weightPctAfter", dec(l.weightPctAfter()), "counterAsset", l.counterAsset()));
        }
        Map<String, Object> before = new TreeMap<>();
        p.weightsBefore().forEach((k, v) -> before.put(k, dec(v)));
        Map<String, Object> after = new TreeMap<>();
        p.weightsAfter().forEach((k, v) -> after.put(k, dec(v)));
        return ordered("legs", legs, "totalUsd", dec(p.totalUsd()), "weightsBefore", before, "weightsAfter", after, "turnoverUsd", dec(p.turnoverUsd()));
    }

    private static Map<String, Object> simulationTree(SimulationOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o.economic() != null) {
            SimulationOutcome.Economic e = o.economic();
            m.put("economic", ordered("sellSymbol", e.sellSymbol(), "sellAmount", dec(e.sellAmount()), "buySymbol", e.buySymbol(),
                    "expectedBuyAmount", dec(e.expectedBuyAmount()), "priceUsd", dec(e.priceUsd()), "estimatedFeeSol", dec(e.estimatedFeeSol()),
                    "priceImpactPct", dec(e.priceImpactPct()), "source", e.source()));
        }
        if (o.onchain() != null) {
            SimulationOutcome.Onchain c = o.onchain();
            m.put("onchain", ordered("ok", c.ok(), "error", c.error(), "unitsConsumed", c.unitsConsumed(), "cluster", c.cluster()));
        }
        return m;
    }

    private static String messageHash(PreparedTransaction tx) {
        return Digests.sha256(java.util.Base64.getDecoder().decode(tx.messageBase64()));
    }

    private ReceiptBody.RuntimeRef runtimeRef(UUID runId) {
        return new ReceiptBody.RuntimeRef(runId == null ? null : runId.toString(), null, null,
                build == null ? null : build.serviceVersion(), build == null ? null : build.gitCommit());
    }

    // ---- Runtime usage (tokens) from the run's events -------------------------------------------

    /**
     * What Runtime measured for the run: token counts from every {@code LLM_CALL_SUCCEEDED} event,
     * latency from the same. No tokens ⇒ no {@code compute} block: an honest receipt omits what was
     * not measured rather than estimating it (§8). Cost is never claimed in this version: there is
     * no price table yet, so {@code cost} is absent everywhere.
     */
    record Usage(Long inputTokens, Long outputTokens, Long wallMs) {
        ReceiptBody.Compute compute() {
            if (inputTokens == null && outputTokens == null) {
                return null;
            }
            long in = inputTokens == null ? 0 : inputTokens;
            long out = outputTokens == null ? 0 : outputTokens;
            return new ReceiptBody.Compute(inputTokens, outputTokens, in + 3 * out, wallMs);
        }
    }

    Usage usageOf(RuntimeRunDetail run) {
        long in = 0;
        long out = 0;
        long wall = 0;
        boolean anyTokens = false;
        boolean anyLatency = false;
        for (RuntimeRunDetail.RuntimeEventSlice ev : run.events()) {
            if (!"LLM_CALL_SUCCEEDED".equals(ev.type())) {
                continue;
            }
            String usage = ev.attributes().get("usage");
            if (usage != null && !usage.isBlank()) {
                try {
                    JsonNode n = objectMapper.readTree(usage);
                    if (n.hasNonNull("prompt_tokens") || n.hasNonNull("completion_tokens")) {
                        in += n.path("prompt_tokens").asLong(0);
                        out += n.path("completion_tokens").asLong(0);
                        anyTokens = true;
                    }
                } catch (Exception ignored) {
                    // usage that is not JSON is not a measurement
                }
            }
            String latency = ev.attributes().get("latencyMs");
            if (latency != null) {
                try {
                    wall += Long.parseLong(latency.trim());
                    anyLatency = true;
                } catch (NumberFormatException ignored) {
                    // same
                }
            }
        }
        return new Usage(anyTokens ? in : null, anyTokens ? out : null, anyLatency ? wall : null);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    static String hash(Map<String, Object> tree) {
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(tree));
    }

    static String dec(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            putIfPresent(m, (String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
