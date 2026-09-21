package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.adapters.solana.ExecutionProperties;
import io.lifeengine.cryptobot.adapters.solana.MainnetDisabledException;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.receipt.ReceiptSigningKey;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ApprovalRecord;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.lifeengine.cryptobot.domain.transactions.SimulationOutcome;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * KAN-493 — mainnet is fail-closed at the execution service. Unlike {@link ExecutionHarness},
 * this wiring uses the <em>real</em> {@link PolicyEngine} ({@code executionPreconditions} is not
 * stubbed) and a proposal that passed policy for real, so the only thing standing between an
 * APPROVED mainnet proposal and the signer is the {@code cryptobot.execution.allow-mainnet} flag.
 */
class ExecutionServiceMainnetGateTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL", "USDC"));
    private final ActionProposalRepository repo = InMemoryControlPlaneRepositories.proposals();
    private final ProposalService proposals = mock(ProposalService.class);
    private final WalletService wallets = mock(WalletService.class);
    private final SimulationService simulation = mock(SimulationService.class);
    private final SignerClient signer = mock(SignerClient.class);
    private final ValidatorClient validator = mock(ValidatorClient.class);
    private final SolanaRpcClient rpc = mock(SolanaRpcClient.class);
    // KAN-439: the world agrees with the plan (SOL $100); the only gate under test is the mainnet flag.
    private final PriceOracleService oracle = mock(PriceOracleService.class);
    private final AuditService audit = new AuditService(InMemoryControlPlaneRepositories.audit());
    private final ExecutionReceipts executionReceipts = new ExecutionReceipts(
            new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("test-key"), metrics),
            new Receipts(new TenantSalts("test-salt-secret".getBytes(StandardCharsets.UTF_8)), null, new com.fasterxml.jackson.databind.ObjectMapper()),
            metrics);
    private final RebalancePlanner planner = new RebalancePlanner(new TokenRegistry(new MarketDataProperties(null, null, null, null, false)));

    ExecutionServiceMainnetGateTest() {
        InMemoryControlPlaneRepositories.reset();
        when(proposals.require(any(), any())).thenAnswer(inv -> repo.findByIdAndOwner(inv.getArgument(1), inv.getArgument(0))
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Proposal"))));
        when(proposals.commit(any())).thenAnswer(inv -> repo.commit(inv.<ProposalTransition>getArgument(0)));
        when(oracle.read(any())).thenReturn(Mono.just(Fixtures.oracle("100", NOW)));
    }

    /** The real engine, with the cluster the operator claims execution is allowed on. */
    private static PolicyEngine engine(String executionCluster) {
        PolicyProperties props = new PolicyProperties(true, executionCluster, new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC", "USDT"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        AuthorizationProperties auth = new AuthorizationProperties("test-policy-v1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2500"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        return new PolicyEngine(props, auth, new TimelockProperties(Duration.ZERO, Duration.ZERO, Duration.ofMinutes(30)), CLOCK);
    }

    private ExecutionService service(PolicyEngine policy, boolean allowMainnet) {
        return new ExecutionService(proposals, wallets, simulation, policy, signer, validator, rpc, oracle, audit, metrics, executionReceipts,
                new ExecutionProperties(allowMainnet));
    }

    /**
     * A proposal for {@code wallet} that went through the real {@link PolicyEngine#evaluate} and was
     * approved with an already-elapsed timelock: everything {@code executionPreconditions} checks
     * is satisfied for real.
     */
    private ActionProposal approvedFor(Wallet wallet, PolicyEngine policy) {
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", new BigDecimal("50")), "USDC"));
        long lamports = plan.legs().get(0).amount().multiply(new BigDecimal("1000000000")).longValue();
        byte[] message = "fake-solana-message".getBytes(StandardCharsets.UTF_8);
        String b64 = java.util.Base64.getEncoder().encodeToString(message);
        PreparedTransaction tx = new PreparedTransaction(wallet.cluster().id(), wallet.address(), Fixtures.VAULT, lamports, "bh", 1L, b64, b64, "transfer");
        SimulationOutcome sim = new SimulationOutcome(null, new SimulationOutcome.Onchain(true, null, 150L, List.of(), wallet.cluster().id()));
        ActionProposal simulated = new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(),
                ProposalStatus.SIMULATED, "REBALANCE", "t", null, "tester", new RebalanceIntent(Map.of("SOL", new BigDecimal("50")), "USDC"), plan,
                null, null, null, sim, tx, null, null, null, null, NOW.plusSeconds(1800), NOW, NOW, null, 0);
        PolicyDecision decision = policy.evaluate(simulated, wallet, PolicyEngine.WalletState.fresh(NOW, Fixtures.oracle("100", NOW)), // KAN-572: the signer misconfigured to the same cluster as the wallet, so only the mainnet flag remains
                Optional.of(new io.lifeengine.cryptobot.integration.signer.SignerClient.Identity(wallet.address(), wallet.cluster().id(), 2_000_000_000L, List.of(Fixtures.VAULT))));
        ApprovalRecord approval = new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW.minusSeconds(60), null, NOW.minusSeconds(60));
        ActionProposal approved = new ActionProposal(simulated.id(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(),
                ProposalStatus.APPROVED, "REBALANCE", "t", null, "tester", simulated.intent(), plan, null, null, decision, sim, tx, approval, null, null, null,
                NOW.plusSeconds(1800), NOW, NOW, null, 0);
        when(wallets.require(eq(wallet.ownerUserId()), eq(wallet.id()))).thenReturn(Mono.just(wallet));
        when(simulation.prepareTransfer(eq(wallet), any(Long.class))).thenReturn(Mono.just(tx));
        when(rpc.simulateTransaction(any(), anyString(), eq(false))).thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));
        return repo.insert(approved).block();
    }

    @Test
    @DisplayName("AC1: mainnet wallet, execution-cluster misconfigured to mainnet-beta, flag off ⇒ 409 MAINNET_DISABLED before validator, signer or RPC")
    void mainnetWalletIsRefusedBeforeAnythingIsSignedOrSent() {
        // Belt removed on purpose: the one-line YAML change that used to be the only brake.
        PolicyEngine policy = engine("mainnet-beta");
        Wallet wallet = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        ActionProposal approved = approvedFor(wallet, policy);
        assertThat(approved.policy().executable()).as("the real engine let this through: only the flag remains").isTrue();
        assertThat(policy.executionPreconditions(approved)).as("real preconditions pass").isEmpty();

        StepVerifier.create(service(policy, false).execute(wallet.ownerUserId(), approved.id(), "op"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(MainnetDisabledException.class);
                    assertThat(((MainnetDisabledException) ex).code()).isEqualTo("MAINNET_DISABLED");
                    assertThat(ex.getMessage()).contains("mainnet-beta").contains("CRYPTOBOT_ALLOW_MAINNET");
                })
                .verify();

        verify(validator, never()).authorize(any(), any());
        verify(signer, never()).sign(any(), anyString(), anyString(), any(), any());
        verify(rpc, never()).sendTransaction(any(), anyString());
        // Nothing moved: no EXECUTING transition, no audit row, no outbox event.
        ActionProposal after = repo.findByIdAndOwner(approved.id(), wallet.ownerUserId()).block();
        assertThat(after.status()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(after.operationId()).isNull();
        assertThat(InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> approved.id().equals(e.proposalId()))).isEmpty();
        assertThat(InMemoryControlPlaneRepositories.outboxOf(approved.id())).isEmpty();
    }

    @Test
    @DisplayName("belt: with the default execution-cluster=devnet the real engine already marks a mainnet proposal not executable (409, not MAINNET_DISABLED)")
    void defaultConfigurationStopsMainnetEvenEarlier() {
        PolicyEngine policy = engine("devnet");
        Wallet wallet = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        ActionProposal approved = approvedFor(wallet, policy);
        assertThat(approved.policy().executable()).isFalse();
        assertThat(approved.policy().executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_CLUSTER);

        StepVerifier.create(service(policy, false).execute(wallet.ownerUserId(), approved.id(), "op"))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("not executable"))
                .verify();
        verify(validator, never()).authorize(any(), any());
        verify(rpc, never()).sendTransaction(any(), anyString());
    }

    @Test
    @DisplayName("the flag is what the gate reads: allow-mainnet=true lets the pipeline reach the validator (which then decides)")
    void explicitFlagOpensTheGateAndNothingElseDoes() {
        PolicyEngine policy = engine("mainnet-beta");
        Wallet wallet = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        ActionProposal approved = approvedFor(wallet, policy);
        when(validator.authorize(any(), any())).thenReturn(Mono.error(new ValidatorClient.ValidatorRefused("test: stop here")));

        ActionProposal done = service(policy, true).execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        verify(validator).authorize(any(), any());
        verify(signer, never()).sign(any(), anyString(), anyString(), any(), any());
        verify(rpc, never()).sendTransaction(any(), anyString());
    }

    @Test
    @DisplayName("devnet is untouched by the gate: same real engine, devnet wallet, flag off ⇒ the pipeline runs")
    void devnetIsNotAffected() {
        PolicyEngine policy = engine("devnet");
        Wallet wallet = Fixtures.wallet(SolanaCluster.DEVNET);
        ActionProposal approved = approvedFor(wallet, policy);
        assertThat(policy.executionPreconditions(approved)).isEmpty();
        when(validator.authorize(any(), any())).thenReturn(Mono.error(new ValidatorClient.ValidatorRefused("test: stop here")));

        ActionProposal done = service(policy, false).execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        verify(validator).authorize(any(), any());
    }

    @Test
    @DisplayName("if the RPC guard is what fires, the row is FAILED (nothing was sent) — not left in flight for the reconciler")
    void rpcGuardRefusalIsACertainFailure() {
        ExecutionHarness h = new ExecutionHarness();
        h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(new MainnetDisabledException("sendTransaction", SolanaCluster.MAINNET_BETA)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.execution().error()).contains("MAINNET").contains("fail-closed");
        assertThat(h.auditTypes()).contains(ExecutionService.EV_SIGNED, ExecutionService.EV_FAILED)
                .doesNotContain(ExecutionService.EV_SUBMITTED, ExecutionService.EV_BROADCAST_UNCERTAIN);
        verify(h.rpc, never()).getSignatureStatus(any(), anyString());
    }
}
