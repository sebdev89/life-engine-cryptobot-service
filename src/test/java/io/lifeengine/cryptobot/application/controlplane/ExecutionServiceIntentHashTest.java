package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.core.intent.IntentHash;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ApprovalRecord;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * (CB-03/09): the intent hash presented as the idempotency key is persisted with the row
 * (not only folded into the operationId), the EXECUTION_STARTED event records it, and the
 * EXECUTION receipt names the advisor run the proposal came from.
 */
class ExecutionServiceIntentHashTest {

    private final ExecutionHarness h = new ExecutionHarness();

    @Test
    @DisplayName("an intent-hash key is persisted on the row with EXECUTING and recorded in EXECUTION_STARTED; the derived operationId is the row's")
    void intentHashIsPersistedWithTheOperation() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
        IntentHash hash = IntentHash.of("canonical intent bytes".getBytes(StandardCharsets.UTF_8));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", hash.toOperationId(), hash.value()).block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(done.operationId()).isEqualTo(hash.toOperationId());
        assertThat(done.intentHash()).isEqualTo(hash.value());
        assertThat(h.current().intentHash()).as("persisted, not only on the returned aggregate").isEqualTo(hash.value());
        assertThat(h.current().execution().signature()).isEqualTo(sig);
        var started = InMemoryControlPlaneRepositories.AUDIT.stream()
                .filter(e -> h.approved.id().equals(e.proposalId()) && ExecutionService.EV_STARTED.equals(e.eventType())).findFirst().orElseThrow();
        assertThat(started.payload()).containsEntry("operationId", hash.toOperationId().toString()).containsEntry("intentHash", hash.value());

        // Replaying the same intent hash ⇒ same operation, no second transaction, hash unchanged.
        ActionProposal again = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", hash.toOperationId(), hash.value()).block();
        assertThat(again.intentHash()).isEqualTo(hash.value());
        assertThat(h.count("duplicate.trade.suppressed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a plain UUID key leaves intent_hash null and EXECUTION_STARTED without it")
    void uuidKeyHasNoIntentHash() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();

        assertThat(done.intentHash()).isNull();
        var started = InMemoryControlPlaneRepositories.AUDIT.stream()
                .filter(e -> h.approved.id().equals(e.proposalId()) && ExecutionService.EV_STARTED.equals(e.eventType())).findFirst().orElseThrow();
        assertThat(started.payload()).containsKey("operationId").doesNotContainKey("intentHash");
    }

    @Test
    @DisplayName("the EXECUTION receipt carries runtime.runId when the proposal came out of an advisor run")
    void executionReceiptNamesTheAdvisorRun() {
        UUID runId = UUID.randomUUID();
        ActionProposal advised = h.repo.insert(new ActionProposal(UUID.randomUUID(), h.wallet.id(), h.wallet.ownerUserId(), h.wallet.address(), "devnet",
                ProposalStatus.APPROVED, "REBALANCE", "t", null, "op", null, h.approved.plan(), null, null, h.approved.policy(), null, h.tx,
                new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", h.now, null), null, runId, null,
                h.now.plusSeconds(600), h.now, h.now, null, 0)).block();
        when(h.signer.sign(eq(advised.id()), anyString(), eq(h.wallet.address()), eq(SolanaCluster.DEVNET), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    byte[] message = java.util.Base64.getDecoder().decode(inv.<String>getArgument(1));
                    byte[] s = h.keypair.sign(message);
                    byte[] wire = new byte[1 + 64 + message.length];
                    wire[0] = 1;
                    System.arraycopy(s, 0, wire, 1, 64);
                    System.arraycopy(message, 0, wire, 65, message.length);
                    return Mono.just(new io.lifeengine.cryptobot.integration.signer.SignerClient.SignResponse(
                            java.util.Base64.getEncoder().encodeToString(wire), h.keypair.publicKeyBase58(), null));
                });
        String sig = h.signatureOf(h.tx);
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), advised.id(), "op", UUID.randomUUID()).block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        IntelligenceReceipt receipt = InMemoryControlPlaneRepositories.receiptsOf(advised.id()).get(0);
        assertThat(receipt.body().runtime()).isNotNull();
        assertThat(receipt.body().runtime().runId()).isEqualTo(runId.toString());
        // The one without a run keeps a null runId (the ref still names the build).
        assertThat(h.receipts()).isEmpty();
    }

    @Test
    @DisplayName("JSON documents written before V11 (no intentHash) still deserialise; new ones round-trip the hash")
    void documentCompatibility() throws Exception {
        // The same defaults as Spring Boot's ObjectMapper (JsonDocs uses that one): unknown properties are ignored.
        ObjectMapper json = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        String hash = IntentHash.of("x".getBytes(StandardCharsets.UTF_8)).value();
        ActionProposal withHash = h.approved.withOperation(UUID.randomUUID(), hash, h.now);
        String doc = json.writeValueAsString(withHash);
        assertThat(doc).contains("\"intentHash\":\"" + hash + "\"");
        assertThat(json.readValue(doc, ActionProposal.class).intentHash()).isEqualTo(hash);

        String legacy = doc.replace(",\"intentHash\":\"" + hash + "\"", "");
        assertThat(legacy).doesNotContain("intentHash");
        ActionProposal read = json.readValue(legacy, ActionProposal.class);
        assertThat(read.intentHash()).isNull();
        assertThat(read.operationId()).isEqualTo(withHash.operationId());
    }
}
