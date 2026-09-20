package io.lifeengine.cryptobot.application.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.solana.ExecutionProperties;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcProperties;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * KAN-571 — the three chaos modes, and the shape of the failure they inject: always a
 * transport-like {@link SolanaRpcException} (cause set), which the execution path treats as
 * <em>uncertain</em>, never as a node-side rejection.
 */
class ChaosSolanaRpcClientTest {

    private static MockWebServer server;
    private static BroadcastChaos chaos;
    private static ChaosSolanaRpcClient client;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        String url = "http://localhost:" + server.getPort();
        chaos = new BroadcastChaos();
        client = new ChaosSolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(url, url, Duration.ofSeconds(2)), new ObjectMapper(),
                CryptobotMetrics.noop(), ExecutionProperties.failClosed(), chaos);
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    @BeforeEach
    void disarm() {
        chaos.disarm();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("uncertain: the transaction IS sent (the node sees it), the answer is lost — one shot, then the client is honest again")
    void uncertainBroadcastsThenDropsTheAnswer() throws Exception {
        chaos.arm(BroadcastChaos.Mode.UNCERTAIN, 1);
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":\"5igNature\",\"id\":1}"));

        StepVerifier.create(client.sendTransaction(SolanaCluster.DEVNET, "AAAA"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(SolanaRpcException.class).hasMessageContaining("chaos uncertain");
                    assertThat(ex.getCause()).isInstanceOf(TimeoutException.class); // transport-shaped ⇒ EV_BROADCAST_UNCERTAIN, never FAILED
                })
                .verify();
        assertThat(server.takeRequest().getBody().readUtf8()).contains("sendTransaction").contains("AAAA");
        assertThat(chaos.state().armed()).isFalse();
        assertThat(chaos.state().faults()).hasSize(1);
        assertThat(chaos.state().faults().get(0).detail()).contains("broadcast for real").contains("5igNature");

        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":\"nextSig\",\"id\":2}"));
        StepVerifier.create(client.sendTransaction(SolanaCluster.DEVNET, "BBBB")).expectNext("nextSig").verifyComplete();
    }

    @Test
    @DisplayName("rpc-down: nothing is sent; status and block height fail too, until disarmed")
    void rpcDownSendsNothingAndBlindsTheReconciler() {
        chaos.arm(BroadcastChaos.Mode.RPC_DOWN, -1);
        int requestsBefore = server.getRequestCount();

        StepVerifier.create(client.sendTransaction(SolanaCluster.DEVNET, "AAAA"))
                .expectErrorSatisfies(ex -> assertThat(ex.getCause()).isInstanceOf(ConnectException.class)).verify();
        StepVerifier.create(client.getSignatureStatus(SolanaCluster.DEVNET, "sig"))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(SolanaRpcException.class).hasMessageContaining("chaos rpc-down")).verify();
        StepVerifier.create(client.getBlockHeight(SolanaCluster.DEVNET))
                .expectError(SolanaRpcException.class).verify();
        assertThat(server.getRequestCount()).as("nothing reached the node").isEqualTo(requestsBefore);
        assertThat(chaos.state().armed()).isTrue();
        assertThat(chaos.state().shotsLeft()).isEqualTo(-1);

        chaos.disarm();
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":1},\"value\":[{\"slot\":1,\"confirmations\":null,\"err\":null,\"confirmationStatus\":\"finalized\"}]},\"id\":1}"));
        StepVerifier.create(client.getSignatureStatus(SolanaCluster.DEVNET, "sig"))
                .assertNext(s -> assertThat(s.confirmationStatus()).isEqualTo("finalized")).verifyComplete();
    }

    @Test
    @DisplayName("confirm-timeout: the broadcast goes through; the confirmation poll fails once")
    void confirmTimeoutOnlyTouchesTheStatusPoll() {
        chaos.arm(BroadcastChaos.Mode.CONFIRM_TIMEOUT, 1);
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":\"5igNature\",\"id\":1}"));

        StepVerifier.create(client.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5igNature").verifyComplete();
        StepVerifier.create(client.getSignatureStatus(SolanaCluster.DEVNET, "5igNature"))
                .expectErrorSatisfies(ex -> assertThat(ex.getCause()).isInstanceOf(TimeoutException.class)).verify();
        assertThat(chaos.state().armed()).isFalse();
        // The other read is untouched.
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":4242,\"id\":2}"));
        StepVerifier.create(client.getBlockHeight(SolanaCluster.DEVNET)).expectNext(4242L).verifyComplete();
    }

    @Test
    void modesParseFromTheirKebabIds() {
        assertThat(BroadcastChaos.Mode.parse("rpc-down")).isEqualTo(BroadcastChaos.Mode.RPC_DOWN);
        assertThat(BroadcastChaos.Mode.parse("CONFIRM_TIMEOUT")).isEqualTo(BroadcastChaos.Mode.CONFIRM_TIMEOUT);
        assertThat(BroadcastChaos.Mode.parse(" uncertain ")).isEqualTo(BroadcastChaos.Mode.UNCERTAIN);
        assertThat(BroadcastChaos.Mode.parse("")).isNull();
        assertThat(BroadcastChaos.Mode.RPC_DOWN.id()).isEqualTo("rpc-down");
    }
}
