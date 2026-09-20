package io.lifeengine.cryptobot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * KAN-570 — the whole pipeline, for real, with nothing stubbed: {@code intent → risk → policy →
 * approval → timelock → execute (preconditions + mainnet gate) → independent validator → isolated
 * signer → sendTransaction → SUBMITTED → confirmation → EXECUTED → EXECUTION receipt}, against the
 * demo stack of {@code docker-compose.demo.yml} (service + signer + validator + Postgres) and a
 * Solana RPC — devnet, or a local {@code solana-test-validator} when devnet's faucet is dry.
 *
 * <p>This is a black-box test over HTTP: it talks to the same endpoints the UI and the demo script
 * use, so {@code PolicyEngine.executionPreconditions}, {@code ExecutionService.requireClusterAllowed},
 * the real {@code ValidatorClient}/{@code SignerClient} and the real {@code SolanaRpcClient} are all
 * on the path. The transaction signature is then checked <em>independently</em> against the RPC
 * ({@code getSignatureStatuses}), not through the service.
 *
 * <p>It runs only under the Maven profile {@code e2e-devnet} (Failsafe, {@code ./mvnw -Pe2e-devnet
 * verify}) and skips itself when the stack is not up. Configuration, all optional except the
 * secret and the wallet, from {@code .env.demo} (written by {@code scripts/demo/wallet-devnet.sh})
 * or the environment:
 * <ul>
 *   <li>{@code CRYPTOBOT_E2E_BASE_URL} (default {@code http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-8091}})
 *   <li>{@code JWT_SECRET} — the demo stack's HS256 secret (never a real environment's)
 *   <li>{@code DEMO_WALLET_ADDRESS} — the devnet wallet the signer controls (public key only)
 *   <li>{@code CRYPTOBOT_E2E_RPC_URL} — the RPC the test verifies the signature against
 *       (default {@code CRYPTOBOT_SOLANA_DEVNET_RPC} of .env.demo, mapped to the host port when it
 *       names the {@code solana-local} container)
 *   <li>{@code CRYPTOBOT_E2E_SELL_SOL} (default 1) — how much SOL the SELL leg moves, clamped to
 *       21–40 % of the position so every rule holds whatever the balance (R_v: SOL ≤ 80 % after;
 *       ≤ $500; ≤ 50 % of the portfolio; ≤ 2 SOL per tx — so keep the wallet between 0.5 and 9 SOL)
 *   <li>{@code CRYPTOBOT_E2E_MAINNET_WALLET} — a funded, read-only mainnet address for the
 *       fail-closed check (default: the SPL Memo program, which holds SOL and no token accounts)
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EDevnetIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";

    private Map<String, String> env;
    private String baseUrl;
    private String rpcUrl;
    private String wallet;
    private String token;
    private final List<String> evidence = new ArrayList<>();

    @BeforeAll
    void stackMustBeUp() throws Exception {
        env = demoEnv();
        baseUrl = env.getOrDefault("CRYPTOBOT_E2E_BASE_URL", "http://127.0.0.1:" + env.getOrDefault("CRYPTOBOT_DEMO_PORT", "8091"));
        rpcUrl = env.getOrDefault("CRYPTOBOT_E2E_RPC_URL", hostRpc(env.getOrDefault("CRYPTOBOT_SOLANA_DEVNET_RPC", "https://api.devnet.solana.com"), env));
        wallet = env.get("DEMO_WALLET_ADDRESS");
        String secret = env.get("JWT_SECRET");
        assumeTrue(secret != null && secret.length() >= 32 && wallet != null && !wallet.isBlank(),
                "no .env.demo (run scripts/demo/wallet-devnet.sh) and no JWT_SECRET/DEMO_WALLET_ADDRESS in the environment");
        token = bearer(secret, UUID.randomUUID(), "e2e@demo.local");
        JsonNode health;
        try {
            health = get("/api/cryptobot/health", null);
        } catch (IOException | InterruptedException ex) {
            assumeTrue(false, "demo stack not reachable at " + baseUrl + ": " + ex);
            return;
        }
        assumeTrue("UP".equals(health.path("status").asText()), "demo stack not UP at " + baseUrl);
        long lamports = balance(wallet);
        if (lamports <= 100_000_000L) {
            // An assumption failure in @BeforeAll shows as "Tests run: 0" — say why on stdout too.
            System.out.println("[E2EDevnetIT] SKIPPED: wallet " + wallet + " holds " + lamports + " lamports on " + rpcUrl
                    + " — fund it (scripts/demo/wallet-devnet.sh, or https://faucet.solana.com) or use --local-validator");
        }
        assumeTrue(lamports > 100_000_000L, "wallet " + wallet + " holds " + lamports + " lamports on " + rpcUrl);
        evidence.add("baseUrl=" + baseUrl + " rpc=" + rpcUrl + " wallet=" + wallet + " balance=" + lamports);
    }

    @Test
    @DisplayName("devnet: intent → policy → approval → timelock → validator → signer → submit → confirmed → receipt, and the signature is on the chain")
    void realExecutionEndToEnd() throws Exception {
        // 0. Register the wallet the signer controls; the portfolio is valued from the real chain.
        JsonNode registered = post("/api/cryptobot/wallets", Map.of("address", wallet, "cluster", "devnet", "label", "KAN-570 e2e"), null, 201);
        String walletId = registered.path("wallet").path("id").asText();
        assertThat(registered.path("snapshot").path("totalUsd").decimalValue()).isPositive();
        JsonNode sol = positionOf(registered.path("snapshot"), "SOL");
        assertThat(sol).as("SOL position priced").isNotNull();

        // 1-3. Intent → plan → simulation on the exact bytes → 13 rules + R_v + validator identity.
        double amount = sol.path("amount").asDouble();
        double sellSol = Math.max(0.21 * amount, Math.min(Double.parseDouble(env.getOrDefault("CRYPTOBOT_E2E_SELL_SOL", "1")), 0.4 * amount));
        assertThat(sellSol).as("21 % of " + amount + " SOL must fit the 2 SOL cap: keep the demo wallet between 0.5 and 9 SOL").isLessThanOrEqualTo(2.0);
        int targetPct = (int) (sol.path("weightPct").asDouble() * (1 - sellSol / amount));
        JsonNode proposed = post("/api/cryptobot/wallets/" + walletId + "/proposals",
                Map.of("kind", "REBALANCE", "targetWeights", Map.of("SOL", targetPct), "reasoningSummary", "KAN-570 e2e devnet"), null, 201);
        JsonNode p = proposed.path("proposal");
        String proposalId = p.path("id").asText();
        assertThat(p.path("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(p.path("plan").path("legs").get(0).path("action").asText()).isEqualTo("SELL");
        assertThat(p.path("plan").path("legs").get(0).path("symbol").asText()).isEqualTo("SOL");
        assertThat(p.path("simulation").path("onchain").path("ok").asBoolean()).as("on-chain simulation").isTrue();
        long lamports = p.path("transaction").path("lamports").asLong();
        assertThat(lamports).isPositive();
        assertThat(p.path("policy").path("allowed").asBoolean()).isTrue();
        // The real signer controls this wallet and the real validator is pinned to the same H_R: executable for real.
        assertThat(p.path("policy").path("executable").asBoolean())
                .as("executable; executionViolations=" + p.path("policy").path("executionViolations")).isTrue();
        JsonNode verdict = p.path("policy").path("authorization");
        assertThat(verdict.path("decision").asText()).isIn("ALLOW", "ESCALATE");
        assertThat(verdict.path("policyHash").asText()).matches("sha256:[0-9a-f]{64}");
        String policyHash = verdict.path("policyHash").asText();

        // 4-5. execute before approval ⇒ 409 (executionPreconditions, not stubbed). Approve. Timelock by tier.
        expectStatus("POST", "/api/cryptobot/proposals/" + proposalId + "/execute", null, 409);
        JsonNode approved = post("/api/cryptobot/proposals/" + proposalId + "/approve", Map.of("note", "e2e: ok"), null, 200);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        Instant executableAt = Instant.parse(approved.path("approval").path("executableAt").asText());
        if (executableAt.isAfter(Instant.now().plusSeconds(1))) {
            JsonNode locked = request("POST", "/api/cryptobot/proposals/" + proposalId + "/execute", null, null);
            assertThat(locked.path("_status").asInt()).isEqualTo(409);
            assertThat(locked.path("message").asText()).contains("Timelock");
            evidence.add("timelock: execute inside the lock → 409 \"" + locked.path("message").asText() + "\"");
            Thread.sleep(Math.max(0, Duration.between(Instant.now(), executableAt).toMillis() + 500));
        }

        // 6-10. Execute with an Idempotency-Key: preconditions → mainnet gate → re-simulate → validator → signer → submit → confirm.
        UUID operationId = UUID.randomUUID();
        JsonNode executed = request("POST", "/api/cryptobot/proposals/" + proposalId + "/execute", null, Map.of("Idempotency-Key", operationId.toString()));
        assertThat(executed.path("_status").asInt()).as("execute: " + executed).isEqualTo(200);
        assertThat(executed.path("operationId").asText()).isEqualTo(operationId.toString());
        assertThat(executed.path("status").asText()).as("execution=" + executed.path("execution")).isIn("SUBMITTED", "EXECUTED");
        String signature = executed.path("execution").path("signature").asText();
        assertThat(signature).as("a real base58 transaction signature").matches("[1-9A-HJ-NP-Za-km-z]{86,88}");
        assertThat(executed.path("execution").path("explorerUrl").asText()).isEqualTo("https://explorer.solana.com/tx/" + signature + "?cluster=devnet");

        // The chain, asked directly (not through the service), knows this signature and it did not fail.
        JsonNode chain = waitForChain(signature);
        assertThat(chain.path("err").isNull()).as("on-chain error for " + signature + ": " + chain).isTrue();
        assertThat(chain.path("confirmationStatus").asText()).isIn("confirmed", "finalized");

        // Terminal state through the API (EXECUTED by the confirm loop, or by the reconciler if the loop gave up).
        JsonNode terminal = waitForStatus(proposalId, "EXECUTED", Duration.ofSeconds(120));
        JsonNode proposal = terminal.path("proposal");
        assertThat(proposal.path("execution").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(proposal.path("execution").path("signature").asText()).isEqualTo(signature);
        assertThat(proposal.path("execution").path("confirmationStatus").asText()).isIn("confirmed", "finalized");

        // Same key again ⇒ same row, same signature, no second transaction (KAN-403).
        JsonNode again = request("POST", "/api/cryptobot/proposals/" + proposalId + "/execute", null, Map.of("Idempotency-Key", operationId.toString()));
        assertThat(again.path("_status").asInt()).isEqualTo(200);
        assertThat(again.path("execution").path("signature").asText()).isEqualTo(signature);
        assertThat(again.path("status").asText()).isEqualTo("EXECUTED");

        // The audit trail names every stage in order; the validator's attestation and the signer's signature are in it.
        List<String> types = new ArrayList<>();
        terminal.path("audit").forEach(e -> types.add(e.path("eventType").asText()));
        assertThat(types).containsSubsequence("PROPOSAL_CREATED", "SIMULATED", "POLICY_EVALUATED", "AWAITING_APPROVAL", "APPROVED",
                "EXECUTION_STARTED", "EXECUTION_VALIDATED", "EXECUTION_SIGNED", "EXECUTION_SUBMITTED", "EXECUTED");
        JsonNode validated = eventOf(terminal.path("audit"), "EXECUTION_VALIDATED").path("payload");
        assertThat(validated.path("policyHash").asText()).isEqualTo(policyHash);
        // The validator re-derived exactly the verdict the service committed at POLICY_EVALUATED (two implementations agree).
        JsonNode policyEvent = eventOf(terminal.path("audit"), "POLICY_EVALUATED").path("payload");
        assertThat(validated.path("verdictHash").asText()).matches("sha256:[0-9a-f]{64}").isEqualTo(policyEvent.path("verdictHash").asText());
        assertThat(validated.path("inputHash").asText()).isEqualTo(policyEvent.path("inputHash").asText());
        assertThat(validated.path("attestationSignature").asText()).isNotBlank();
        assertThat(eventOf(terminal.path("audit"), "EXECUTION_SIGNED").path("payload").path("signature").asText()).isEqualTo(signature);

        // The durable event stream: trade.submitted and trade.confirmed exist, no dead letter.
        JsonNode events = get("/api/cryptobot/proposals/" + proposalId + "/events", null);
        List<String> eventTypes = new ArrayList<>();
        events.path("events").forEach(e -> eventTypes.add(e.path("eventType").asText()));
        assertThat(eventTypes).contains("trade.requested", "trade.approved", "trade.submitted", "trade.confirmed");
        assertThat(events.path("deadLetters").size()).isZero();

        // 11. The EXECUTION receipt: hash, nonce = exec:<operationId>, verify recomputes hash + signature + parents.
        JsonNode receipts = get("/api/cryptobot/proposals/" + proposalId + "/receipts", null);
        JsonNode execution = null;
        for (JsonNode r : receipts) {
            if ("EXECUTION".equals(r.path("body").path("kind").asText())) {
                execution = r;
            }
        }
        assertThat(execution).as("EXECUTION receipt among " + receipts.size()).isNotNull();
        String receiptHash = execution.path("receiptHash").asText();
        assertThat(receiptHash).matches("sha256:[0-9a-f]{64}");
        assertThat(execution.path("body").path("nonce").asText()).isEqualTo("exec:" + operationId);
        assertThat(execution.path("body").path("refs").path("proposalId").asText()).isEqualTo(proposalId);
        assertThat(execution.path("canonicalJson").asText()).doesNotContain(signature.substring(0, 20)).doesNotContain(wallet);
        JsonNode verified = post("/api/cryptobot/receipts/" + receiptHash + "/verify", Map.of(), null, 200);
        assertThat(verified.path("valid").asBoolean()).isTrue();
        assertThat(verified.path("signatureValid").asBoolean()).isTrue();
        assertThat(verified.path("parentsPresent").asBoolean()).isTrue();

        evidence.add("proposalId=" + proposalId + " operationId=" + operationId + " signature=" + signature
                + " slot=" + chain.path("slot").asText() + " confirmation=" + chain.path("confirmationStatus").asText()
                + " receipt=" + receiptHash + " lamports=" + lamports);
    }

    @Test
    @DisplayName("mainnet is fail-closed in the same run: a mainnet intent never executes (409) and is recorded as such")
    void mainnetIntentIsRefused() throws Exception {
        String mainnet = env.getOrDefault("CRYPTOBOT_E2E_MAINNET_WALLET", MEMO_PROGRAM);
        JsonNode registered = request("POST", "/api/cryptobot/wallets", Map.of("address", mainnet, "cluster", "mainnet-beta", "label", "KAN-570 read-only"), null);
        assumeTrue(registered.path("_status").asInt() == 201, "mainnet RPC/pricing not available for the read-only wallet: " + registered);
        String walletId = registered.path("wallet").path("id").asText();
        JsonNode proposed = request("POST", "/api/cryptobot/wallets/" + walletId + "/proposals",
                Map.of("kind", "REBALANCE", "targetWeights", Map.of("SOL", 70), "reasoningSummary", "KAN-570 mainnet gate"), null);
        assumeTrue(proposed.path("_status").asInt() == 201, "could not plan on the mainnet wallet: " + proposed);
        JsonNode p = proposed.path("proposal");
        String proposalId = p.path("id").asText();
        // A paper trade at most: the cluster rule and the signer rule are recorded as execution violations.
        assertThat(p.path("policy").path("executable").asBoolean()).isFalse();
        String violations = p.path("policy").path("executionViolations").toString();
        assertThat(violations).contains("EXECUTION_CLUSTER").contains("SIGNER_CONTROLS_WALLET");
        if ("AWAITING_APPROVAL".equals(p.path("status").asText())) {
            post("/api/cryptobot/proposals/" + proposalId + "/approve", Map.of("note", "e2e: mainnet gate"), null, 200);
        }
        JsonNode refused = request("POST", "/api/cryptobot/proposals/" + proposalId + "/execute", null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(refused.path("_status").asInt()).as("mainnet execute: " + refused).isEqualTo(409);
        JsonNode after = get("/api/cryptobot/proposals/" + proposalId, null).path("proposal");
        assertThat(after.path("status").asText()).isNotIn("EXECUTING", "SUBMITTED", "EXECUTED");
        assertThat(after.path("execution").isNull()).isTrue();
        evidence.add("mainnet: proposal " + proposalId + " on " + mainnet + " → execute 409 \"" + refused.path("message").asText() + "\"; violations=" + violations);
    }

    @org.junit.jupiter.api.AfterAll
    void printEvidence() throws IOException {
        Path out = Path.of(env.getOrDefault("CRYPTOBOT_E2E_EVIDENCE", "target/e2e-devnet-evidence.txt"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, evidence, StandardCharsets.UTF_8);
        evidence.forEach(line -> System.out.println("[E2EDevnetIT] " + line));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** .env.demo (next to the pom) merged under the process environment; the environment wins. */
    private static Map<String, String> demoEnv() throws IOException {
        Map<String, String> m = new HashMap<>();
        Path envFile = Path.of(System.getProperty("cryptobot.e2e.envFile", ".env.demo"));
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (t.isEmpty() || t.startsWith("#") || eq < 1) {
                    continue;
                }
                m.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        m.putAll(System.getenv());
        return m;
    }

    /** The compose-internal RPC name → the port published on the host (plan B: local validator). */
    private static String hostRpc(String rpc, Map<String, String> env) {
        if (rpc.contains("solana-local")) {
            return "http://127.0.0.1:" + env.getOrDefault("SOLANA_LOCAL_PORT", "8999");
        }
        return rpc;
    }

    private static String bearer(String secret, UUID userId, String email) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("authorities", List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private JsonNode get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        JsonNode r = request("GET", path, null, headers);
        assertThat(r.path("_status").asInt()).as("GET " + path + ": " + r).isEqualTo(200);
        return r.path("_body").isMissingNode() ? r : r.path("_body");
    }

    private JsonNode post(String path, Object body, Map<String, String> headers, int expected) throws IOException, InterruptedException {
        JsonNode r = request("POST", path, body, headers);
        assertThat(r.path("_status").asInt()).as("POST " + path + ": " + r).isEqualTo(expected);
        return r;
    }

    private void expectStatus(String method, String path, Object body, int expected) throws IOException, InterruptedException {
        JsonNode r = request(method, path, body, null);
        assertThat(r.path("_status").asInt()).as(method + " " + path + ": " + r).isEqualTo(expected);
    }

    /** The response body as JSON with {@code _status} added; a JSON array is wrapped as {@code _body}. */
    private JsonNode request(String method, String path, Object body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", token).header("Accept", "application/json");
        if (headers != null) {
            headers.forEach(b::header);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = resp.body() == null || resp.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(resp.body());
        com.fasterxml.jackson.databind.node.ObjectNode out = parsed.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                : JSON.createObjectNode().set("_body", parsed);
        out.put("_status", resp.statusCode());
        return out;
    }

    private JsonNode waitForStatus(String proposalId, String status, Duration max) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(max);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = get("/api/cryptobot/proposals/" + proposalId, null);
            if (status.equals(last.path("proposal").path("status").asText())) {
                return last;
            }
            assertThat(last.path("proposal").path("status").asText()).as("proposal " + proposalId + " failed: " + last.path("proposal").path("execution")).isNotEqualTo("FAILED");
            Thread.sleep(2000);
        }
        throw new AssertionError("proposal " + proposalId + " did not reach " + status + " within " + max + "; last=" + (last == null ? null : last.path("proposal").path("status")));
    }

    private static JsonNode eventOf(JsonNode audit, String type) {
        for (JsonNode e : audit) {
            if (type.equals(e.path("eventType").asText())) {
                return e;
            }
        }
        throw new AssertionError("no audit event " + type);
    }

    private static JsonNode positionOf(JsonNode snapshot, String symbol) {
        for (JsonNode pos : snapshot.path("positions")) {
            if (symbol.equals(pos.path("symbol").asText()) && !pos.path("priceUsd").isNull()) {
                return pos;
            }
        }
        return null;
    }

    // ---- the chain, asked directly --------------------------------------------------------

    private JsonNode rpc(String method, Object params) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(rpcUrl)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))))
                .build();
        return JSON.readTree(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private long balance(String address) throws IOException, InterruptedException {
        return rpc("getBalance", List.of(address)).path("result").path("value").asLong(0);
    }

    private JsonNode waitForChain(String signature) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(90);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = rpc("getSignatureStatuses", List.of(List.of(signature), Map.of("searchTransactionHistory", true))).path("result").path("value").get(0);
            if (last != null && !last.isNull() && !last.path("confirmationStatus").asText("").isBlank()) {
                return last;
            }
            Thread.sleep(1500);
        }
        throw new AssertionError("signature " + signature + " never seen on " + rpcUrl + "; last=" + last);
    }
}
