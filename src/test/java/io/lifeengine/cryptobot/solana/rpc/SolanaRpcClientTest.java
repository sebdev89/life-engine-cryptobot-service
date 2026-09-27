package io.lifeengine.cryptobot.solana.rpc;

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
    void getBlockHeightReadsThePlainNumber() {
        // KAN-403: reconciliation compares this with the signed transaction's lastValidBlockHeight.
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":312345678,\"id\":1}"));
        StepVerifier.create(client.getBlockHeight(SolanaCluster.DEVNET))
                .expectNext(312_345_678L)
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

    @Test
    void getTransactionReadsSlotErrAndMemosFromParsedInstructionsOrLogs() {
        // KAN-394: the anchor's memo read back at `finalized`. jsonParsed shape first…
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"slot\":4242,\"blockTime\":1789700000,\"meta\":{\"err\":null,\"logMessages\":[]},"
                + "\"transaction\":{\"message\":{\"instructions\":[{\"program\":\"spl-memo\",\"programId\":\"MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr\",\"parsed\":\"ir/1 root=sha256:aa n=1 ts=x\"}]}}},\"id\":1}"));
        StepVerifier.create(client.getTransaction(SolanaCluster.DEVNET, "sig"))
                .assertNext(tx -> {
                    assertThat(tx.slot()).isEqualTo(4242L);
                    assertThat(tx.failed()).isFalse();
                    assertThat(tx.blockTime()).isNotNull();
                    assertThat(tx.memos()).containsExactly("ir/1 root=sha256:aa n=1 ts=x");
                })
                .verifyComplete();
        // …then the program's log line as a fallback, with an on-chain error.
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"slot\":7,\"meta\":{\"err\":{\"InstructionError\":[0,\"Custom\"]},\"logMessages\":[\"Program MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr invoke [1]\",\"Program log: Memo (len 5): \\\"hello\\\"\"]},"
                + "\"transaction\":{\"message\":{\"instructions\":[{\"programId\":\"MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr\",\"data\":\"Cn8eVZg\"}]}}},\"id\":2}"));
        StepVerifier.create(client.getTransaction(SolanaCluster.DEVNET, "sig"))
                .assertNext(tx -> {
                    assertThat(tx.failed()).isTrue();
                    assertThat(tx.error()).contains("InstructionError");
                    assertThat(tx.memos()).containsExactly("hello");
                })
                .verifyComplete();
        // Not finalized (or unknown): null result ⇒ empty, not an error.
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":3}"));
        StepVerifier.create(client.getTransaction(SolanaCluster.DEVNET, "sig")).verifyComplete();
    }

    @Test
    void getSignatureStatusCarriesTheSlot() {
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":9},\"value\":[{\"slot\":4242,\"confirmations\":null,\"err\":null,\"confirmationStatus\":\"finalized\"}]},\"id\":1}"));
        StepVerifier.create(client.getSignatureStatus(SolanaCluster.DEVNET, "sig"))
                .assertNext(s -> {
                    assertThat(s.confirmationStatus()).isEqualTo("finalized");
                    assertThat(s.slot()).isEqualTo(4242L);
                    assertThat(s.failed()).isFalse();
                })
                .verifyComplete();
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":9},\"value\":[null]},\"id\":2}"));
        StepVerifier.create(client.getSignatureStatus(SolanaCluster.DEVNET, "sig"))
                .assertNext(s -> {
                    assertThat(s.confirmationStatus()).isNull();
                    assertThat(s.slot()).isNull();
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
