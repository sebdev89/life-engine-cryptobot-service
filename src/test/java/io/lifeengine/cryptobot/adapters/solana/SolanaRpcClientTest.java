package io.lifeengine.cryptobot.adapters.solana;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class SolanaRpcClientTest {

    private static MockWebServer server;
    private static SolanaRpcClient client;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        String url = "http://localhost:" + server.getPort();
        client = new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(url, url, Duration.ofSeconds(2)), new ObjectMapper());
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void getBalanceReadsLamports() {
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":2500000000},\"id\":1}"));
        StepVerifier.create(client.getBalanceLamports(SolanaCluster.DEVNET, "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"))
                .expectNext(2_500_000_000L)
                .verifyComplete();
    }

    @Test
    void tokenAccountsParseJsonParsedShapeForBothPrograms() {
        String body = "{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":[{\"pubkey\":\"acct1\",\"account\":{\"data\":{\"parsed\":{\"info\":{"
                + "\"mint\":\"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\",\"tokenAmount\":{\"amount\":\"12500000\",\"decimals\":6,\"uiAmountString\":\"12.5\"}}}}}}]},\"id\":1}";
        server.enqueue(json(body));
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":[]},\"id\":2}"));
        StepVerifier.create(client.getTokenAccountsByOwner(SolanaCluster.DEVNET, "x"))
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).mint()).isEqualTo("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
                    assertThat(list.get(0).uiAmount()).isEqualByComparingTo("12.5");
                    assertThat(list.get(0).decimals()).isEqualTo(6);
                })
                .verifyComplete();
    }

    @Test
    void simulateTransactionReportsErrAndLogs() {
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":{\"err\":{\"InstructionError\":[0,{\"Custom\":1}]},\"logs\":[\"Program 11111111111111111111111111111111 invoke [1]\",\"Transfer: insufficient lamports\"],\"unitsConsumed\":150}},\"id\":1}"));
        StepVerifier.create(client.simulateTransaction(SolanaCluster.DEVNET, "AA==", false))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.error()).contains("InstructionError");
                    assertThat(r.logs()).hasSize(2);
                    assertThat(r.unitsConsumed()).isEqualTo(150L);
                })
                .verifyComplete();
    }

    @Test
    void rpcErrorBecomesSolanaRpcException() {
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid param: WrongSize\"},\"id\":1}"));
        StepVerifier.create(client.getBalanceLamports(SolanaCluster.DEVNET, "bad"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(SolanaRpcException.class);
                    assertThat(((SolanaRpcException) ex).code()).isEqualTo(-32602);
                })
                .verify();
    }

    @Test
    void signaturesForAddressParseBlockTimeAndErr() {
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":[{\"signature\":\"sig1\",\"slot\":10,\"blockTime\":1700000000,\"err\":null,\"memo\":null},{\"signature\":\"sig2\",\"slot\":9,\"blockTime\":null,\"err\":{\"x\":1}}],\"id\":1}"));
        StepVerifier.create(client.getSignaturesForAddress(SolanaCluster.DEVNET, "x", 5))
                .assertNext(list -> {
                    assertThat(list).extracting(SolanaRpcClient.SignatureInfo::signature).containsExactly("sig1", "sig2");
                    assertThat(list.get(0).failed()).isFalse();
                    assertThat(list.get(1).failed()).isTrue();
                    assertThat(list.get(0).blockTime()).isNotNull();
                })
                .verifyComplete();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return List.of();
    }
}
