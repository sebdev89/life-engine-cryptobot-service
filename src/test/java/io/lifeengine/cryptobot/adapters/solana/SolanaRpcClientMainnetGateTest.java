package io.lifeengine.cryptobot.adapters.solana;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * KAN-493 — the broadcast guard. {@code sendTransaction} refuses a mainnet transaction before any
 * bytes leave the process unless {@code cryptobot.execution.allow-mainnet=true}; the cluster is
 * the one the caller passes with the transaction, never a global.
 */
class SolanaRpcClientMainnetGateTest {

    private static final String SIGNED = "AQID";
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private SolanaRpcClient client(ExecutionProperties execution) {
        String url = "http://localhost:" + server.getPort();
        return new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(url, url, Duration.ofSeconds(2)), new ObjectMapper(), execution);
    }

    @Test
    @DisplayName("mainnet + allow-mainnet=false ⇒ MainnetDisabledException, and not a single HTTP request")
    void mainnetIsRefusedWithoutTouchingTheNetwork() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":\"sig\",\"id\":1}").addHeader("Content-Type", "application/json"));

        StepVerifier.create(client(new ExecutionProperties(false)).sendTransaction(SolanaCluster.MAINNET_BETA, SIGNED))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(MainnetDisabledException.class);
                    assertThat(((MainnetDisabledException) ex).code()).isEqualTo("MAINNET_DISABLED");
                    assertThat(ex.getMessage()).contains("sendTransaction").contains("mainnet-beta").contains("CRYPTOBOT_ALLOW_MAINNET");
                })
                .verify();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("the 3-arg (test) constructor is fail-closed too")
    void defaultConstructorIsFailClosed() {
        String url = "http://localhost:" + server.getPort();
        SolanaRpcClient client = new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(url, url, Duration.ofSeconds(2)), new ObjectMapper());
        StepVerifier.create(client.sendTransaction(SolanaCluster.MAINNET_BETA, SIGNED)).expectError(MainnetDisabledException.class).verify();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("devnet is unaffected by the flag")
    void devnetGoesThrough() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":\"sig-devnet\",\"id\":1}").addHeader("Content-Type", "application/json"));
        StepVerifier.create(client(new ExecutionProperties(false)).sendTransaction(SolanaCluster.DEVNET, SIGNED)).expectNext("sig-devnet").verifyComplete();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("\"method\":\"sendTransaction\"");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("only the explicit flag opens mainnet")
    void explicitFlagOpensMainnet() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":\"sig-mainnet\",\"id\":1}").addHeader("Content-Type", "application/json"));
        StepVerifier.create(client(new ExecutionProperties(true)).sendTransaction(SolanaCluster.MAINNET_BETA, SIGNED)).expectNext("sig-mainnet").verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reads on mainnet stay allowed: the gate is on the write path only")
    void mainnetReadsAreNotGated() {
        server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":42},\"id\":1}").addHeader("Content-Type", "application/json"));
        StepVerifier.create(client(new ExecutionProperties(false)).getBalanceLamports(SolanaCluster.MAINNET_BETA, "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"))
                .expectNext(42L).verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
