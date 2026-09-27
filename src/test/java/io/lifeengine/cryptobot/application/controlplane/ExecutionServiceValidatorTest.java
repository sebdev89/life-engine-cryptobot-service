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
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * KAN-438 — level 5 (paper §20): nothing is signed without the independent validator's
 * attestation, and the validator's refusal, disagreement or silence all fail closed before the
 * signer is even asked.
 */
class ExecutionServiceValidatorTest {

    private final ExecutionHarness h = new ExecutionHarness();

    @Test
    @DisplayName("happy path: validator attests, the attestation goes to the signer, EXECUTION_VALIDATED precedes EXECUTION_SIGNED")
    void attestationTravelsToTheSigner() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        ArgumentCaptor<ValidatorClient.Attestation> att = ArgumentCaptor.forClass(ValidatorClient.Attestation.class);
        verify(h.signer).sign(eq(h.approved.id()), anyString(), eq(h.wallet.address()), eq(SolanaCluster.DEVNET), att.capture());
        assertThat(att.getValue().signature()).isEqualTo("sig");
        assertThat(att.getValue().validator()).isEqualTo("validator-key");
        List<String> audit = h.auditTypes();
        assertThat(audit).containsSubsequence(ExecutionService.EV_STARTED, ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED, ExecutionService.EV_SUBMITTED);
        assertThat(InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> ExecutionService.EV_VALIDATED.equals(e.eventType())).findFirst().orElseThrow().payload())
                .containsEntry("validator", "validator-key").containsEntry("decision", "ESCALATE").containsEntry("verdictHash", "sha256:" + "c".repeat(64));
        assertThat(h.count("validator.attestations", "result", "issued")).isEqualTo(1);
    }

    @Test
    @DisplayName("validator says DENY / disagrees: FAILED at stage=validate, the signer is never asked")
    void validatorRefusalFailsClosedBeforeSigning() {
        when(h.validator.authorize(any(), any()))
                .thenReturn(Mono.error(new ValidatorClient.ValidatorRefused("verdict disagreement: recorded sha256:c…, validator derived sha256:d…")));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.execution().error()).contains("Validator refused").contains("disagreement");
        verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        verify(h.rpc, never()).sendTransaction(any(), anyString());
        assertThat(h.count("trade.failed", "stage", "validate", "asset", "SOL")).isEqualTo(1);
        assertThat(h.count("validator.attestations", "result", "refused")).isEqualTo(1);
        assertThat(h.auditTypes()).contains(ExecutionService.EV_FAILED).doesNotContain(ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED);
    }

    @Test
    @DisplayName("validator unreachable: Unknown ⇒ Deny — FAILED at stage=validate, nothing signed")
    void validatorSilenceIsDeny() {
        when(h.validator.authorize(any(), any())).thenReturn(Mono.error(new ValidatorClient.ValidatorRefused("Connection refused: localhost/127.0.0.1:8097")));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        assertThat(h.count("trade.failed", "stage", "validate", "asset", "SOL")).isEqualTo(1);
    }

    @Test
    @DisplayName("signer refuses the attestation (e.g. attestation_message_mismatch): FAILED at stage=sign")
    void signerRefusingTheAttestationIsASignFailure() {
        when(h.signer.sign(eq(h.approved.id()), anyString(), eq(h.wallet.address()), eq(SolanaCluster.DEVNET), any()))
                .thenReturn(Mono.error(new SignerClient.SignerRefused("HTTP 403 {\"reason\":\"attestation_message_mismatch\"}")));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(done.execution().error()).contains("attestation_message_mismatch");
        assertThat(h.count("trade.failed", "stage", "sign", "asset", "SOL")).isEqualTo(1);
        assertThat(h.count("validator.attestations", "result", "issued")).isEqualTo(1);
    }
}
