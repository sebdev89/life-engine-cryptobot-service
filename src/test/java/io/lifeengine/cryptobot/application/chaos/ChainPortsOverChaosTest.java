package io.lifeengine.cryptobot.application.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.Network;
import io.lifeengine.cryptobot.core.execution.NetworkPolicy;
import io.lifeengine.cryptobot.core.ports.ChainExecutionPort;
import io.lifeengine.cryptobot.core.ports.ChainObservationPort;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.solana.rpc.ExecutionProperties;
import io.lifeengine.cryptobot.solana.rpc.SolanaChainAdapter;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
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
 * KAN-596 AC: "chaos modes del demo (rpc-down/uncertain/confirm-timeout) siguen funcionando vía
 * decorator". The chain ports ({@link SolanaChainAdapter}) are wired over whichever
 * {@code SolanaRpcClient} bean Spring resolves; with chaos enabled that is the {@code @Primary}
 * {@link ChaosSolanaRpcClient}. These tests drive the three demo modes <em>through the ports</em>
 * and assert the same transport-shaped failures the execution path relies on.
 */
class ChainPortsOverChaosTest {

    private static MockWebServer server;
    private static BroadcastChaos chaos;
    private static SolanaChainAdapter ports;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        String url = "http://localhost:" + server.getPort();
        chaos = new BroadcastChaos();
        ChaosSolanaRpcClient decorated = new ChaosSolanaRpcClient(WebClient.builder(),
                new SolanaRpcProperties(url, url, Duration.ofSeconds(2)), new ObjectMapper(),
                CryptobotMetrics.noop(), ExecutionProperties.failClosed(), chaos);
        ports = new SolanaChainAdapter(decorated, NetworkPolicy.failClosed());
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
    @DisplayName("uncertain via ChainExecutionPort.submit: broadcast happens, the answer is lost (timeout cause)")
    void uncertainThroughThePort() throws Exception {
        chaos.arm(BroadcastChaos.Mode.UNCERTAIN, 1);
        int before = server.getRequestCount();
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":\"5igNature\",\"id\":1}"));

        ChainExecutionPort exec = ports;
        StepVerifier.create(exec.submit(Network.DEVNET, "AAAA"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(SolanaRpcException.class).hasMessageContaining("chaos uncertain");
                    assertThat(ex.getCause()).isInstanceOf(TimeoutException.class);
                })
                .verify();
        assertThat(server.getRequestCount()).isEqualTo(before + 1);
        assertThat(chaos.state().faults()).isNotEmpty();
    }

    @Test
    @DisplayName("rpc-down via the ports: submit, status and blockHeight all fail with a connect cause, nothing sent")
    void rpcDownThroughThePorts() {
        chaos.arm(BroadcastChaos.Mode.RPC_DOWN, -1);
        int before = server.getRequestCount();

        ChainExecutionPort exec = ports;
        ChainObservationPort obs = ports;
        StepVerifier.create(exec.submit(Network.DEVNET, "AAAA"))
                .expectErrorSatisfies(ex -> assertThat(ex.getCause()).isInstanceOf(ConnectException.class)).verify();
        StepVerifier.create(obs.status(Network.DEVNET, "sig"))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(SolanaRpcException.class).hasMessageContaining("chaos rpc-down")).verify();
        StepVerifier.create(obs.blockHeight(Network.DEVNET))
                .expectErrorSatisfies(ex -> assertThat(ex.getCause()).isInstanceOf(ConnectException.class)).verify();
        assertThat(server.getRequestCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("confirm-timeout via the ports: submit succeeds, the status poll fails with a timeout cause")
    void confirmTimeoutThroughThePorts() {
        chaos.arm(BroadcastChaos.Mode.CONFIRM_TIMEOUT, -1);
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"result\":\"okSig\",\"id\":1}"));

        ChainExecutionPort exec = ports;
        ChainObservationPort obs = ports;
        StepVerifier.create(exec.submit(Network.DEVNET, "AAAA"))
                .assertNext(s -> assertThat(s.signature()).isEqualTo("okSig"))
                .verifyComplete();
        StepVerifier.create(obs.status(Network.DEVNET, "okSig"))
                .expectErrorSatisfies(ex -> assertThat(ex.getCause()).isInstanceOf(TimeoutException.class)).verify();
    }
}
