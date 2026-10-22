package io.lifeengine.cryptobot.integration.signer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Pilot: what the service sends to /api/signer/sign-swap, and what it never sends. */
class SignerClientSwapTest {

    private MockWebServer server;
    private SignerClient client;
    private final ValidatorClient.Attestation att = new ValidatorClient.Attestation("{\"p\":1}", "sig", "vkey");

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new SignerClient(WebClient.builder(), new SignerProperties(true, "http://localhost:" + server.getPort(), "tok", Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    @Test
    void sendsTheBytesClusterMinimumAndAttestation() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"signedTransactionBase64\":\"AQ==\",\"signer\":\"me\",\"txHash\":\"h\"}"));
        UUID op = UUID.randomUUID();

        StepVerifier.create(client.signSwap(op, "AAEC", SolanaCluster.DEVNET, 1_026_998L, att))
                .assertNext(r -> assertThat(r.signedTransactionBase64()).isEqualTo("AQ=="))
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/signer/sign-swap");
        assertThat(req.getHeader("X-Signer-Token")).isEqualTo("tok");
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertThat(body.path("proposalId").asText()).isEqualTo(op.toString());
        assertThat(body.path("unsignedTransactionBase64").asText()).isEqualTo("AAEC");
        assertThat(body.path("cluster").asText()).isEqualTo("devnet");
        assertThat(body.path("minOut").asLong()).isEqualTo(1_026_998L);
        assertThat(body.path("attestation").path("signature").asText()).isEqualTo("sig");
    }

    @Test
    void neverSendsWithoutAnAttestationAMinimumOrWhenDisabled() {
        UUID op = UUID.randomUUID();
        StepVerifier.create(client.signSwap(op, "AAEC", SolanaCluster.DEVNET, 1L, null)).expectError(SignerClient.SignerRefused.class).verify();
        StepVerifier.create(client.signSwap(op, "AAEC", SolanaCluster.DEVNET, 0L, att)).expectError(SignerClient.SignerRefused.class).verify();
        StepVerifier.create(client.signSwap(op, " ", SolanaCluster.DEVNET, 1L, att)).expectError(SignerClient.SignerRefused.class).verify();
        SignerClient off = new SignerClient(WebClient.builder(), new SignerProperties(false, "http://localhost:" + server.getPort(), "tok", Duration.ofSeconds(2)));
        StepVerifier.create(off.signSwap(op, "AAEC", SolanaCluster.DEVNET, 1L, att)).expectError(SignerClient.SignerRefused.class).verify();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aRefusalIsAnErrorWithItsReason() {
        server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{\"reason\":\"swaps_disabled\"}"));
        StepVerifier.create(client.signSwap(UUID.randomUUID(), "AAEC", SolanaCluster.DEVNET, 1L, att))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(SignerClient.SignerRefused.class).hasMessageContaining("swaps_disabled"))
                .verify();
    }
}
