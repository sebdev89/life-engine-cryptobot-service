package io.lifeengine.cryptobot.application.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.trading.advisor.AdvisorAnswer;
import io.lifeengine.cryptobot.trading.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.trading.portfolio.Position;
import io.lifeengine.cryptobot.trading.risk.RiskFinding;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AdvisorMessageRepository;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeRunDetail;
import io.lifeengine.cryptobot.integration.lifeengine.AdvisorProperties;
import io.lifeengine.cryptobot.integration.lifeengine.AdvisorRuntimeClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wallet chat. Builds the facts (positions, weights, risk findings, policy limits), sends them
 * to the Runtime workflow {@code crypto.portfolio-advisor.v1}, waits for the run, parses the
 * strict JSON, persists both turns. The LLM sees numbers we computed; it never sees keys, the
 * raw RPC payloads, or the transaction bytes.
 */
@Service
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);
    public static final String INPUT_CONTRACT = "crypto.portfolio-advisor-input.v1";
    /** A base58 string of 87–88 chars is the shape of a 64-byte secret key. Refuse to forward it anywhere. */
    private static final Pattern SECRET_SHAPE = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{64,90}");
    private static final int MAX_QUESTION_CHARS = 1000;
    static final int MAX_POSITIONS_FOR_LLM = 15;

    /** {@code receiptHash} is the {@code MARKET_ANALYSIS} receipt of this answer. */
    public record Asked(AdvisorAnswer answer, UUID runtimeRunId, String runtimeBaseUrl, String ssePath, String receiptHash) {}

    private final PortfolioService portfolio;
    private final AdvisorRuntimeClient runtime;
    private final AdvisorProperties props;
    private final PolicyProperties policy;
    private final AdvisorMessageRepository messages;
    private final ObjectMapper objectMapper;
    private final ReceiptService receipts;
    private final Receipts receiptOf;
    private final TenantSalts salts;
    private final Clock clock;

    public AdvisorService(PortfolioService portfolio, AdvisorRuntimeClient runtime, AdvisorProperties props, PolicyProperties policy,
            AdvisorMessageRepository messages, ObjectMapper objectMapper, ReceiptService receipts, Receipts receiptOf, TenantSalts salts) {
        this.portfolio = portfolio;
        this.runtime = runtime;
        this.props = props;
        this.policy = policy;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.receipts = receipts;
        this.receiptOf = receiptOf;
        this.salts = salts;
        this.clock = Clock.systemUTC();
    }

    /**
     * two receipts per question. {@code HUMAN_IDEA} before the Runtime is called (the
     * question as a salted commitment, parent: the wallet's latest {@code WALLET_SNAPSHOT}), and
     * {@code MARKET_ANALYSIS} once the run succeeded (prompt commitment, model, run id, tokens if
     * Runtime reported them, hash of the structured answer; parents: the idea, the snapshot and
     * its {@code RISK_DECISION}). The prompt and the answer never enter a receipt.
     */
    public Mono<Asked> ask(Wallet wallet, String actor, String rawQuestion, ActionProposal proposalContext, String bearer) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isEmpty()) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("EMPTY_QUESTION", "Ask something about the wallet"));
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("QUESTION_TOO_LONG", "Keep the question under " + MAX_QUESTION_CHARS + " characters"));
        }
        if (looksLikeSecret(question)) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("SECRET_IN_QUESTION",
                    "That looks like a private key. CryptoBot never needs it and will not forward it — remove it and ask again."));
        }
        return portfolio.latest(wallet).flatMap(view -> {
            String input = buildInput(wallet, view, question, proposalContext);
            String correlationId = "cryptobot-advisor-" + UUID.randomUUID();
            String tenant = Receipts.tenantOf(wallet.ownerUserId());
            Instant askedAt = clock.instant();
            AdvisorMessage userTurn = new AdvisorMessage(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), "user", question, Map.of(), null, askedAt);
            // The receipts of the snapshot the advisor is looking at (absent for snapshots older than the receipt layer).
            Mono<Optional<String>> snapshotReceipt = receipts.byNonce(tenant, view.snapshot().id().toString())
                    .map(r -> Optional.of(r.receiptHash())).defaultIfEmpty(Optional.empty());
            Mono<Optional<String>> riskReceipt = receipts.byNonce(tenant, "risk:" + view.snapshot().id())
                    .map(r -> Optional.of(r.receiptHash())).defaultIfEmpty(Optional.empty());
            return messages.insert(userTurn)
                    .then(Mono.zip(snapshotReceipt, riskReceipt))
                    .flatMap(refs -> receipts.issue(receiptOf.humanIdea(wallet, userTurn.id(), question, askedAt, refs.getT1().orElse(null)))
                            .map(idea -> new Context(idea.receiptHash(), refs.getT1().orElse(null), refs.getT2().orElse(null))))
                    .flatMap(ctx -> runtime.start(input, correlationId, bearer)
                            .flatMap(started -> runtime.awaitTerminal(started.runId(), bearer)
                                    .flatMap(done -> {
                                        AdvisorAnswer answer = parse(done.detail());
                                        AdvisorMessage assistantTurn = new AdvisorMessage(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), "assistant",
                                                answer.answer(), asMap(answer), started.runId(), clock.instant());
                                        List<String> parents = new ArrayList<>();
                                        parents.add(ctx.ideaReceipt());
                                        if (ctx.snapshotReceipt() != null) {
                                            parents.add(ctx.snapshotReceipt());
                                        }
                                        if (ctx.riskReceipt() != null) {
                                            parents.add(ctx.riskReceipt());
                                        }
                                        return messages.insert(assistantTurn)
                                                .then(receipts.issue(receiptOf.marketAnalysis(wallet, runtime.workflowId(), props.locale(), input,
                                                        salts.commit(tenant, question), answer, done.detail(), assistantTurn.id(), parents, askedAt)))
                                                .map(analysis -> new Asked(answer, started.runId(), runtime.runtimeBaseUrl(),
                                                        "/api/runtime/runs/" + started.runId() + "/events", analysis.receiptHash()));
                                    })))
                    .onErrorMap(java.util.concurrent.TimeoutException.class, ex -> new ControlPlaneExceptions.UpstreamUnavailable("The advisor did not answer in time", ex));
        });
    }

    private record Context(String ideaReceipt, String snapshotReceipt, String riskReceipt) {}

    public Flux<AdvisorMessage> history(UUID walletId, int limit) {
        return messages.findByWallet(walletId, limit);
    }

    static boolean looksLikeSecret(String text) {
        Matcher m = SECRET_SHAPE.matcher(text);
        while (m.find()) {
            try {
                if (Base58.decode(m.group()).length == 64) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // not base58 after all
            }
        }
        String lower = text.toLowerCase();
        return lower.contains("seed phrase") || lower.contains("private key:") || lower.contains("secret key:");
    }

    String buildInput(Wallet wallet, PortfolioService.PortfolioView view, String question, ActionProposal proposalContext) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("contractId", INPUT_CONTRACT);
        in.put("question", question);
        in.put("locale", props.locale());
        in.put("wallet", Map.of("address", wallet.address(), "cluster", wallet.cluster().id()));

        Map<String, Object> pf = new LinkedHashMap<>();
        pf.put("totalUsd", scale(view.snapshot().totalUsd(), 2));
        // The Runtime caps input at 32k chars and the model has a context budget: send the priced
        // positions (already sorted by value, capped) and only a count for the unpriced tail. A busy
        // mainnet wallet can hold hundreds of airdrop/spam mints; none of them change the analysis.
        List<Map<String, Object>> positions = new ArrayList<>();
        int unpriced = 0;
        for (Position p : view.snapshot().positions()) {
            if (!p.priced()) {
                unpriced++;
                continue;
            }
            if (positions.size() >= MAX_POSITIONS_FOR_LLM) {
                continue;
            }
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("symbol", p.symbol());
            pos.put("amount", scale(p.amount(), 6));
            pos.put("priceUsd", p.priceUsd() == null ? null : scale(p.priceUsd(), 4));
            pos.put("valueUsd", p.valueUsd() == null ? null : scale(p.valueUsd(), 2));
            pos.put("weightPct", p.weightPct() == null ? null : scale(p.weightPct(), 1));
            pos.put("stable", p.stable());
            positions.add(pos);
        }
        pf.put("positions", positions);
        pf.put("unpricedPositions", unpriced);
        pf.put("positionsOmitted", Math.max(0, (int) view.snapshot().positions().stream().filter(Position::priced).count() - positions.size()));
        if (view.changes() != null) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("totalUsdPct", view.changes().totalUsdDeltaPct() == null ? null : scale(view.changes().totalUsdDeltaPct(), 2));
            ch.put("largestMove", view.changes().largestMoveSymbol() == null ? null
                    : Map.of("symbol", view.changes().largestMoveSymbol(), "weightPctDelta", scale(view.changes().largestWeightPctDelta(), 2)));
            ch.put("previousCapturedAt", view.changes().previousCapturedAt() == null ? null : view.changes().previousCapturedAt().toString());
            pf.put("changeSincePrevious", ch);
        }
        in.put("portfolio", pf);

        List<Map<String, Object>> findings = new ArrayList<>();
        for (RiskFinding f : view.risk().findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("severity", f.severity().name());
            m.put("title", f.title());
            m.put("detail", f.detail());
            m.put("asset", f.asset());
            m.put("metric", f.metric() == null ? null : scale(f.metric(), 2));
            m.put("threshold", f.threshold() == null ? null : scale(f.threshold(), 2));
            findings.add(m);
        }
        in.put("riskFindings", findings);
        in.put("riskScore", view.risk().score());
        in.put("policy", Map.of("executionCluster", policy.executionCluster(), "maxTradeUsd", policy.maxTradeUsd(),
                "allowedAssets", policy.allowedAssets(), "humanApprovalRequired", true));
        if (proposalContext != null) {
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("status", proposalContext.status().name());
            pr.put("plan", proposalContext.plan() == null ? null : proposalContext.plan().summary());
            pr.put("riskAfter", proposalContext.riskAfter() == null ? null : proposalContext.riskAfter().overall().name());
            in.put("proposal", pr);
        } else {
            in.put("proposal", null);
        }
        try {
            return objectMapper.writeValueAsString(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build advisor input", e);
        }
    }

    AdvisorAnswer parse(RuntimeRunDetail detail) {
        if (!"SUCCEEDED".equals(detail.status())) {
            throw new ControlPlaneExceptions.UpstreamUnavailable("Advisor run " + detail.runId() + " ended " + detail.status() + ": " + detail.terminalError(), null);
        }
        String output = detail.agentStages().isEmpty() ? null : detail.agentStages().get(detail.agentStages().size() - 1).output();
        if (output == null || output.isBlank()) {
            throw new ControlPlaneExceptions.UpstreamUnavailable("Advisor run produced no output", null);
        }
        String model = detail.llmCalls().isEmpty() ? null : detail.llmCalls().get(detail.llmCalls().size() - 1).model();
        try {
            JsonNode n = objectMapper.readTree(output);
            List<AdvisorAnswer.KeyRisk> risks = new ArrayList<>();
            for (JsonNode r : n.path("keyRisks")) {
                risks.add(new AdvisorAnswer.KeyRisk(r.path("title").asText(""), r.path("severity").asText("MEDIUM"), r.path("why").asText("")));
            }
            List<AdvisorAnswer.SuggestedAction> actions = new ArrayList<>();
            for (JsonNode a : n.path("suggestedActions")) {
                JsonNode t = a.path("targetWeightPct");
                actions.add(new AdvisorAnswer.SuggestedAction(a.path("action").asText("HOLD"), a.path("asset").asText(null),
                        t.isNumber() ? t.decimalValue() : null, a.path("rationale").asText("")));
            }
            JsonNode conf = n.path("confidence");
            return new AdvisorAnswer(n.path("answer").asText(""), risks, actions, conf.isNumber() ? conf.decimalValue() : null,
                    n.path("disclaimer").asText(""), n.path("promptVersion").asText(""), detail.runId(), model);
        } catch (Exception e) {
            throw new ControlPlaneExceptions.UpstreamUnavailable("Advisor output is not the expected JSON", e);
        }
    }

    private Map<String, Object> asMap(AdvisorAnswer answer) {
        return objectMapper.convertValue(answer, new TypeReference<Map<String, Object>>() {});
    }

    private static BigDecimal scale(BigDecimal v, int s) {
        return v == null ? null : v.setScale(s, RoundingMode.HALF_UP);
    }
}
