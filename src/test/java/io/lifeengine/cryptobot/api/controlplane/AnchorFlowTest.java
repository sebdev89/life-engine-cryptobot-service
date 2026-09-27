package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import io.lifeengine.cryptobot.core.receipts.MerkleTree;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-394 over HTTP with a fake devnet and a fake signer that really signs: two users' receipts
 * → one admin sweep (`wait=true`) → memo transaction finalized → every receipt carries the anchor
 * and a proof that folds to the root in the memo → verify per receipt and per batch → each user
 * sees only their own receipts of the batch → the explorer link is the devnet one.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class AnchorFlowTest {

    static final String ADDRESS_A = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    static final String ADDRESS_B = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";
    static final SolanaKeypair SIGNER_KEY = SolanaKeypair.generate();
    static final ObjectMapper JSON = new ObjectMapper();

    private static MockWebServer rpc;
    private static MockWebServer signer;
    private static MockWebServer runtime;

    @Autowired private WebTestClient web;

    @BeforeAll
    static void startMocks() throws Exception {
        rpc = new MockWebServer();
        rpc.setDispatcher(new DevnetDispatcher());
        rpc.start();
        signer = new MockWebServer();
        signer.setDispatcher(new SignerDispatcher());
        signer.start();
        runtime = new MockWebServer();
        runtime.setDispatcher(new ControlPlaneFlowTest.RuntimeDispatcher());
        runtime.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        rpc.shutdown();
        signer.shutdown();
        runtime.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.runtime.base-url", () -> "http://localhost:" + runtime.getPort());
        r.add("cryptobot.signer.enabled", () -> "true");
        r.add("cryptobot.signer.base-url", () -> "http://localhost:" + signer.getPort());
        r.add("cryptobot.signer.token", () -> "flow-token");
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
        DevnetDispatcher.reset();
    }

    @Test
    void receiptsOfTwoUsersAreAnchoredInOneFinalizedMemoAndVerifiable() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String tokenA = bearer(userA, List.of("RUNTIME_OPERATOR"));
        String tokenB = bearer(userB, List.of("RUNTIME_OPERATOR"));
        String admin = bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));

        String walletA = register(tokenA, ADDRESS_A);
        String walletB = register(tokenB, ADDRESS_B);
        List<String> receiptsA = hashes(walletA, tokenA);
        List<String> receiptsB = hashes(walletB, tokenB);
        assertThat(receiptsA).hasSize(2); // WALLET_SNAPSHOT + RISK_DECISION
        assertThat(receiptsB).hasSize(2);
        for (String h : receiptsA) {
            assertThat(verifyReceipt(h, tokenA).path("anchor").path("anchored").asBoolean()).isFalse();
        }

        // An operator cannot open a batch; an admin can, and waits for finality.
        web.post().uri("/api/cryptobot/anchors?wait=true").header(HttpHeaders.AUTHORIZATION, tokenA).exchange().expectStatus().isForbidden();
        JsonNode sweep = JSON.readTree(web.post().uri("/api/cryptobot/anchors?wait=true").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        JsonNode anchored = sweep.path("anchored");
        assertThat(anchored.path("status").asText()).isEqualTo("FINALIZED");
        assertThat(anchored.path("receiptCount").asInt()).isEqualTo(4);
        assertThat(anchored.path("chain").asText()).isEqualTo("solana-devnet");
        assertThat(anchored.path("slot").asLong()).isEqualTo(4242L);
        assertThat(anchored.path("feePayer").asText()).isEqualTo(SIGNER_KEY.publicKeyBase58());
        assertThat(sweep.path("pending").asLong()).isZero();
        String root = anchored.path("root").asText();
        String tx = anchored.path("tx").asText();

        // What went to the chain is the memo, the memo names the root, and the root is the tree of the four receipts.
        AnchorMemo memo = AnchorMemo.parse(DevnetDispatcher.lastMemo.get()).orElseThrow();
        assertThat(memo.root()).isEqualTo(root);
        assertThat(memo.count()).isEqualTo(4);
        List<String> all = new ArrayList<>(receiptsA);
        all.addAll(receiptsB);
        assertThat(MerkleTree.of(all).root()).isEqualTo(root);
        assertThat(DevnetDispatcher.lastSignature.get()).isEqualTo(tx);
        assertThat(SignerDispatcher.lastRoot.get()).isEqualTo(root);

        // Every receipt of both users carries the anchor, and verify folds the proof back to the root.
        for (String h : all) {
            String token = receiptsA.contains(h) ? tokenA : tokenB;
            JsonNode receipt = JSON.readTree(web.get().uri("/api/cryptobot/receipts/" + h).header(HttpHeaders.AUTHORIZATION, token)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
            JsonNode anchor = receipt.path("receipt").path("anchor");
            assertThat(anchor.path("tx").asText()).isEqualTo(tx);
            assertThat(anchor.path("root").asText()).isEqualTo(root);
            assertThat(anchor.path("slot").asLong()).isEqualTo(4242L);
            List<String> proof = new ArrayList<>();
            anchor.path("proof").forEach(n -> proof.add(n.asText()));
            assertThat(proof).hasSize(2);
            assertThat(MerkleTree.verify(h, proof, root)).isTrue();

            JsonNode v = verifyReceipt(h, token);
            assertThat(v.path("valid").asBoolean()).as("receipt checks still pass (unwrapped)").isTrue();
            assertThat(v.path("hashMatchesCanonical").asBoolean()).isTrue();
            assertThat(v.path("signatureValid").asBoolean()).isTrue();
            assertThat(v.path("anchor").path("anchored").asBoolean()).isTrue();
            assertThat(v.path("anchor").path("proofValid").asBoolean()).isTrue();
            assertThat(v.path("anchor").path("status").asText()).isEqualTo("FINALIZED");
            assertThat(v.path("anchor").path("explorerUrl").asText()).isEqualTo("https://explorer.solana.com/tx/" + tx + "?cluster=devnet");
        }

        // The batch: listed with its explorer link, verified end to end, and each user sees only their own members.
        JsonNode list = JSON.readTree(web.get().uri("/api/cryptobot/anchors").header(HttpHeaders.AUTHORIZATION, tokenA)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("explorerUrl").asText()).endsWith("?cluster=devnet");
        assertThat(list.get(0).path("anchor").path("root").asText()).isEqualTo(root);

        JsonNode detailA = JSON.readTree(web.get().uri("/api/cryptobot/anchors/" + root).header(HttpHeaders.AUTHORIZATION, tokenA)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        List<String> mineA = new ArrayList<>();
        detailA.path("myReceipts").forEach(m -> mineA.add(m.path("receiptHash").asText()));
        assertThat(mineA).containsExactlyInAnyOrderElementsOf(receiptsA);
        JsonNode detailB = JSON.readTree(web.get().uri("/api/cryptobot/anchors/" + root).header(HttpHeaders.AUTHORIZATION, tokenB)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        List<String> mineB = new ArrayList<>();
        detailB.path("myReceipts").forEach(m -> mineB.add(m.path("receiptHash").asText()));
        assertThat(mineB).containsExactlyInAnyOrderElementsOf(receiptsB).doesNotContainAnyElementsOf(receiptsA);
        // B cannot read A's receipt through the receipt endpoint either.
        web.get().uri("/api/cryptobot/receipts/" + receiptsA.get(0)).header(HttpHeaders.AUTHORIZATION, tokenB).exchange().expectStatus().isNotFound();

        JsonNode verification = JSON.readTree(web.post().uri("/api/cryptobot/anchors/" + root + "/verify").header(HttpHeaders.AUTHORIZATION, tokenB)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(verification.path("valid").asBoolean()).isTrue();
        assertThat(verification.path("recomputedRoot").asText()).isEqualTo(root);
        assertThat(verification.path("memberCount").asInt()).isEqualTo(4);
        assertThat(verification.path("proofsValid").asBoolean()).isTrue();
        assertThat(verification.path("memoMatches").asBoolean()).isTrue();
        assertThat(verification.path("onChain").path("found").asBoolean()).isTrue();
        assertThat(verification.path("onChain").path("memoMatches").asBoolean()).isTrue();
        assertThat(verification.path("onChain").path("slot").asLong()).isEqualTo(4242L);
        web.post().uri("/api/cryptobot/anchors/sha256:" + "00".repeat(32) + "/verify").header(HttpHeaders.AUTHORIZATION, tokenB).exchange().expectStatus().isNotFound();
        web.get().uri("/api/cryptobot/anchors/not-a-root").header(HttpHeaders.AUTHORIZATION, tokenB).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_ROOT");

        // Nothing left to anchor: a second sweep opens no batch and sends nothing.
        int sends = DevnetDispatcher.sendCount.get();
        JsonNode again = JSON.readTree(web.post().uri("/api/cryptobot/anchors").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(again.path("anchored").isNull()).isTrue();
        assertThat(again.path("pending").asLong()).isZero();
        assertThat(DevnetDispatcher.sendCount.get()).isEqualTo(sends);
        assertThat(DevnetDispatcher.statusCalls.get()).as("the wait polled: confirmed first, then finalized").isGreaterThanOrEqualTo(2);
    }

    private String register(String token, String address) throws Exception {
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + address + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        return created.path("wallet").path("id").asText();
    }

    private List<String> hashes(String walletId, String token) throws Exception {
        JsonNode receipts = JSON.readTree(web.get().uri("/api/cryptobot/wallets/" + walletId + "/receipts").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        List<String> out = new ArrayList<>();
        receipts.forEach(r -> out.add(r.path("receiptHash").asText()));
        return out;
    }

    private JsonNode verifyReceipt(String hash, String token) throws Exception {
        return JSON.readTree(web.post().uri("/api/cryptobot/receipts/" + hash + "/verify").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    // ---- fakes -----------------------------------------------------------------------------

    /** Devnet: balances for the wallets, a blockhash, and a memo transaction that is confirmed on the first look and finalized on the next. */
    static final class DevnetDispatcher extends Dispatcher {
        static final AtomicInteger sendCount = new AtomicInteger();
        static final AtomicInteger statusCalls = new AtomicInteger();
        static final AtomicReference<String> lastMemo = new AtomicReference<>();
        static final AtomicReference<String> lastSignature = new AtomicReference<>();

        static void reset() {
            sendCount.set(0);
            statusCalls.set(0);
            lastMemo.set(null);
            lastSignature.set(null);
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            try {
                JsonNode body = JSON.readTree(request.getBody().readUtf8());
                String method = body.path("method").asText();
                String result = switch (method) {
                    case "getBalance" -> "{\"context\":{\"slot\":1},\"value\":7000000000}";
                    case "getTokenAccountsByOwner" -> {
                        String program = body.path("params").get(1).path("programId").asText();
                        yield program.startsWith("Tokenkeg")
                                ? "{\"context\":{\"slot\":1},\"value\":[{\"pubkey\":\"acct\",\"account\":{\"data\":{\"parsed\":{\"info\":{\"mint\":\"" + DEVNET_USDC
                                        + "\",\"tokenAmount\":{\"amount\":\"300000000\",\"decimals\":6,\"uiAmountString\":\"300\"}}}}}}]}"
                                : "{\"context\":{\"slot\":1},\"value\":[]}";
                    }
                    case "getSignaturesForAddress" -> "[]";
                    case "getLatestBlockhash" -> "{\"context\":{\"slot\":1},\"value\":{\"blockhash\":\"So11111111111111111111111111111111111111112\",\"lastValidBlockHeight\":1000}}";
                    case "getBlockHeight" -> "10";
                    case "sendTransaction" -> {
                        sendCount.incrementAndGet();
                        byte[] wire = Base64.getDecoder().decode(body.path("params").get(0).asText());
                        String sig = Base58.encode(Arrays.copyOfRange(wire, 1, 65));
                        String message = new String(Arrays.copyOfRange(wire, 65, wire.length), StandardCharsets.ISO_8859_1);
                        int at = message.indexOf("ir/1 root=");
                        lastMemo.set(at < 0 ? null : message.substring(at));
                        lastSignature.set(sig);
                        yield "\"" + sig + "\"";
                    }
                    case "getSignatureStatuses" -> {
                        int n = statusCalls.incrementAndGet();
                        String sig = body.path("params").get(0).get(0).asText();
                        yield sig.equals(lastSignature.get())
                                ? "{\"context\":{\"slot\":4242},\"value\":[{\"slot\":4242,\"confirmations\":null,\"err\":null,\"confirmationStatus\":\"" + (n == 1 ? "confirmed" : "finalized") + "\"}]}"
                                : "{\"context\":{\"slot\":4242},\"value\":[null]}";
                    }
                    case "getTransaction" -> {
                        String sig = body.path("params").get(0).asText();
                        yield sig.equals(lastSignature.get()) && lastMemo.get() != null
                                ? "{\"slot\":4242,\"blockTime\":1789700000,\"meta\":{\"err\":null,\"logMessages\":[\"Program MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr invoke [1]\"]},"
                                        + "\"transaction\":{\"message\":{\"instructions\":[{\"program\":\"spl-memo\",\"programId\":\"MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr\",\"parsed\":"
                                        + JSON.writeValueAsString(lastMemo.get()) + "}]}}}"
                                : "null";
                    }
                    default -> "null";
                };
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + body.path("id").asLong() + ",\"result\":" + result + "}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }
    }

    /** cryptobot-signer as the service sees it: identity on devnet, and sign-anchor signs the message with the signer key. */
    static final class SignerDispatcher extends Dispatcher {
        static final AtomicReference<String> lastRoot = new AtomicReference<>();

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if (!"flow-token".equals(request.getHeader("X-Signer-Token"))) {
                return new MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("{\"reason\":\"bad_token\"}");
            }
            try {
                if ("GET".equals(request.getMethod()) && "/api/signer/identity".equals(request.getPath())) {
                    return json("{\"publicKey\":\"" + SIGNER_KEY.publicKeyBase58() + "\",\"cluster\":\"devnet\",\"maxLamports\":2000000000,\"allowedDestinations\":[],\"enabled\":true}");
                }
                if ("POST".equals(request.getMethod()) && "/api/signer/sign-anchor".equals(request.getPath())) {
                    JsonNode body = JSON.readTree(request.getBody().readUtf8());
                    lastRoot.set(body.path("root").asText());
                    byte[] wire = Base64.getDecoder().decode(body.path("unsignedTransactionBase64").asText());
                    byte[] message = Arrays.copyOfRange(wire, 65, wire.length);
                    byte[] sig = SIGNER_KEY.sign(message);
                    byte[] signed = new byte[wire.length];
                    signed[0] = 1;
                    System.arraycopy(sig, 0, signed, 1, 64);
                    System.arraycopy(message, 0, signed, 65, message.length);
                    return json("{\"signedTransactionBase64\":\"" + Base64.getEncoder().encodeToString(signed) + "\",\"signer\":\"" + SIGNER_KEY.publicKeyBase58()
                            + "\",\"txHash\":\"\",\"signature\":\"" + Base58.encode(sig) + "\"}");
                }
                return new MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{\"reason\":\"program_not_allowed\"}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
        }
    }

    private static String bearer(UUID userId, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", "operator@test.local")
                .claim("authorities", authorities)
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
