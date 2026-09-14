package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.application.controlplane.AdvisorService;
import io.lifeengine.cryptobot.application.controlplane.PortfolioService;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.application.controlplane.WalletService;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/cryptobot/wallets", produces = "application/json")
public class WalletsController {

    private final WalletService wallets;
    private final PortfolioService portfolio;
    private final AdvisorService advisor;
    private final ProposalService proposals;
    private final SolanaRpcClient rpc;

    public WalletsController(WalletService wallets, PortfolioService portfolio, AdvisorService advisor, ProposalService proposals, SolanaRpcClient rpc) {
        this.wallets = wallets;
        this.portfolio = portfolio;
        this.advisor = advisor;
        this.proposals = proposals;
        this.rpc = rpc;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlPlaneDtos.PortfolioResponse> register(@RequestBody ControlPlaneDtos.RegisterWalletRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.register(p.userId(), Principals.actor(p), req.address(), req.cluster(), req.label())
                .flatMap(w -> portfolio.refresh(w).map(v -> new ControlPlaneDtos.PortfolioResponse(ControlPlaneDtos.WalletView.of(w), v.snapshot(), v.risk(), v.changes())));
    }

    @GetMapping
    public Flux<ControlPlaneDtos.WalletView> list(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return wallets.list(Principals.require(principal).userId()).map(ControlPlaneDtos.WalletView::of);
    }

    @GetMapping("/{walletId}/portfolio")
    public Mono<ControlPlaneDtos.PortfolioResponse> portfolio(@PathVariable UUID walletId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId)
                .flatMap(w -> portfolio.latest(w).map(v -> new ControlPlaneDtos.PortfolioResponse(ControlPlaneDtos.WalletView.of(w), v.snapshot(), v.risk(), v.changes())));
    }

    @PostMapping("/{walletId}/refresh")
    public Mono<ControlPlaneDtos.PortfolioResponse> refresh(@PathVariable UUID walletId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId)
                .flatMap(w -> portfolio.refresh(w).map(v -> new ControlPlaneDtos.PortfolioResponse(ControlPlaneDtos.WalletView.of(w), v.snapshot(), v.risk(), v.changes())));
    }

    @GetMapping("/{walletId}/activity")
    public Mono<List<ControlPlaneDtos.ActivityItem>> activity(@PathVariable UUID walletId, @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId)
                .flatMap(w -> rpc.getSignaturesForAddress(w.cluster(), w.address(), limit)
                        .map(list -> list.stream().map(s -> ControlPlaneDtos.activity(s, w.cluster().explorerTxUrl(s.signature()))).toList()));
    }

    @PostMapping(path = "/{walletId}/ask", consumes = "application/json")
    public Mono<ControlPlaneDtos.AskResponse> ask(@PathVariable UUID walletId, @RequestBody ControlPlaneDtos.AskRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId).flatMap(w -> {
            Mono<io.lifeengine.cryptobot.domain.transactions.ActionProposal> ctx = req.proposalId() == null
                    ? Mono.empty() : proposals.require(p.userId(), req.proposalId());
            return ctx.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                    .flatMap(opt -> advisor.ask(w, Principals.actor(p), req.question(), opt.orElse(null), p.rawToken()))
                    .map(a -> new ControlPlaneDtos.AskResponse(a.answer(), a.runtimeRunId(), a.runtimeBaseUrl(), a.ssePath()));
        });
    }

    @GetMapping("/{walletId}/messages")
    public Flux<ControlPlaneDtos.MessageView> messages(@PathVariable UUID walletId, @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId).flatMapMany(w -> advisor.history(w.id(), limit)).map(ControlPlaneDtos.MessageView::of);
    }

    @PostMapping(path = "/{walletId}/proposals", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlPlaneDtos.ProposalView> propose(@PathVariable UUID walletId, @RequestBody ControlPlaneDtos.CreateProposalRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        if (req.kind() != null && !"REBALANCE".equalsIgnoreCase(req.kind())) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("UNSUPPORTED_KIND", "Only REBALANCE proposals exist in this version"));
        }
        if (req.targetWeights() == null || req.targetWeights().isEmpty()) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("MISSING_TARGETS", "targetWeights is required, e.g. {\"SOL\": 50}"));
        }
        return wallets.require(p.userId(), walletId)
                .flatMap(w -> proposals.createRebalance(w, Principals.actor(p),
                        new io.lifeengine.cryptobot.domain.strategy.RebalanceIntent(req.targetWeights(), req.counterAsset()), req.reasoningSummary(), req.runtimeRunId()))
                .map(pr -> new ControlPlaneDtos.ProposalView(pr, List.of()));
    }

    @GetMapping("/{walletId}/proposals")
    public Flux<io.lifeengine.cryptobot.domain.transactions.ActionProposal> proposals(@PathVariable UUID walletId, @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId).flatMapMany(w -> proposals.listForWallet(w.id(), limit));
    }
}
