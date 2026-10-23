package io.lifeengine.cryptobot.application.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.application.controlplane.AuthorizationProperties;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.controlplane.PolicyProperties;
import io.lifeengine.cryptobot.application.controlplane.TimelockProperties;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.signer.SignerProperties;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorProperties;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.testsupport.InMemorySwapOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * KAN-1249 pilot (b2b): executing an approved swap against fakes — Jupiter from the real legacy fixtures (fee payer
 * rewritten to a test key), a validator that attests what it is asked, a signer that really signs with that key, and
 * an RPC whose broadcast and confirmation each test chooses. Once, bound to the approval, never retried.
 */
class SwapExecutorTest {

    static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-00000000c003");
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    static final class FixedClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final SolanaKeypair key = SolanaKeypair.generate();
    final String owner = key.publicKeyBase58();
    MockWebServer jupiter;
    MockWebServer rpc;
    MockWebServer validator;
    MockWebServer signer;
    InMemorySwapOrderRepository repo;
    SimpleMeterRegistry meters;
    FixedClock clock;

    /** What the fake RPC answers: sendTransaction ("ok", "reject" = JSON-RPC error, "down" = HTTP 503) and the status. */
    final AtomicReference<String> send = new AtomicReference<>("ok");
    final AtomicReference<String> status = new AtomicReference<>("confirmed");
    final AtomicReference<String> signerCluster = new AtomicReference<>("devnet");
    final AtomicReference<String> validatorDecision = new AtomicReference<>("ALLOW");
    final AtomicReference<Boolean> signerTampers = new AtomicReference<>(false);
    final List<String> rpcMethods = new CopyOnWriteArrayList<>();
    final List<String> simulated = new CopyOnWriteArrayList<>();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> signedRequests = new CopyOnWriteArrayList<>();
    final AtomicReference<Boolean> simulationOk = new AtomicReference<>(true);
    final AtomicReference<Runnable> onSign = new AtomicReference<>(() -> { });
    /** The fixture's blockhash is valid up to 438 245 737. */
    final java.util.concurrent.atomic.AtomicLong blockHeight = new java.util.concurrent.atomic.AtomicLong(438_245_000L);
    /** What the fake Jupiter quotes: the fixture as is, or with a field replaced. */
    final AtomicReference<java.util.function.UnaryOperator<ObjectNode>> quoteEdit = new AtomicReference<>(q -> q);
    final AtomicInteger signs = new AtomicInteger();
    final AtomicReference<JsonNode> lastValidatorBody = new AtomicReference<>();
    final AtomicReference<JsonNode> lastSignBody = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        jupiter = new MockWebServer();
        jupiter.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath() == null ? "" : r.getPath();
                if (path.startsWith("/swap/v1/quote")) {
                    return json(quoteEdit.get().apply((ObjectNode) read(SwapOrderServiceTest.fixture("quote-sol-usdc.json"))).toString());
                }
                if (path.startsWith("/swap/v1/swap")) {
                    return json(swapFixtureFor(owner));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        rpc = new MockWebServer();
        rpc.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                JsonNode body = read(r.getBody().readUtf8());
                String method = body.path("method").asText();
                rpcMethods.add(method);
                return switch (method) {
                    case "simulateTransaction" -> {
                        simulated.add(body.path("params").path(0).asText());
                        yield result("{\"context\":{\"slot\":1},\"value\":{\"err\":" + (simulationOk.get() ? "null" : "{\"InstructionError\":[3,{\"Custom\":6001}]}")
                                + ",\"logs\":[],\"unitsConsumed\":61000}}");
                    }
                    case "getGenesisHash" -> result("\"" + SolanaRpcProperties.DEVNET_GENESIS + "\"");
                    case "getBlockHeight" -> result(Long.toString(blockHeight.get()));
                    case "sendTransaction" -> switch (send.get()) {
                        case "reject" -> json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32002,\"message\":\"Transaction simulation failed\"}}");
                        case "down" -> new MockResponse().setResponseCode(503);
                        case "other" -> result("\"5otherSignatureThatIsNotOurs\"");
                        default -> {
                            sent.add(body.path("params").path(0).asText());
                            yield result("\"" + signatureOf(body.path("params").path(0).asText()) + "\"");
                        }
                    };
                    case "getSignatureStatuses" -> switch (status.get()) {
                        case "unknown" -> result("{\"context\":{\"slot\":2},\"value\":[null]}");
                        case "failed" -> result("{\"context\":{\"slot\":2},\"value\":[{\"slot\":2,\"confirmations\":0,\"err\":{\"InstructionError\":[3,"
                                + "{\"Custom\":6001}]},\"confirmationStatus\":\"confirmed\"}]}");
                        default -> result("{\"context\":{\"slot\":2},\"value\":[{\"slot\":2,\"confirmations\":1,\"err\":null,\"confirmationStatus\":\""
                                + status.get() + "\"}]}");
                    };
                    default -> new MockResponse().setResponseCode(400);
                };
            }
        });
        validator = new MockWebServer();
        validator.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                JsonNode body = read(r.getBody().readUtf8());
                lastValidatorBody.set(body);
                return json(attestationEchoing(body));
            }
        });
        signer = new MockWebServer();
        signer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath() == null ? "" : r.getPath();
                if (path.equals("/api/signer/identity")) {
                    return json("{\"publicKey\":\"" + owner + "\",\"cluster\":\"" + signerCluster.get() + "\",\"maxLamports\":1,\"allowedDestinations\":[]}");
                }
                if (path.equals("/api/signer/sign-swap")) {
                    signs.incrementAndGet();
                    onSign.get().run();
                    JsonNode body = read(r.getBody().readUtf8());
                    lastSignBody.set(body);
                    signedRequests.add(body.path("unsignedTransactionBase64").asText());
                    byte[] wire = Base64.getDecoder().decode(body.path("unsignedTransactionBase64").asText());
                    byte[] message = Arrays.copyOfRange(wire, 65, wire.length);
                    byte[] sig = key.sign(message);
                    if (signerTampers.get()) {
                        message[message.length - 1] ^= 1;
                    }
                    byte[] signed = new byte[wire.length];
                    signed[0] = 1;
                    System.arraycopy(sig, 0, signed, 1, 64);
                    System.arraycopy(message, 0, signed, 65, message.length);
                    return json("{\"signedTransactionBase64\":\"" + Base64.getEncoder().encodeToString(signed) + "\",\"signer\":\"" + owner
                            + "\",\"txHash\":\"x\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        jupiter.start();
        rpc.start();
        validator.start();
        signer.start();
        repo = new InMemorySwapOrderRepository();
        meters = new SimpleMeterRegistry();
        clock = new FixedClock();
    }

    @AfterEach
    void stop() throws Exception {
        jupiter.close();
        rpc.close();
        validator.close();
        signer.close();
    }

    // ---- fakes ----------------------------------------------------------------------------------------

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    static MockResponse result(String value) {
        return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + value + "}");
    }

    static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The legacy fixture with its fee payer (the first account key) replaced by {@code payer}. */
    static String swapFixtureFor(String payer) {
        try {
            ObjectNode n = (ObjectNode) JSON.readTree(SwapOrderServiceTest.fixture("swap-sol-usdc.json"));
            byte[] tx = Base64.getDecoder().decode(n.path("swapTransaction").asText());
            // 1 signature (1 + 64 bytes), the 3-byte header, a 1-byte key count (< 128), then the keys
            assertThat(tx[0]).isEqualTo((byte) 1);
            assertThat(tx[1 + 64 + 3] & 0x80).isZero();
            System.arraycopy(io.lifeengine.cryptobot.solana.rpc.Base58.decode(payer), 0, tx, 1 + 64 + 3 + 1, 32);
            n.put("swapTransaction", Base64.getEncoder().encodeToString(tx));
            return JSON.writeValueAsString(n);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String signatureOf(String signedBase64) {
        byte[] wire = Base64.getDecoder().decode(signedBase64);
        return io.lifeengine.cryptobot.solana.rpc.Base58.encode(Arrays.copyOfRange(wire, 1, 65));
    }

    /** The validator's answer: what it was asked, attested (its signature is the signer's business, not the service's). */
    String attestationEchoing(JsonNode body) {
        try {
            byte[] wire = Base64.getDecoder().decode(body.path("transaction").asText());
            String messageHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.copyOfRange(wire, 65, wire.length)));
            ObjectNode payload = JSON.createObjectNode();
            payload.put("message_hash", messageHash);
            payload.put("cluster", body.path("cluster").asText());
            payload.putArray("checks").add(ValidatorClient.CHECK_HASH_FROM_BYTES).add(ValidatorClient.CHECK_JUPITER_SWAP);
            payload.set("swap", body.path("swap"));
            ObjectNode r = JSON.createObjectNode();
            r.put("decision", validatorDecision.get());
            r.put("policyHash", body.path("policyHash").asText());
            r.put("verdictHash", body.path("expectedVerdictHash").asText());
            r.put("messageHash", messageHash);
            ObjectNode att = r.putObject("attestation");
            att.put("payload", JSON.writeValueAsString(payload));
            att.put("signature", "sig");
            att.put("validator", "validator-key");
            r.putArray("refusals");
            r.putArray("failedPredicates");
            return JSON.writeValueAsString(r);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- wiring ---------------------------------------------------------------------------------------

    SwapExecutor executor(String cluster, boolean executionEnabled, Optional<SwapExecutor.Price> price) {
        return executor(true, cluster, executionEnabled, price);
    }

    SwapExecutor executor(boolean enabled, String cluster, boolean executionEnabled, Optional<SwapExecutor.Price> price) {
        String rpcUrl = "http://localhost:" + rpc.getPort();
        SolanaRpcClient solana = new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(rpcUrl, rpcUrl, Duration.ofSeconds(3)), JSON);
        MarketDataProperties md = new MarketDataProperties("http://localhost:" + jupiter.getPort(), Duration.ofSeconds(3), Map.of(), Map.of(), true);
        JupiterSwapClient jup = new JupiterSwapClient(WebClient.builder(), md, JSON);
        SignerClient signerClient = new SignerClient(WebClient.builder(),
                new SignerProperties(true, "http://localhost:" + signer.getPort(), "tok", Duration.ofSeconds(3)));
        ValidatorClient validatorClient = new ValidatorClient(WebClient.builder(),
                new ValidatorProperties(true, "http://localhost:" + validator.getPort(), "tok", Duration.ofSeconds(3)));
        SwapsProperties props = new SwapsProperties(enabled, APPROVER, "", null, null, cluster, null, null, null, null, null,
                Duration.ofMillis(300), Duration.ofMillis(50));
        PolicyEngine policy = new PolicyEngine(new PolicyProperties(executionEnabled, null, null, null, null, null, null, 0, null),
                new AuthorizationProperties(null, null, null, null, null, null, null, null, List.of("REBALANCE", "PILOT_SWAP"), 100_000_000L, 10_000_000L, 100),
                new TimelockProperties(null, null, null));
        SwapOrderService orders = new SwapOrderService(props, repo, jup, solana, signerClient, meters, clock);
        return new SwapExecutor(props, repo, orders, jup, solana, validatorClient, signerClient, policy, () -> Mono.just(price), meters, clock);
    }

    SwapExecutor executor() {
        return executor("devnet", true, Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0.minusSeconds(30))));
    }

    /** An order as the approval left it: APPROVED by the approver, inside its window, bound to min_out. */
    SwapOrder approved(long minOut) {
        return approved(minOut, "devnet");
    }

    SwapOrder approved(long minOut, String cluster) {
        SwapOrder o = new SwapOrder(UUID.randomUUID(), "k-" + UUID.randomUUID(), CREATOR, SwapOrder.Status.APPROVED, SwapOrder.Direction.SOL_USDC, cluster,
                owner, 10_000_000L, minOut, 50, "{}", "a".repeat(64), "n".repeat(64), APPROVER, T0.minusSeconds(10), "jti-1", T0.plusSeconds(110), null, null,
                T0.minusSeconds(60), T0.minusSeconds(10));
        repo.put(o);
        return o;
    }

    static CryptobotPrincipal approver() {
        return new CryptobotPrincipal(APPROVER, "sebas@x", "ADMIN", List.of("ROLE_ADMIN"), "raw", T0, "jti-exec", "sid-1", false, null, "RS256");
    }

    static CryptobotPrincipal creator() {
        return new CryptobotPrincipal(CREATOR, "c@x", "USER", List.of("ROLE_USER"), "raw", T0, "jti-c", "sid-c", false, null, "RS256");
    }

    static String reason(Mono<?> m) {
        try {
            m.block();
            return null;
        } catch (SwapOrderService.SwapRefused ex) {
            return ex.reason();
        }
    }

    List<String> audit(UUID id) {
        return repo.auditEvents(id).collectList().block();
    }

    long sends() {
        return rpcMethods.stream().filter("sendTransaction"::equals).count();
    }

    // ---- the happy path -------------------------------------------------------------------------------

    @Test
    void anApprovedSwapIsRequotedAttestedSignedSentAndConfirmed() {
        SwapOrder o = approved(1_026_997L);
        SwapOrder done = executor().execute(approver(), o.id()).block();

        assertThat(done.status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(done.txSignature()).isNotBlank();
        assertThat(audit(o.id())).containsExactly("EXECUTING", "SIGNED", "SENT", "CONFIRMED");
        assertThat(sends()).isEqualTo(1);
        // the validator was asked for exactly the approved swap, under PILOT_SWAP
        JsonNode v = lastValidatorBody.get();
        assertThat(v.path("swap").path("in_amount").asLong()).isEqualTo(10_000_000L);
        assertThat(v.path("swap").path("min_out_amount").asLong()).isEqualTo(1_026_997L);
        assertThat(v.path("swap").path("input_mint").asText()).isEqualTo(SwapOrder.Direction.SOL_USDC.inputMint());
        assertThat(v.path("intent").path("strategy_id").asText()).isEqualTo("PILOT_SWAP");
        assertThat(v.path("intent").path("asset").asText()).isEqualTo("SOL");
        assertThat(v.path("cluster").asText()).isEqualTo("devnet");
        // and the signer for that same minimum
        assertThat(lastSignBody.get().path("minOut").asLong()).isEqualTo(1_026_997L);
        assertThat(lastSignBody.get().path("proposalId").asText()).isEqualTo(o.id().toString());
        assertThat(meters.counter(SwapOrderService.METRIC_ORDERS, "status", "CONFIRMED").count()).isEqualTo(1.0);
    }

    @Test
    void anOrderExecutesOnceTheSecondCallFindsItNoLongerApproved() {
        SwapOrder o = approved(1_026_997L);
        SwapExecutor ex = executor();
        assertThat(ex.execute(approver(), o.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(reason(ex.execute(approver(), o.id()))).isEqualTo("not_approved");
        assertThat(signs.get()).isEqualTo(1);
        assertThat(sends()).isEqualTo(1);
    }

    // ---- who, when ------------------------------------------------------------------------------------

    @Test
    void onlyTheApproverExecutesAndOnlyInsideTheWindow() {
        SwapOrder o = approved(1_026_997L);
        assertThat(reason(executor().execute(creator(), o.id()))).isEqualTo("not_approver");
        clock.now = o.expiresAt();
        assertThat(reason(executor().execute(approver(), o.id()))).isEqualTo("execution_expired");
        assertThat(repo.find(o.id()).block().status()).isEqualTo(SwapOrder.Status.EXPIRED);
        assertThat(signs.get()).isZero();
    }

    @Test
    void aPendingOrderIsNotExecutable() {
        SwapOrder a = approved(1_026_997L);
        SwapOrder pending = new SwapOrder(a.id(), a.idempotencyKey(), a.createdBy(), SwapOrder.Status.PENDING_APPROVAL, a.direction(), a.cluster(), a.owner(),
                a.inAmount(), a.minOut(), a.maxSlippageBps(), a.preview(), a.previewHash(), a.approvalNonce(), null, null, null, a.expiresAt(), null, null,
                a.createdAt(), a.updatedAt());
        repo.put(pending);
        assertThat(reason(executor().execute(approver(), a.id()))).isEqualTo("not_approved");
    }

    @Test
    void oneOrderAtATimePerProcess() {
        SwapOrder a = approved(1_026_997L);
        SwapOrder b = approved(1_026_997L);
        SwapExecutor ex = executor();
        // the first holds the executor while the RPC never confirms
        status.set("processed");
        Mono<SwapOrder> first = ex.execute(approver(), a.id());
        AtomicReference<SwapOrder> firstResult = new AtomicReference<>();
        Thread t = new Thread(() -> firstResult.set(first.block()));
        t.start();
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (!rpcMethods.contains("sendTransaction") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(reason(ex.execute(approver(), b.id()))).isEqualTo("executor_busy");
            t.join(5_000);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        assertThat(firstResult.get().status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        assertThat(repo.find(b.id()).block().status()).isEqualTo(SwapOrder.Status.APPROVED);
        // once free, the second one runs
        status.set("confirmed");
        assertThat(ex.execute(approver(), b.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
    }

    // ---- refusals before anything is signed: FAILED, nothing sent ------------------------------------------

    @Test
    void aWorseQuoteThanTheApprovedMinimumIsPriceMovedAndNothingIsSigned() {
        // the fixture's otherAmountThreshold is 1 026 998: one unit above it is a minimum the new route cannot honour
        SwapOrder o = approved(1_026_999L);
        SwapOrder done = executor().execute(approver(), o.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("price_moved");
        assertThat(signs.get()).isZero();
        assertThat(sends()).isZero();
        assertThat(audit(o.id())).containsExactly("EXECUTING", "FAILED");
    }

    @Test
    void exactlyTheApprovedMinimumIsAccepted() {
        assertThat(executor().execute(approver(), approved(1_026_998L).id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
    }

    @Test
    void theEmergencyStopRefusesBeforeQuoting() {
        SwapOrder o = approved(1_026_997L);
        SwapOrder done = executor("devnet", false, Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0))).execute(approver(), o.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("emergency_stop");
        assertThat(jupiter.getRequestCount()).isZero();
        assertThat(signs.get()).isZero();
    }

    @Test
    void noPriceMeansThePolicyDeniesAndTheValidatorIsNeverAsked() {
        SwapOrder o = approved(1_026_997L);
        SwapOrder done = executor("devnet", true, Optional.empty()).execute(approver(), o.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("policy_denied");
        assertThat(validator.getRequestCount()).isZero();
        assertThat(signs.get()).isZero();
    }

    @Test
    void aValidatorDenialIsFailedAndTheSignerIsNeverAsked() {
        validatorDecision.set("DENY");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("validator_refused");
        assertThat(signs.get()).isZero();
    }

    @Test
    void aSignerThatAltersTheMessageIsCaughtBeforeTheBroadcast() {
        signerTampers.set(true);
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("signature_invalid");
        assertThat(done.txSignature()).isNull();
        assertThat(sends()).isZero();
    }

    @Test
    void aSignerWhoseKeyIsNotTheOrdersOwnerIsRefusedBeforeQuoting() {
        // approved for another key (the signer was rotated since): the approval was for that owner's funds, not these
        SwapOrder a = approved(1_026_997L);
        SwapOrder otherOwner = new SwapOrder(a.id(), a.idempotencyKey(), a.createdBy(), a.status(), a.direction(), a.cluster(),
                SolanaKeypair.generate().publicKeyBase58(), a.inAmount(), a.minOut(), a.maxSlippageBps(), a.preview(), a.previewHash(), a.approvalNonce(),
                a.approvedBy(), a.approvedAt(), a.approvalJti(), a.expiresAt(), null, null, a.createdAt(), a.updatedAt());
        repo.put(otherOwner);
        SwapOrder done = executor().execute(approver(), a.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("signer_changed");
        assertThat(jupiter.getRequestCount()).isZero();
        assertThat(signs.get()).isZero();
    }

    @Test
    void aSignerOnAnotherClusterIsRefused() {
        signerCluster.set("mainnet-beta");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.error()).isEqualTo("signer_cluster_mismatch");
        assertThat(signs.get()).isZero();
    }

    @Test
    void anOrderForAnotherClusterThanTheConfiguredOneIsRefused() {
        SwapOrder o = approved(1_026_997L, "mainnet-beta");
        SwapOrder done = executor().execute(approver(), o.id()).block();
        assertThat(done.error()).isEqualTo("cluster_changed");
        assertThat(signs.get()).isZero();
    }

    @Test
    void mainnetIsStoppedAtTheBroadcastByTheRpcClientsGate() {
        // everything agrees on mainnet — config, order, signer — and the send is still refused: allow-mainnet is false
        signerCluster.set("mainnet-beta");
        SwapOrder o = approved(1_026_997L, "mainnet-beta");
        SwapOrder done = executor("mainnet-beta", true, Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0))).execute(approver(), o.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("mainnet_disabled");
        assertThat(sends()).isZero();
    }

    // ---- after the broadcast: certain or not, never retried --------------------------------------------

    @Test
    void aJsonRpcRejectionOfTheBroadcastIsCertainlyFailed() {
        send.set("reject");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("broadcast_rejected");
        assertThat(done.txSignature()).isNotNull();
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void aBroadcastThatMayHaveLandedIsUnknownSentAndNotRetried() {
        send.set("down");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        assertThat(done.error()).isEqualTo("broadcast_uncertain");
        assertThat(sends()).isEqualTo(1);
        assertThat(rpcMethods).doesNotContain("getSignatureStatuses");
    }

    @Test
    void noConfirmationInTimeIsUnknownSent() {
        status.set("unknown");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        assertThat(done.error()).isEqualTo("not_confirmed_in_time");
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void aFailureTheChainReportsIsFailed() {
        status.set("failed");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("onchain_failed");
    }

    // ---- reconcile -------------------------------------------------------------------------------------

    @Test
    void reconcileMovesAnUnknownSentOrderToWhereTheChainSaysAndNeverSends() {
        status.set("unknown");
        SwapExecutor ex = executor();
        SwapOrder o = ex.execute(approver(), approved(1_026_997L).id()).block();
        assertThat(o.status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        // still unknown: unchanged
        assertThat(ex.reconcile(creator(), o.id()).block().status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        status.set("finalized");
        SwapOrder r = ex.reconcile(creator(), o.id()).block();
        assertThat(r.status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(sends()).isEqualTo(1);
        assertThat(audit(o.id())).endsWith("RECONCILED", "CONFIRMED");
    }

    @Test
    void reconcileOfAnOrderLeftExecutingWithoutASignatureFailsItOnlyAfterTheWindow() {
        SwapOrder a = approved(1_026_997L);
        SwapOrder stuck = new SwapOrder(a.id(), a.idempotencyKey(), a.createdBy(), SwapOrder.Status.EXECUTING, a.direction(), a.cluster(), a.owner(),
                a.inAmount(), a.minOut(), a.maxSlippageBps(), a.preview(), a.previewHash(), a.approvalNonce(), a.approvedBy(), a.approvedAt(),
                a.approvalJti(), a.expiresAt(), null, null, a.createdAt(), a.updatedAt());
        repo.put(stuck);
        SwapExecutor ex = executor();
        assertThat(ex.reconcile(approver(), a.id()).block().status()).isEqualTo(SwapOrder.Status.EXECUTING);
        clock.now = a.expiresAt();
        SwapOrder r = ex.reconcile(approver(), a.id()).block();
        assertThat(r.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(r.error()).isEqualTo("abandoned_before_signing");
        assertThat(rpcMethods).isEmpty();
    }

    @Test
    void reconcileOfAnOrderLeftExecutingWithASignatureAsksTheChain() {
        SwapOrder a = approved(1_026_997L);
        repo.put(new SwapOrder(a.id(), a.idempotencyKey(), a.createdBy(), SwapOrder.Status.EXECUTING, a.direction(), a.cluster(), a.owner(), a.inAmount(),
                a.minOut(), a.maxSlippageBps(), a.preview(), a.previewHash(), a.approvalNonce(), a.approvedBy(), a.approvedAt(), a.approvalJti(),
                a.expiresAt(), "5igSig", null, a.createdAt(), a.updatedAt()));
        status.set("unknown");
        assertThat(executor().reconcile(approver(), a.id()).block().status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        status.set("confirmed");
        assertThat(executor().reconcile(approver(), a.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(sends()).isZero();
    }

    @Test
    void reconcileIsOnlyForTheCreatorOrTheApprover() {
        SwapOrder o = approved(1_026_997L);
        CryptobotPrincipal stranger = new CryptobotPrincipal(UUID.randomUUID(), "s@x", "USER", List.of("ROLE_USER"), "raw", T0, "j", "s", false, null,
                "RS256");
        assertThat(reason(executor().reconcile(stranger, o.id()))).isEqualTo("not_creator_or_approver");
    }

    // ---- the pieces ------------------------------------------------------------------------------------

    @Test
    void humanEscalationIsAcceptableBecauseTheOrderCarriesTheApprovalAndNothingElseIs() {
        var allow = new io.lifeengine.cryptobot.core.policy.PolicyVerdict(io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision.ALLOW,
                io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation.NONE, null, List.of(), List.of(), "v", "h", "i");
        var human = new io.lifeengine.cryptobot.core.policy.PolicyVerdict(io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision.ESCALATE,
                io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation.REQUIRE_HUMAN_SIGNATURE, null, List.of(), List.of(), "v", "h", "i");
        var agent = new io.lifeengine.cryptobot.core.policy.PolicyVerdict(io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision.ESCALATE,
                io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation.REQUIRE_SECOND_AGENT, null, List.of(), List.of(), "v", "h", "i");
        var deny = new io.lifeengine.cryptobot.core.policy.PolicyVerdict(io.lifeengine.cryptobot.core.policy.PolicyVerdict.Decision.DENY,
                io.lifeengine.cryptobot.core.policy.PolicyVerdict.Escalation.NONE, null, List.of(), List.of(), "v", "h", "i");
        assertThat(SwapExecutor.acceptable(allow)).isTrue();
        assertThat(SwapExecutor.acceptable(human)).isTrue();
        assertThat(SwapExecutor.acceptable(agent)).isFalse();
        assertThat(SwapExecutor.acceptable(deny)).isFalse();
    }

    @Test
    void centsAreRoundedUpSolNeedsAPriceAndUsdcIsAtPar() {
        SwapOrder sol = approved(1L);
        Optional<SwapExecutor.Price> p = Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0));
        // 0.01 SOL × 150 USD = 1.50 USD
        assertThat(SwapExecutor.cents(sol, p)).isEqualTo(150L);
        assertThat(SwapExecutor.cents(sol, Optional.empty())).isNull();
        SwapOrder usdc = new SwapOrder(UUID.randomUUID(), "k", CREATOR, SwapOrder.Status.APPROVED, SwapOrder.Direction.USDC_SOL, "devnet", owner,
                10_000_001L, 1L, 50, "{}", "a", "n", APPROVER, T0, "j", T0, null, null, T0, T0);
        // 10.000001 USDC → 1001 cents (up)
        assertThat(SwapExecutor.cents(usdc, Optional.empty())).isEqualTo(1001L);
    }

    @Test
    void theOtherSwapsOfTheLastDayAreTheExposure() {
        SwapExecutor ex = executor();
        SwapOrder o = approved(1L);
        Optional<SwapExecutor.Price> p = Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0.minusSeconds(30)));
        var facts = ex.facts(o, p, List.of(approved(1L), approved(1L)), T0);
        assertThat(facts.state().dailyExposureCents()).isEqualTo(300L);
        assertThat(facts.state().oracleAgeSeconds()).isEqualTo(30L);
        assertThat(facts.intent().tradeValueCents()).isEqualTo(150L);
        assertThat(facts.intent().strategyId()).isEqualTo("PILOT_SWAP");
        assertThat(ex.facts(o, Optional.empty(), List.of(), T0).intent().tradeValueCents()).isNull();
    }

    // ---- LEAD review of b2b: money guards pinned ------------------------------------------------------

    SwapOrder withStatus(SwapOrder a, SwapOrder.Status st, String signature) {
        SwapOrder o = new SwapOrder(a.id(), a.idempotencyKey(), a.createdBy(), st, a.direction(), a.cluster(), a.owner(), a.inAmount(), a.minOut(),
                a.maxSlippageBps(), a.preview(), a.previewHash(), a.approvalNonce(), a.approvedBy(), a.approvedAt(), a.approvalJti(), a.expiresAt(),
                signature, null, a.createdAt(), a.updatedAt());
        repo.put(o);
        return o;
    }

    static io.lifeengine.cryptobot.adapters.jupiter.SwapQuote quote(String in, String out, long inAmount, long minOut) {
        return new io.lifeengine.cryptobot.adapters.jupiter.SwapQuote(in, out, inAmount, minOut + 100, minOut, 50, BigDecimal.ZERO, List.of(), null);
    }

    @Test
    void theBindingRefusesOtherMintsAnyOtherInputAndALowerMinimum() {
        SwapOrder o = approved(1_000L);
        String sol = SwapOrder.Direction.SOL_USDC.inputMint();
        String usdc = SwapOrder.Direction.SOL_USDC.outputMint();
        assertThat(SwapExecutor.binding(o, quote(sol, usdc, 10_000_000L, 1_000L))).isNull();
        assertThat(SwapExecutor.binding(o, quote(sol, usdc, 10_000_000L, 999L))).isEqualTo("price_moved");
        assertThat(SwapExecutor.binding(o, quote(usdc, sol, 10_000_000L, 1_000L))).isEqualTo("quote_mints_changed");
        assertThat(SwapExecutor.binding(o, quote(sol, "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 10_000_000L, 1_000L))).isEqualTo("quote_mints_changed");
        // more than approved, and less: both are another swap
        assertThat(SwapExecutor.binding(o, quote(sol, usdc, 10_000_001L, 1_000L))).isEqualTo("quote_amount_changed");
        assertThat(SwapExecutor.binding(o, quote(sol, usdc, 9_999_999L, 1_000L))).isEqualTo("quote_amount_changed");
    }

    @Test
    void aFailedSimulationOfTheNewTransactionSignsNothing() {
        simulationOk.set(false);
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("simulation_failed");
        assertThat(validator.getRequestCount()).isZero();
        assertThat(signs.get()).isZero();
        assertThat(sends()).isZero();
    }

    @Test
    void swapsDisabledRefusesExecuteAndReconcile() {
        SwapOrder o = approved(1_026_997L);
        SwapExecutor off = executor(false, "devnet", true, Optional.of(new SwapExecutor.Price(new BigDecimal("150"), T0)));
        assertThat(reason(off.execute(approver(), o.id()))).isEqualTo("swaps_disabled");
        assertThat(reason(off.reconcile(approver(), o.id()))).isEqualTo("swaps_disabled");
        assertThat(repo.find(o.id()).block().status()).isEqualTo(SwapOrder.Status.APPROVED);
        assertThat(jupiter.getRequestCount()).isZero();
    }

    @Test
    void exposureCountsEveryOtherSwapThatMayHaveMovedFundsAndNothingElse() {
        // 0.01 SOL at 150 USD = 150 cents each; four that may have moved funds, one that failed and one only approved
        withStatus(approved(1L), SwapOrder.Status.EXECUTING, "s1");
        withStatus(approved(1L), SwapOrder.Status.SENT, "s2");
        withStatus(approved(1L), SwapOrder.Status.UNKNOWN_SENT, "s3");
        withStatus(approved(1L), SwapOrder.Status.CONFIRMED, "s4");
        withStatus(approved(1L), SwapOrder.Status.FAILED, null);
        approved(1L);
        SwapOrder o = approved(1_026_997L);
        assertThat(executor().execute(approver(), o.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(lastValidatorBody.get().path("state").path("daily_exposure_cents").asLong()).isEqualTo(600L);
        assertThat(lastValidatorBody.get().path("intent").path("trade_value_cents").asLong()).isEqualTo(150L);
    }

    @Test
    void noOrderOutsideApprovedIsExecuted() {
        for (SwapOrder.Status st : SwapOrder.Status.values()) {
            if (st == SwapOrder.Status.APPROVED) {
                continue;
            }
            SwapOrder o = withStatus(approved(1_026_997L), st, null);
            assertThat(reason(executor().execute(approver(), o.id()))).as(st.name()).isEqualTo("not_approved");
        }
        // past its window too: a rejected order is "not approved", never re-labelled as an expired approval
        SwapOrder late = withStatus(approved(1_026_997L), SwapOrder.Status.REJECTED, null);
        clock.now = late.expiresAt().plusSeconds(1);
        assertThat(reason(executor().execute(approver(), late.id()))).isEqualTo("not_approved");
        assertThat(repo.find(late.id()).block().status()).isEqualTo(SwapOrder.Status.REJECTED);
        assertThat(signs.get()).isZero();
        assertThat(jupiter.getRequestCount()).isZero();
    }

    @Test
    void anOrderThatStoppedBeingApprovedBetweenTheReadAndTheClaimIsNotExecuted() {
        SwapOrder a = approved(1_026_997L);
        withStatus(a, SwapOrder.Status.REJECTED, null);
        // the read still sees it APPROVED (a stale read, a concurrent reject): only the conditional claim can say no
        InMemorySwapOrderRepository real = repo;
        repo = new InMemorySwapOrderRepository() {
            @Override
            public Mono<SwapOrder> find(UUID id) {
                return id.equals(a.id()) ? Mono.just(a) : real.find(id);
            }

            @Override
            public Mono<SwapOrder> transition(UUID id, SwapOrder.Status from, SwapOrder.Status to, String error) {
                return real.transition(id, from, to, error);
            }
        };
        assertThat(reason(executor().execute(approver(), a.id()))).isEqualTo("not_approved");
        assertThat(real.find(a.id()).block().status()).isEqualTo(SwapOrder.Status.REJECTED);
        assertThat(jupiter.getRequestCount()).isZero();
    }

    @Test
    void theBytesSimulatedAreTheBytesAttestedSignedAndSent() {
        executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(simulated).hasSize(1);
        String bytes = simulated.get(0);
        assertThat(lastValidatorBody.get().path("transaction").asText()).isEqualTo(bytes);
        assertThat(signedRequests).containsExactly(bytes);
        byte[] unsigned = Base64.getDecoder().decode(bytes);
        byte[] onTheWire = Base64.getDecoder().decode(sent.get(0));
        assertThat(Arrays.copyOfRange(onTheWire, 65, onTheWire.length)).isEqualTo(Arrays.copyOfRange(unsigned, 65, unsigned.length));
    }

    // ---- LEAD review of b2b: design ---------------------------------------------------------------------

    @Test
    void reconcileFailsASignatureTheChainNeverSawOncePastItsLastValidHeight() {
        SwapOrder o = withStatus(approved(1_026_997L), SwapOrder.Status.UNKNOWN_SENT, "5sig");
        repo.putHeight(o.id(), 1_000L);
        status.set("unknown");
        blockHeight.set(1_000L);
        // at the last valid height it may still land
        assertThat(executor().reconcile(approver(), o.id()).block().status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        blockHeight.set(1_001L);
        SwapOrder r = executor().reconcile(approver(), o.id()).block();
        assertThat(r.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(r.error()).isEqualTo("blockhash_expired");
        assertThat(sends()).isZero();
    }

    @Test
    void aSignatureTheChainKnowsWinsOverAnExpiredHeight() {
        SwapOrder o = withStatus(approved(1_026_997L), SwapOrder.Status.UNKNOWN_SENT, "5sig");
        repo.putHeight(o.id(), 1_000L);
        blockHeight.set(5_000L);
        status.set("finalized");
        assertThat(executor().reconcile(approver(), o.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
    }

    @Test
    void theHeightIsReadBeforeTheSignatureStatus() {
        SwapOrder o = withStatus(approved(1_026_997L), SwapOrder.Status.UNKNOWN_SENT, "5sig");
        repo.putHeight(o.id(), 1_000L);
        status.set("unknown");
        executor().reconcile(approver(), o.id()).block();
        assertThat(rpcMethods).containsSubsequence("getBlockHeight", "getSignatureStatuses");
    }

    @Test
    void aCallerThatGoesAwayDoesNotStopTheExecutionHalfway() throws Exception {
        SwapOrder o = approved(1_026_997L);
        executor().execute(approver(), o.id()).subscribe().dispose();
        long deadline = System.currentTimeMillis() + 5_000;
        while (repo.find(o.id()).block().status() != SwapOrder.Status.CONFIRMED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(repo.find(o.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void theWindowIsCheckedAgainRightBeforeTheBroadcast() {
        SwapOrder o = approved(1_026_997L);
        onSign.set(() -> clock.now = o.expiresAt());
        SwapOrder done = executor().execute(approver(), o.id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.FAILED);
        assertThat(done.error()).isEqualTo("execution_window_elapsed");
        assertThat(done.txSignature()).isNotNull();
        assertThat(sends()).isZero();
    }

    @Test
    void anRpcThatAnswersAnotherSignatureLeavesTheOrderUnknownSent() {
        send.set("other");
        SwapOrder done = executor().execute(approver(), approved(1_026_997L).id()).block();
        assertThat(done.status()).isEqualTo(SwapOrder.Status.UNKNOWN_SENT);
        assertThat(done.error()).isEqualTo("signature_mismatch");
        assertThat(rpcMethods).doesNotContain("getSignatureStatuses");
    }

    @Test
    void onlyTheHttpStatusOfARefusalReachesTheLog() {
        assertThat(SwapExecutor.httpStatusOnly("HTTP 403 {\"error\":\"token=abc\"}")).isEqualTo("HTTP 403");
        assertThat(SwapExecutor.httpStatusOnly("connection reset by peer at 10.0.0.1")).isEqualTo("refused");
        assertThat(SwapExecutor.httpStatusOnly(null)).isEqualTo("refused");
    }

    @Test
    void theReconcileJobSettlesInFlightOrdersAndLeavesSettledOnesAlone() {
        SwapOrder settled = withStatus(approved(1_026_997L), SwapOrder.Status.UNKNOWN_SENT, "5a");
        SwapOrder done = withStatus(approved(1_026_997L), SwapOrder.Status.CONFIRMED, "5b");
        status.set("finalized");
        SwapExecutor ex = executor();
        SwapsProperties props = new SwapsProperties(true, APPROVER, "", null, null, "devnet", null, null, null, null, null);
        SwapReconcileJob job = new SwapReconcileJob(props, repo, ex);
        assertThat(job.sweep().block()).isEqualTo(1L);
        assertThat(repo.find(settled.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(repo.find(done.id()).block().status()).isEqualTo(SwapOrder.Status.CONFIRMED);
        assertThat(sends()).isZero();
    }
}
