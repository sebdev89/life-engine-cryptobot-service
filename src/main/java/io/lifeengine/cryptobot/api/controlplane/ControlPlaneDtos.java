package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.trading.advisor.AdvisorAnswer;
import io.lifeengine.cryptobot.trading.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.trading.risk.RiskReport;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.AuditEvent;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code /api/cryptobot/wallets/**} and {@code /api/cryptobot/proposals/**}. */
public final class ControlPlaneDtos {

    private ControlPlaneDtos() {}

    public record RegisterWalletRequest(String address, String cluster, String label) {}

    public record WalletView(UUID id, String address, String cluster, String label, String explorerUrl, Instant createdAt) {
        public static WalletView of(Wallet w) {
            return new WalletView(w.id(), w.address(), w.cluster().id(), w.label(),
                    io.lifeengine.cryptobot.solana.rpc.SolanaCluster.from(w.cluster()).explorerAddressUrl(w.address()), w.createdAt());
        }
    }

    public record PortfolioResponse(WalletView wallet, PortfolioSnapshot snapshot, RiskReport risk, PortfolioDiff changes) {}

    public record ActivityItem(String signature, long slot, Instant blockTime, boolean failed, String memo, String explorerUrl) {}

    public record AskRequest(String question, UUID proposalId) {}

    /** {@code receiptHash}: the {@code MARKET_ANALYSIS} receipt of this answer. */
    public record AskResponse(AdvisorAnswer answer, UUID runtimeRunId, String runtimeBaseUrl, String ssePath, String receiptHash) {}

    public record MessageView(UUID id, String role, String content, Map<String, Object> structured, UUID runtimeRunId, Instant createdAt) {
        public static MessageView of(AdvisorMessage m) {
            return new MessageView(m.id(), m.role(), m.content(), m.structured(), m.runtimeRunId(), m.createdAt());
        }
    }

    public record CreateProposalRequest(String kind, Map<String, BigDecimal> targetWeights, String counterAsset, String reasoningSummary, UUID runtimeRunId) {}

    public record DecisionRequest(String note) {}

    /** Optional body of {@code POST …/execute}; the {@code Idempotency-Key} header takes precedence. */
    public record ExecuteRequest(String operationId) {}

    public record ProposalView(ActionProposal proposal, List<AuditEvent> audit) {}

    /**
     * (TAE fase 2, mandato §28): {@code POST /api/cryptobot/intents} — el mismo
     * {@code REBALANCE} de {@link CreateProposalRequest}, con {@code walletId} en el body en vez
     * de en el path (no hay wallet-scoped route para este recurso). No es el intent canónico
     * hasheable de la fase de intent canónico (ese trabajo — unir este DTO con {@code core.intent} — sigue en
     * Backlog); es aditivo y deliberadamente mínimo: mismos campos, misma validación, mismo
     * {@link io.lifeengine.cryptobot.application.controlplane.ProposalService#createRebalance}.
     */
    public record ExecutionIntentV1(UUID walletId, String kind, Map<String, BigDecimal> targetWeights, String counterAsset, String reasoningSummary,
            UUID runtimeRunId) {}

    /** The response of {@code POST /intents}: the same {@link ProposalView} a proposal always was, plus {@code executionId == proposal.id()}. */
    public record IntentView(@com.fasterxml.jackson.annotation.JsonUnwrapped ProposalView proposal, UUID executionId) {
        public static IntentView of(ActionProposal proposal) {
            return new IntentView(new ProposalView(proposal, List.of()), proposal.id());
        }
    }

    /** The durable event stream of one proposal: outbox events (with delivery state) and dead letters. */
    public record ProposalEvents(UUID proposalId, String status, UUID operationId, List<OutboxEvent> events, List<DeadLetter> deadLetters) {}

    /**
     * the receipt as the caller has it, for {@code POST /receipts/verify}. {@code body} is
     * the only required field; {@code canonical} substitutes what would normally be a stored
     * (possibly tampered) {@code canonicalJson} column, {@code signature}/{@code keyId} rebuild the
     * {@link IntelligenceReceipt.Signature}, and {@code anchor} lets the same call also resolve
     * {@code anchor.proofValid} without a DB lookup. No {@code parents} override — see {@link
     * io.lifeengine.cryptobot.application.receipt.ReceiptService#reconstruct}.
     */
    public record VerifyReceiptRequest(ReceiptBody body, String canonical, String signature, String keyId, IntelligenceReceipt.Anchor anchor) {}

    public record ApiError(String code, String message, List<String> details) {
        public ApiError(String code, String message) {
            this(code, message, List.of());
        }
    }

    static ActivityItem activity(SolanaRpcClient.SignatureInfo s, String explorerUrl) {
        return new ActivityItem(s.signature(), s.slot(), s.blockTime(), s.failed(), s.memo(), explorerUrl);
    }
}
