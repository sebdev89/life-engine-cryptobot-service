package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.domain.advisor.AdvisorAnswer;
import io.lifeengine.cryptobot.domain.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
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
            return new WalletView(w.id(), w.address(), w.cluster().id(), w.label(), w.cluster().explorerAddressUrl(w.address()), w.createdAt());
        }
    }

    public record PortfolioResponse(WalletView wallet, PortfolioSnapshot snapshot, RiskReport risk, PortfolioDiff changes) {}

    public record ActivityItem(String signature, long slot, Instant blockTime, boolean failed, String memo, String explorerUrl) {}

    public record AskRequest(String question, UUID proposalId) {}

    /** {@code receiptHash}: the {@code MARKET_ANALYSIS} receipt of this answer (KAN-391). */
    public record AskResponse(AdvisorAnswer answer, UUID runtimeRunId, String runtimeBaseUrl, String ssePath, String receiptHash) {}

    public record MessageView(UUID id, String role, String content, Map<String, Object> structured, UUID runtimeRunId, Instant createdAt) {
        public static MessageView of(AdvisorMessage m) {
            return new MessageView(m.id(), m.role(), m.content(), m.structured(), m.runtimeRunId(), m.createdAt());
        }
    }

    public record CreateProposalRequest(String kind, Map<String, BigDecimal> targetWeights, String counterAsset, String reasoningSummary, UUID runtimeRunId) {}

    public record DecisionRequest(String note) {}

    /** Optional body of {@code POST …/execute}; the {@code Idempotency-Key} header takes precedence (KAN-403). */
    public record ExecuteRequest(String operationId) {}

    public record ProposalView(ActionProposal proposal, List<AuditEvent> audit) {}

    /** The durable event stream of one proposal: outbox events (with delivery state) and dead letters (KAN-403). */
    public record ProposalEvents(UUID proposalId, String status, UUID operationId, List<OutboxEvent> events, List<DeadLetter> deadLetters) {}

    public record ApiError(String code, String message, List<String> details) {
        public ApiError(String code, String message) {
            this(code, message, List.of());
        }
    }

    static ActivityItem activity(SolanaRpcClient.SignatureInfo s, String explorerUrl) {
        return new ActivityItem(s.signature(), s.slot(), s.blockTime(), s.failed(), s.memo(), explorerUrl);
    }
}
