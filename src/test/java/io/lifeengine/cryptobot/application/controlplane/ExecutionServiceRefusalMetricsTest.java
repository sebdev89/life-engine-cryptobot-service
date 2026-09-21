package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.ExecutionProperties;
import io.lifeengine.cryptobot.adapters.solana.MainnetDisabledException;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.domain.oracle.OracleReading;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-582 (HK-5b) — a 409 of {@code /execute} says why in a metric
 * ({@code cryptobot_execution_refused_total{reason}}), and every stage of the demo path records
 * into its own histogram ({@code cryptobot_stage_latency_seconds{stage}}). On
 * {@link ExecutionHarness}: real in-memory store, real signature, mocked network.
 */
class ExecutionServiceRefusalMetricsTest {

    private final ExecutionHarness h = new ExecutionHarness();

    @Test
    @DisplayName("every reason and every stage exist at 0 from construction: the panels never read 'No data'")
    void placeholders() {
        for (CryptobotMetrics.RefusalReason r : CryptobotMetrics.RefusalReason.values()) {
            assertThat(h.count("cryptobot.execution.refused", "reason", r.label())).as(r.label()).isZero();
        }
        for (CryptobotMetrics.Stage s : CryptobotMetrics.Stage.values()) {
            Timer t = h.registry.get("cryptobot.stage.latency").tag("stage", s.label()).timer();
            assertThat(t.count()).as(s.label()).isZero();
            assertThat(t.takeSnapshot().histogramCounts()).as("%s has explicit buckets", s.label()).hasSize(s.buckets().length);
        }
    }

    @Test
    @DisplayName("preconditions failed: counted under the most specific reason (timelock beats state), the policy stage is timed")
    void preconditionRefusedByTimelock() {
        when(h.policy.executionRefusals(any())).thenReturn(List.of(
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.STATE, "Proposal is APPROVED, not APPROVED"),
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.TIMELOCK, "Timelock: executable at later (1800s remaining); cancel it or wait")));

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class)
                .hasMessageContaining("Timelock");

        assertThat(h.count("cryptobot.execution.refused", "reason", "timelock")).isEqualTo(1);
        assertThat(h.count("cryptobot.execution.refused", "reason", "state")).isZero();
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "policy").timer().count()).isEqualTo(1);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.APPROVED);
    }

    @Test
    @DisplayName("a proposal the cooldown rule blocked: reason=cooldown, not policy")
    void preconditionRefusedByCooldown() {
        when(h.policy.executionRefusals(any())).thenReturn(List.of(
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.STATE, "Proposal is BLOCKED_BY_POLICY, not APPROVED"),
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.COOLDOWN, "Policy marked this proposal as not executable")));

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class);

        assertThat(h.count("cryptobot.execution.refused", "reason", "cooldown")).isEqualTo(1);
        assertThat(h.count("cryptobot.execution.refused", "reason", "policy")).isZero();
    }

    @Test
    @DisplayName("in flight under another operation: reason=state")
    void inFlightUnderAnotherOperation() {
        ActionProposal executing = h.approved.withOperation(UUID.randomUUID(), h.now).withStatus(ProposalStatus.EXECUTING, h.now);
        h.repo.commit(ProposalTransition.from(h.approved, executing)).block();

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class)
                .hasMessageContaining("under operation");

        assertThat(h.count("cryptobot.execution.refused", "reason", "state")).isEqualTo(1);
    }

    @Test
    @DisplayName("the fresh oracle reading refuses: reason=oracle (and the KAN-439 counter still moves)")
    void oracleRefused() {
        OracleReading world = Fixtures.oracle("1000", h.now);
        when(h.oracle.read(any())).thenReturn(Mono.just(world));
        when(h.policy.priceViolations(any(), eq(world))).thenReturn(List.of(new PolicyDecision.Violation(PolicyEngine.RULE_PRICE_DRIFT, "drift")));

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class);

        assertThat(h.count("cryptobot.execution.refused", "reason", "oracle")).isEqualTo(1);
        assertThat(h.count("oracle.execution.refused")).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "policy").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("wallet on mainnet with the flag off: reason=mainnet, nothing else counted")
    void mainnetRefused() {
        Wallet mainnet = new Wallet(h.wallet.id(), h.wallet.ownerUserId(), h.wallet.address(), SolanaCluster.MAINNET_BETA, "demo", h.now, h.now);
        when(h.wallets.require(eq(h.wallet.ownerUserId()), eq(h.wallet.id()))).thenReturn(Mono.just(mainnet));
        ExecutionService service = new ExecutionService(h.proposals, h.wallets, h.simulation, h.policy, h.signer, h.validator, h.rpc, h.oracle,
                h.audit, h.metrics, h.executionReceipts, ExecutionProperties.failClosed());

        assertThatThrownBy(() -> service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block())
                .isInstanceOf(MainnetDisabledException.class);

        assertThat(h.count("cryptobot.execution.refused", "reason", "mainnet")).isEqualTo(1);
        assertThat(h.count("cryptobot.execution.refused", "reason", "state")).isZero();
        assertThat(h.count("cryptobot.execution.refused", "reason", "policy")).isZero();
    }

    @Test
    @DisplayName("happy path: simulate, policy, validate, sign, submit and confirm each record exactly one sample")
    void happyPathTimesEveryStage() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        for (CryptobotMetrics.Stage s : List.of(CryptobotMetrics.Stage.POLICY, CryptobotMetrics.Stage.SIMULATE, CryptobotMetrics.Stage.VALIDATE,
                CryptobotMetrics.Stage.SIGN, CryptobotMetrics.Stage.SUBMIT, CryptobotMetrics.Stage.CONFIRM)) {
            assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", s.label()).timer().count()).as(s.label()).isEqualTo(1);
        }
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "reconcile").timer().count()).isZero();
        for (CryptobotMetrics.RefusalReason r : CryptobotMetrics.RefusalReason.values()) {
            assertThat(h.count("cryptobot.execution.refused", "reason", r.label())).as(r.label()).isZero();
        }
    }

    @Test
    @DisplayName("signer refuses: the sign stage is still timed (a failure is a sample too), later stages are not")
    void failedStageIsStillTimed() {
        when(h.signer.sign(eq(h.approved.id()), anyString(), eq(h.wallet.address()), eq(SolanaCluster.DEVNET), any()))
                .thenReturn(Mono.error(new SignerClient.SignerRefused("destination not allowed")));

        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "sign").timer().count()).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "validate").timer().count()).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "submit").timer().count()).isZero();
        assertThat(h.registry.get("cryptobot.stage.latency").tag("stage", "confirm").timer().count()).isZero();
        assertThat(h.count("trade.failed", "stage", "sign", "asset", "SOL")).isEqualTo(1);
    }
}
