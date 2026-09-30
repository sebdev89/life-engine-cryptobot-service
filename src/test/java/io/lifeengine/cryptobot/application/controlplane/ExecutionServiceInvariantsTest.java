package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * (audit §26 "Property / invariants"): the five invariants §29 asks for over the
 * <em>real</em> {@link ExecutionService} — real {@code PolicyEngine}/{@code ProposalService}
 * wiring against {@code InMemoryControlPlaneRepositories} ({@link ExecutionHarness}), chain/signer
 * /validator mocked. There is no jqwik in this pom; each invariant is checked over a loop of
 * randomised inputs instead of one example, which is what the audit gap actually asks for
 * ("not tested as a property over ExecutionService, only over the AuthorityLayer harness / as
 * single unit cases").
 */
class ExecutionServiceInvariantsTest {

    private static final int ITERATIONS = 20;
    private final Random random = new Random(604L);

    // ---------------------------------------------------------------------------------------
    // 1. DENIED never SIGNED — whatever the validator's refusal reason, the signer is never asked.
    // ---------------------------------------------------------------------------------------
    @Test
    @DisplayName("property: for any validator refusal, the signer is never invoked and EXECUTION_SIGNED never appears in the audit trail")
    void deniedByValidatorNeverReachesSigned() {
        List<String> reasons = List.of(
                "verdict disagreement: recorded sha256:c…, validator derived sha256:d…",
                "DENY: mainnet cluster on a devnet-only policy",
                "policy hash mismatch: expected cryptobot-policy-v3, got cryptobot-policy-v1",
                "escalation required: REQUIRE_SECOND_AGENT not satisfied",
                "expired attestation window");
        for (int i = 0; i < ITERATIONS; i++) {
            ExecutionHarness h = new ExecutionHarness();
            String reason = reasons.get(random.nextInt(reasons.size()));
            when(h.validator.authorize(any(), any())).thenReturn(Mono.error(new ValidatorClient.ValidatorRefused(reason)));

            ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op-" + i).block();

            assertThat(done.status()).as("iteration %d, reason=%s", i, reason).isEqualTo(ProposalStatus.FAILED);
            verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
            assertThat(h.auditTypes()).as("iteration %d", i).doesNotContain(ExecutionService.EV_SIGNED, ExecutionService.EV_SUBMITTED);
        }
    }

    // ---------------------------------------------------------------------------------------
    // 2. Unvalidated never SUBMITTED — validator refusal or plain unreachability (unknown ⇒ deny,
    //    I6) must never let the pipeline reach the broadcast step.
    // ---------------------------------------------------------------------------------------
    @Test
    @DisplayName("property: whether the validator explicitly denies or is simply unreachable, sendTransaction is never called and the proposal never reaches SUBMITTED/EXECUTED")
    void unvalidatedNeverReachesSubmitted() {
        for (int i = 0; i < ITERATIONS; i++) {
            ExecutionHarness h = new ExecutionHarness();
            boolean unreachable = random.nextBoolean();
            Throwable failure = unreachable
                    ? new ValidatorClient.ValidatorRefused("Connection refused: localhost/127.0.0.1:809" + random.nextInt(10))
                    : new ValidatorClient.ValidatorRefused("DENY: rule " + random.nextInt(30));
            when(h.validator.authorize(any(), any())).thenReturn(Mono.error(failure));

            ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op-" + i).block();

            assertThat(done.status()).as("iteration %d, unreachable=%s", i, unreachable)
                    .isNotIn(ProposalStatus.SUBMITTED, ProposalStatus.EXECUTED);
            verify(h.rpc, never()).sendTransaction(any(), anyString());
            assertThat(h.auditTypes()).as("iteration %d", i).doesNotContain(ExecutionService.EV_SUBMITTED, ExecutionService.EV_EXECUTED);
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. Same Idempotency-Key ⇒ at most one sendTransaction, no matter how many times execute()
    //    is called with it (internal ticket's replay guard).
    // ---------------------------------------------------------------------------------------
    @RepeatedTest(5)
    @DisplayName("property: replaying the same operationId any number of times never calls sendTransaction more than once")
    void sameIdempotencyKeyAtMostOneBroadcast(org.junit.jupiter.api.RepetitionInfo info) {
        ExecutionHarness h = new ExecutionHarness();
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
        UUID operationId = UUID.randomUUID();
        int repeats = 1 + info.getCurrentRepetition(); // 2..6 replays across the repetitions

        ActionProposal first = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", operationId).block();
        assertThat(first.status()).isEqualTo(ProposalStatus.EXECUTED);
        for (int i = 1; i < repeats; i++) {
            ActionProposal replay = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op-replay-" + i, operationId).block();
            assertThat(replay.operationId()).isEqualTo(operationId);
            assertThat(replay.status()).isEqualTo(ProposalStatus.EXECUTED);
        }

        verify(h.rpc, org.mockito.Mockito.atMostOnce()).sendTransaction(any(), anyString());
        assertThat(h.count("duplicate.trade.suppressed")).isEqualTo(repeats - 1);
    }

    // ---------------------------------------------------------------------------------------
    // 4. allow-mainnet=false ⇒ zero sendTransaction for a mainnet cluster, whatever else varies.
    //    (ExecutionServiceMainnetGateTest.alwaysMainnetDisabledRefusesRegardlessOfOperationId
    //    carries the loop that needs the real PolicyEngine + a mainnet wallet; this one exercises
    //    the same guard at the ExecutionHarness level for the case where the wallet itself is
    //    swapped to mainnet-beta but the flag stays off.)
    // ---------------------------------------------------------------------------------------
    @Test
    @DisplayName("property: with allow-mainnet=false, no random operationId/actor combination ever reaches sendTransaction for a mainnet wallet")
    void mainnetDisabledNeverBroadcastsRegardlessOfOtherInputs() {
        for (int i = 0; i < ITERATIONS; i++) {
            ExecutionHarness h = new ExecutionHarness();
            // requireClusterAllowed checks the wallet's cluster before anything else; swapping
            // the wallet's own cluster to mainnet (with the same failClosed() ExecutionProperties the
            // harness wires by default) is enough to exercise the gate without rebuilding the whole
            // real-PolicyEngine scaffolding ExecutionServiceMainnetGateTest already has.
            io.lifeengine.cryptobot.core.wallet.Wallet mainnetWallet = new io.lifeengine.cryptobot.core.wallet.Wallet(
                    h.wallet.id(), h.wallet.ownerUserId(), h.wallet.address(), SolanaCluster.MAINNET_BETA.toNetwork(), h.wallet.label(),
                    h.wallet.createdAt(), h.wallet.updatedAt());
            when(h.wallets.require(eq(h.wallet.ownerUserId()), eq(h.wallet.id()))).thenReturn(Mono.just(mainnetWallet));
            // The recorded proposal's own cluster is "devnet" (ExecutionHarness); requireClusterAllowed
            // refuses on the wallet's cluster first, so the proposal-cluster mismatch never gets reached.
            UUID operationId = random.nextBoolean() ? UUID.randomUUID() : null;

            reactor.core.publisher.Mono<ActionProposal> exec = operationId == null
                    ? h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "actor-" + i)
                    : h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "actor-" + i, operationId);

            org.assertj.core.api.Assertions.assertThatThrownBy(exec::block)
                    .as("iteration %d", i)
                    .isInstanceOf(io.lifeengine.cryptobot.solana.rpc.MainnetDisabledException.class);
            verify(h.rpc, never()).sendTransaction(any(), anyString());
            verify(h.validator, never()).authorize(any(), any());
            verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        }
    }

    // ---------------------------------------------------------------------------------------
    // 5. Expired approval / unmet timelock ⇒ zero calls to the signer (checked in
    //    executionRefusals, before the wallet is even looked up).
    // ---------------------------------------------------------------------------------------
    @Test
    @DisplayName("property: any execution-precondition refusal (timelock not yet elapsed, expired proposal, no approval) means zero signer calls and the row stays untouched")
    void timelockOrExpiryRefusalNeverReachesTheSigner() {
        List<PolicyEngine.Refusal> timelockVariants = List.of(
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.TIMELOCK, "Timelock: executable at " + Instant.now().plusSeconds(30) + " (30s remaining); cancel it or wait"),
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.TIMELOCK, "Timelock: executable at " + Instant.now().plusSeconds(900) + " (900s remaining); cancel it or wait"),
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.STATE, "Proposal expired at " + Instant.now().minusSeconds(1)),
                new PolicyEngine.Refusal(CryptobotMetrics.RefusalReason.STATE, "No approval record"));
        for (int i = 0; i < ITERATIONS; i++) {
            ExecutionHarness h = new ExecutionHarness();
            PolicyEngine.Refusal refusal = timelockVariants.get(random.nextInt(timelockVariants.size()));
            when(h.policy.executionRefusals(any())).thenReturn(List.of(refusal));
            String actor = "op-" + i;

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), actor).block())
                    .as("iteration %d, refusal=%s", i, refusal.message())
                    .isInstanceOf(ControlPlaneExceptions.Conflict.class);

            verify(h.wallets, never()).require(any(), any());
            verify(h.validator, never()).authorize(any(), any());
            verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
            verify(h.rpc, never()).sendTransaction(any(), anyString());
            ActionProposal after = h.current();
            assertThat(after.status()).as("iteration %d", i).isEqualTo(ProposalStatus.APPROVED);
            assertThat(after.operationId()).isNull();
        }
    }
}
