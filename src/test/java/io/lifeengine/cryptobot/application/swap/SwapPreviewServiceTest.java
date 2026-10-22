package io.lifeengine.cryptobot.application.swap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.jupiter.SwapRejectedException;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * KAN-1249, offline: Jupiter answers from real responses saved under {@code /jupiter}, and two stand-in RPC nodes
 * (devnet, mainnet) record every method they receive. The preview must end in exactly one
 * {@code simulateTransaction} on mainnet, with the signature unverified, and nothing else — no send, no airdrop,
 * no devnet call. That the swap path cannot reach the signer at all is not a runtime fact but a structural one:
 * ModuleBoundariesTest#swapPathCannotSignOrBroadcast fails the build if it depends on the signer client.
 */
class SwapPreviewServiceTest {

    static final String SOL = "So11111111111111111111111111111111111111112";
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String OWNER = "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa";

    private MockWebServer jupiter;
    private MockWebServer mainnet;
    private MockWebServer devnet;
    private final List<String> mainnetMethods = new CopyOnWriteArrayList<>();
    private final List<String> mainnetBodies = new CopyOnWriteArrayList<>();
    private final List<String> jupiterCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        jupiter = new MockWebServer();
        jupiter.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                jupiterCalls.add(r.getMethod() + " " + r.getPath());
                String body = r.getPath().startsWith("/swap/v1/quote") ? fixture("quote-sol-usdc.json")
                        : r.getPath().startsWith("/swap/v1/swap") ? fixture("swap-sol-usdc.json") : null;
                return body == null ? new MockResponse().setResponseCode(404)
                        : new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        mainnet = new MockWebServer();
        mainnet.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String body = r.getBody().readUtf8();
                mainnetBodies.add(body);
                mainnetMethods.add(body.replaceAll(".*\"method\":\"([A-Za-z]+)\".*", "$1"));
                // shape of a real simulateTransaction answer; the values are illustrative (the real one waits for a funded address)
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"context\":{\"slot\":460063400},\"value\":{\"err\":null,"
                                + "\"logs\":[\"Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 invoke [1]\","
                                + "\"Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 success\"],\"unitsConsumed\":182311}}}");
            }
        });
        devnet = new MockWebServer();
        jupiter.start();
        mainnet.start();
        devnet.start();
    }

    @AfterEach
    void stop() throws Exception {
        jupiter.shutdown();
        mainnet.shutdown();
        devnet.shutdown();
    }

    static String fixture(String name) {
        try (var in = SwapPreviewServiceTest.class.getResourceAsStream("/jupiter/" + name)) {
            return new String(in.readAllBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SwapPreviewService service() {
        MarketDataProperties md = new MarketDataProperties("http://localhost:" + jupiter.getPort(), Duration.ofSeconds(3), Map.of(), Map.of(), true);
        ObjectMapper json = new ObjectMapper();
        SolanaRpcClient rpc = new SolanaRpcClient(WebClient.builder(),
                new SolanaRpcProperties("http://localhost:" + devnet.getPort(), "http://localhost:" + mainnet.getPort(), Duration.ofSeconds(3)), json);
        return new SwapPreviewService(new JupiterSwapClient(WebClient.builder(), md, json), rpc);
    }

    @Test
    void aPreviewQuotesBuildsChecksAndOnlySimulates() {
        StepVerifier.create(service().preview(SOL, USDC, 10_000_000L, 50, OWNER))
                .assertNext(p -> {
                    assertThat(p.quote().outAmount()).isEqualTo(1_048_065L);
                    assertThat(p.quote().minOutAmount()).isEqualTo(1_042_825L);
                    assertThat(p.quote().routeLabels()).isNotEmpty();
                    assertThat(p.checked().feePayer()).isEqualTo(OWNER);
                    // Jupiter's own pre-simulation of the unfunded fixture address failed; surfaced, not trusted
                    assertThat(p.swap().jupiterSimulationError()).contains("no record of a prior credit");
                    assertThat(p.simulation().ok()).isTrue();
                    assertThat(p.simulation().unitsConsumed()).isEqualTo(182_311L);
                })
                .verifyComplete();

        assertThat(mainnetMethods).containsExactly("simulateTransaction");
        assertThat(mainnetBodies.get(0)).contains("\"sigVerify\":false").contains("\"replaceRecentBlockhash\":true");
        assertThat(devnet.getRequestCount()).isZero();
        assertThat(jupiterCalls).hasSize(2);
        assertThat(jupiterCalls.get(0)).startsWith("GET /swap/v1/quote?inputMint=" + SOL).contains("amount=10000000").contains("slippageBps=50");
        assertThat(jupiterCalls.get(1)).isEqualTo("POST /swap/v1/swap");
    }

    @Test
    void aTransactionBuiltForSomeoneElseIsNeverSimulated() {
        StepVerifier.create(service().preview(SOL, USDC, 10_000_000L, 50, "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"))
                .expectErrorSatisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("fee_payer_mismatch"))
                .verify();

        assertThat(mainnet.getRequestCount()).isZero();
    }

    @Test
    void aQuoteForAnotherAmountIsRefusedBeforeBuilding() {
        StepVerifier.create(service().preview(SOL, USDC, 20_000_000L, 50, OWNER))
                .expectErrorSatisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("quote_mismatch"))
                .verify();

        assertThat(jupiterCalls).hasSize(1);
        assertThat(mainnet.getRequestCount()).isZero();
    }
}
