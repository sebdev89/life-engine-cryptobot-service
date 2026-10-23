package io.lifeengine.cryptobot.solana.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
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
 * KAN-1304 (Alpenglow): what commitment the reads, the simulation and the send preflight ask for. With Alpenglow
 * running on the cluster ({@code getAgGenesisCert} returns a certificate) nothing asks for the deprecated
 * {@code confirmed} — tested against an RPC that rejects it, as a node will once it is removed. Before Alpenglow
 * ({@code null}, or a node without the method) {@code confirmed} stays, because finalized would cost ~12.8 s.
 */
class SolanaRpcClientCommitmentTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String CERT = "{\"block\":{\"blockId\":[1,2,3],\"slot\":459652999},\"signature\":{\"bitmap\":[0],\"signature\":[6,21]}}";

    private MockWebServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** What getAgGenesisCert answers: "cert", "null", "notfound" (-32601) or "down" (HTTP 503). */
    private final AtomicReference<String> cert = new AtomicReference<>("cert");
    /** A node after the removal: any request with commitment "confirmed" is a JSON-RPC error. */
    private final AtomicReference<Boolean> rejectConfirmed = new AtomicReference<>(false);

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                JsonNode body;
                try {
                    body = JSON.readTree(request.getBody().readUtf8());
                } catch (Exception ex) {
                    return new MockResponse().setResponseCode(400);
                }
                requests.add(body);
                String method = body.path("method").asText();
                if (rejectConfirmed.get() && body.path("params").toString().contains("\"confirmed\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid commitment: confirmed\"}}");
                }
                return switch (method) {
                    case "getAgGenesisCert" -> switch (cert.get()) {
                        case "null" -> result("null");
                        case "notfound" -> json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
                        case "down" -> new MockResponse().setResponseCode(503);
                        default -> result(CERT);
                    };
                    case "getBalance" -> result("{\"context\":{\"slot\":1},\"value\":5}");
                    case "getBlockHeight" -> result("42");
                    case "getSignaturesForAddress" -> result("[]");
                    case "getTokenAccountsByOwner" -> result("{\"context\":{\"slot\":1},\"value\":[]}");
                    case "simulateTransaction" -> result("{\"context\":{\"slot\":1},\"value\":{\"err\":null,\"logs\":[],\"unitsConsumed\":1}}");
                    case "sendTransaction" -> result("\"5sig\"");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    static MockResponse result(String value) {
        return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + value + "}");
    }

    SolanaRpcClient client(String devnetCommitment) {
        String url = "http://localhost:" + server.getPort();
        // devnet genesis check off ("") so sendTransaction needs no getGenesisHash stub
        return new SolanaRpcClient(WebClient.builder(),
                new SolanaRpcProperties(url, url, Duration.ofSeconds(2), "", null, devnetCommitment, null), JSON);
    }

    List<String> methods() {
        return requests.stream().map(r -> r.path("method").asText()).toList();
    }

    /** The commitment each non-detection request asked for, in order. */
    List<String> commitmentsAsked() {
        return requests.stream().filter(r -> !r.path("method").asText().equals("getAgGenesisCert")).map(r -> {
            String p = r.path("params").toString();
            return p.contains("\"finalized\"") ? "finalized" : p.contains("\"confirmed\"") ? "confirmed" : "none";
        }).toList();
    }

    void everyCommitmentRead(SolanaRpcClient c) {
        StepVerifier.create(c.getBalanceLamports(SolanaCluster.DEVNET, "addr")).expectNext(5L).verifyComplete();
        StepVerifier.create(c.getBlockHeight(SolanaCluster.DEVNET)).expectNext(42L).verifyComplete();
        StepVerifier.create(c.getSignaturesForAddress(SolanaCluster.DEVNET, "addr", 5)).expectNext(List.of()).verifyComplete();
        StepVerifier.create(c.getTokenAccountsByOwner(SolanaCluster.DEVNET, "addr")).expectNext(List.of()).verifyComplete();
        StepVerifier.create(c.simulateTransaction(SolanaCluster.DEVNET, "AAAA", false)).expectNextMatches(SolanaRpcClient.SimulationResult::ok)
                .verifyComplete();
        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();
    }

    // ---- auto ------------------------------------------------------------------------------------------

    @Test
    void withAlpenglowRunningNothingAsksForConfirmedEvenWhenTheNodeRejectsIt() {
        rejectConfirmed.set(true);
        SolanaRpcClient c = client(null); // null = auto, the production default

        everyCommitmentRead(c);

        // six reads/writes (token accounts asks twice: SPL and Token-2022), all finalized; the cluster asked once
        assertThat(commitmentsAsked()).hasSize(7).containsOnly("finalized");
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(1);
        assertThat(methods().get(0)).isEqualTo("getAgGenesisCert");
    }

    @Test
    void theSendPreflightAsksForTheDetectedCommitment() {
        SolanaRpcClient c = client(null);
        StepVerifier.create(c.sendTransaction(SolanaCluster.DEVNET, "AAAA")).expectNext("5sig").verifyComplete();
        JsonNode send = requests.stream().filter(r -> r.path("method").asText().equals("sendTransaction")).findFirst().orElseThrow();
        assertThat(send.path("params").path(1).path("preflightCommitment").asText()).isEqualTo("finalized");
    }

    @Test
    void beforeAlpenglowItStaysConfirmed() {
        cert.set("null");
        SolanaRpcClient c = client("auto");
        everyCommitmentRead(c);
        assertThat(commitmentsAsked()).containsOnly("confirmed");
    }

    @Test
    void aNodeWithoutTheMethodIsTreatedAsBeforeAlpenglow() {
        cert.set("notfound");
        SolanaRpcClient c = client("auto");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("confirmed").verifyComplete();
        // an answer, not a failure: kept like a null, not asked on every call
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("confirmed").verifyComplete();
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(1);
    }

    @Test
    void aFailedQuestionFallsBackToConfirmedForThisCallAndIsAskedAgain() {
        cert.set("down");
        SolanaRpcClient c = client("auto");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("confirmed").verifyComplete();
        // the node recovers: the next call asks again and sees Alpenglow
        cert.set("cert");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("finalized").verifyComplete();
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(2);
    }

    @Test
    void alpenglowOnceSeenIsRemembered() {
        SolanaRpcClient c = client("auto");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("finalized").verifyComplete();
        cert.set("down");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("finalized").verifyComplete();
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(1);
    }

    @Test
    void beforeAlpenglowTheAnswerIsKeptForAWhileNotAskedOnEveryCall() {
        cert.set("null");
        SolanaRpcClient c = client("auto");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("confirmed").verifyComplete();
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("confirmed").verifyComplete();
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(1);
        assertThat(SolanaRpcClient.RECHECK_BEFORE_ALPENGLOW).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void eachClusterIsAskedOnItsOwn() {
        SolanaRpcClient c = client("auto");
        StepVerifier.create(c.commitmentFor(SolanaCluster.DEVNET)).expectNext("finalized").verifyComplete();
        StepVerifier.create(c.commitmentFor(SolanaCluster.MAINNET_BETA)).expectNext("finalized").verifyComplete();
        assertThat(methods().stream().filter("getAgGenesisCert"::equals).count()).isEqualTo(2);
    }

    // ---- fixed -----------------------------------------------------------------------------------------

    @Test
    void aFixedSettingIsUsedAsIsAndNothingIsAsked() {
        SolanaRpcClient fin = client("finalized");
        everyCommitmentRead(fin);
        assertThat(commitmentsAsked()).containsOnly("finalized");
        requests.clear();
        cert.set("cert");
        SolanaRpcClient conf = client("confirmed");
        everyCommitmentRead(conf);
        assertThat(commitmentsAsked()).containsOnly("confirmed");
        assertThat(methods()).doesNotContain("getAgGenesisCert");
    }

    @Test
    void theSettingIsValidatedAndDefaultsToAuto() {
        String url = "http://localhost:1";
        SolanaRpcProperties p = new SolanaRpcProperties(url, url, null, null, null, null, " Finalized ");
        assertThat(p.commitmentSettingFor(SolanaCluster.DEVNET)).isEqualTo("auto");
        assertThat(p.commitmentSettingFor(SolanaCluster.MAINNET_BETA)).isEqualTo("finalized");
        assertThatThrownBy(() -> new SolanaRpcProperties(url, url, null, null, null, "processed", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auto, confirmed or finalized");
        // the shapes before KAN-1304 keep confirmed, as it was
        assertThat(new SolanaRpcProperties(url, url, null).commitmentSettingFor(SolanaCluster.DEVNET)).isEqualTo("confirmed");
    }
}
