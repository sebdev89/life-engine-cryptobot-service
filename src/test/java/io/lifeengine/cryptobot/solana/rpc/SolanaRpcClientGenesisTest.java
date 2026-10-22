package io.lifeengine.cryptobot.solana.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * KAN-602: nothing is broadcast through an RPC whose genesis hash is not the expected one for the cluster. The
 * label "devnet" is declarative; the genesis is not — a "devnet" URL that is really mainnet is refused before
 * sendTransaction.
 */
class SolanaRpcClientGenesisTest {

    private MockWebServer server;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> genesisAnswer = new AtomicReference<>(SolanaRpcProperties.DEVNET_GENESIS);

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                String method = body.replaceAll(".*\"method\":\"([A-Za-z]+)\".*", "$1");
                methods.add(method);
                String result = switch (method) {
                    case "getGenesisHash" -> genesisAnswer.get() == null ? null : "\"" + genesisAnswer.get() + "\"";
                    case "sendTransaction" -> "\"5sig\"";
                    default -> "null";
                };
                if (result == null) {
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":1}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private SolanaRpcClient client(String expectedDevnetGenesis) {
        String url = "http://localhost:" + server.getPort();
        return new SolanaRpcClient(WebClient.builder(),
                new SolanaRpcProperties(url, url, Duration.ofSeconds(2), expectedDevnetGenesis, null), new ObjectMapper());
    }

    @Test
    void theRealDevnetGenesisLetsSendThroughAndIsAskedOnce() {
        SolanaRpcClient c = client(null); // null = the real devnet genesis, the production default

        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();
        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();

        assertThat(methods).containsExactly("getGenesisHash", "sendTransaction", "sendTransaction");
    }

    @Test
    void aDevnetUrlThatAnswersTheMainnetGenesisIsRefusedBeforeSending() {
        genesisAnswer.set(SolanaRpcProperties.MAINNET_GENESIS);
        SolanaRpcClient c = client(null);

        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA"))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(SolanaRpcException.class).hasMessageContaining("genesis_mismatch"))
                .verify();
        assertThat(methods).containsExactly("getGenesisHash");
    }

    @Test
    void anRpcFailureOnTheCheckIsNotRememberedTheNextSendAsksAgain() {
        genesisAnswer.set(null); // 503
        SolanaRpcClient c = client(null);
        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectError().verify();

        genesisAnswer.set(SolanaRpcProperties.DEVNET_GENESIS);
        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();
        assertThat(methods).containsExactly("getGenesisHash", "getGenesisHash", "sendTransaction");
    }

    @Test
    void aLocalValidatorCanTurnTheDevnetCheckOffButMainnetsCannotBeTurnedOff() {
        SolanaRpcClient local = client("");
        StepVerifier.create(local.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();
        assertThat(methods).containsExactly("sendTransaction");

        SolanaRpcProperties p = new SolanaRpcProperties("u", "u", Duration.ofSeconds(1), "", "   ");
        assertThat(p.expectedGenesisFor(SolanaCluster.MAINNET_BETA)).isEqualTo(SolanaRpcProperties.MAINNET_GENESIS);
        assertThat(p.expectedGenesisFor(SolanaCluster.DEVNET)).isEmpty();
    }
}
