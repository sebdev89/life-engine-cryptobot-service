package io.lifeengine.cryptobot.e2e.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.application.reliability.ReconciliationService;
import io.lifeengine.cryptobot.core.intent.IntentHash;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-500 (CB-03/09) — <b>one</b> test that walks the whole chain with the gates and the pipeline
 * together, nothing stubbed inside the service:
 *
 * <pre>
 * intent → risk → policy (13 rules + R_v, H_R) → approval → executionPreconditions (timelock) →
 * mainnet gate → re-simulation → independent validator (real process, own copy of R_v pinned by H_R)
 * → isolated signer (real process, refuses without the attestation) → sendTransaction → confirm →
 * EXECUTED → EXECUTION receipt → reconciliation
 * </pre>
 *
 * <ul>
 *   <li>the service is the real Spring context on the real R2DBC stores, Flyway-migrated into a
 *       Postgres started by Testcontainers ({@code postgres:16-alpine}) — or the one named by
 *       {@code CRYPTOBOT_IT_PG_HOST} (dev box, scratch schema {@code kan500_e2e});
 *   <li>{@code cryptobot-validator} and {@code cryptobot-signer} are <em>processes</em>
 *       ({@code java -jar} of {@code validator/target} and {@code signer/target}, built first), each
 *       with its own key generated for the run: the signer holds the wallet key, the validator the
 *       attestation key the signer pins;
 *   <li>the Solana RPC and the Runtime are HTTP mocks; the RPC behaves like a node: the signature it
 *       returns is the one inside the bytes it received, and it only confirms what it was sent;
 *   <li>the validator and the signer are reached through relays so a scenario can take the validator
 *       down or corrupt one attestation on the wire without touching the processes.
 * </ul>
 *
 * Runs only under the Maven profile {@code e2e-chain} (Failsafe): {@code ./mvnw -Pe2e-chain verify}
 * after {@code ./mvnw -f signer/pom.xml -DskipTests package} and the same for {@code validator}.
 */
@SpringBootTest(classes = CryptobotServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "120s")
@ActiveProfiles("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChainE2EIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EVIDENCE = new ArrayList<>();

    /** Everything outside the service: DB, keys, processes, relays, mocks. Started once, before the context. */
    private static ChainStack stack;

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry meters;
    @Autowired private DatabaseClient db;
    @Autowired private ActionProposalRepository proposals;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private io.lifeengine.cryptobot.application.reliability.OutboxPublisher outboxPublisher;

    private final UUID user = UUID.randomUUID();
    private String token;
    private String walletId;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        stack = ChainStack.start();
        r.add("spring.r2dbc.url", () -> stack.r2dbcUrl);
        r.add("spring.r2dbc.username", () -> stack.dbUser);
        r.add("spring.r2dbc.password", () -> stack.dbPassword);
        r.add("spring.flyway.url", () -> stack.jdbcUrl);
        r.add("spring.flyway.user", () -> stack.dbUser);
        r.add("spring.flyway.password", () -> stack.dbPassword);
        r.add("spring.flyway.schemas", () -> stack.schema);
        r.add("spring.flyway.default-schema", () -> stack.schema);
        r.add("cryptobot.solana.rpc.devnet-url", () -> stack.baseUrl(stack.rpcServer));
        r.add("cryptobot.solana.rpc.mainnet-url", () -> stack.baseUrl(stack.rpcServer));
        r.add("cryptobot.runtime.base-url", () -> stack.baseUrl(stack.runtimeServer));
        // KAN-439/KAN-500: quorum 2 against the fixed fake feed (SOL $100 / USDC $1) — without this the
        // real Pyth/CoinGecko/Coinbase endpoints answer with the live price and `lamports` drifts.
        r.add("cryptobot.marketdata.pyth.enabled", () -> "true");
        r.add("cryptobot.marketdata.pyth.base-url", () -> stack.baseUrl(stack.pricesServer));
        r.add("cryptobot.marketdata.coingecko.enabled", () -> "true");
        r.add("cryptobot.marketdata.coingecko.base-url", () -> stack.baseUrl(stack.pricesServer));
        r.add("cryptobot.signer.base-url", () -> stack.baseUrl(stack.signerRelayServer));
        r.add("cryptobot.signer.token", () -> stack.signerToken);
        r.add("cryptobot.validator.base-url", () -> stack.baseUrl(stack.validatorRelayServer));
        r.add("cryptobot.validator.token", () -> stack.validatorToken);
        r.add("lifeengine.security.jwt.secret", () -> stack.jwtSecret);
    }

    @AfterAll
    void tearDown() throws Exception {
        Path out = Path.of(System.getProperty("basedir", "."), "target", "e2e-chain", "evidence.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, EVIDENCE, StandardCharsets.UTF_8);
        EVIDENCE.forEach(line -> System.out.println("[ChainE2EIT] " + line));
        if (stack != null) {
            stack.close();
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("the whole chain once: intent → risk → policy → approval → timelock(409) → validator → signer → submit → confirm → receipt → reconcile")
    void wholeChainOnce() throws Exception {
        token = bearer(user);
        int sends0 = stack.rpc.sends();

        // 0. The wallet the signer controls: valued from the (mock) chain, 7 SOL @ $100 + 300 USDC ⇒ SOL 70 % ⇒ HIGH.
        JsonNode created = json(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"address\":\"" + stack.walletAddress + "\",\"cluster\":\"devnet\",\"label\":\"KAN-500 e2e\"}")
                .exchange().expectStatus().isCreated());
        walletId = created.path("wallet").path("id").asText();
        assertThat(created.path("risk").path("overall").asText()).isEqualTo("HIGH");

        // 1. Intent: the advisor's run (mock Runtime) is the run the proposal — and its EXECUTION receipt — will name.
        JsonNode asked = json(web.post().uri("/api/cryptobot/wallets/" + walletId + "/ask").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"What is my biggest risk?\"}")
                .exchange().expectStatus().isOk());
        String runtimeRunId = asked.path("runtimeRunId").asText();
        assertThat(runtimeRunId).isNotBlank();

        // 2-3. Plan → simulation on the exact bytes → policy: executable for real (signer controls the wallet, validator holds H_R).
        JsonNode proposed = json(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50},\"reasoningSummary\":\"KAN-500 e2e\",\"runtimeRunId\":\"" + runtimeRunId + "\"}")
                .exchange().expectStatus().isCreated());
        JsonNode p = proposed.path("proposal");
        String proposalId = p.path("id").asText();
        assertThat(p.path("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(p.path("simulation").path("onchain").path("ok").asBoolean()).isTrue();
        assertThat(p.path("policy").path("allowed").asBoolean()).isTrue();
        assertThat(p.path("policy").path("executable").asBoolean())
                .as("executable; executionViolations=" + p.path("policy").path("executionViolations")).isTrue();
        assertThat(p.path("policy").path("rulesApplied").toString()).contains("VALIDATOR_AVAILABLE").contains("SIGNER_CONTROLS_WALLET");
        JsonNode verdict = p.path("policy").path("authorization");
        assertThat(verdict.path("decision").asText()).isEqualTo("ESCALATE");
        assertThat(verdict.path("policyHash").asText()).isEqualTo(stack.policyHash);
        long lamports = p.path("transaction").path("lamports").asLong();
        assertThat(lamports).isEqualTo(2_000_000_000L);

        // 4. Execute before approval ⇒ 409 (executionPreconditions, real).
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);

        // 5. Approve ⇒ ESCALATE tier ⇒ the escalated timelock (3 s) starts. Inside it: 409, nothing signed, nothing sent.
        JsonNode approved = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/approve").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"e2e: ok\"}").exchange().expectStatus().isOk());
        Instant executableAt = Instant.parse(approved.path("approval").path("executableAt").asText());
        assertThat(executableAt).isAfter(Instant.now());
        IntentHash intentHash = IntentHash.of(("kan-500 intent " + proposalId).getBytes(StandardCharsets.UTF_8));
        JsonNode locked = web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", intentHash.value())
                .exchange().expectStatus().isEqualTo(409).expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(locked.path("message").asText()).contains("Timelock");
        assertThat(stack.rpc.sends()).isEqualTo(sends0);
        assertThat(stack.signerRelay.signCalls()).isZero();
        EVIDENCE.add("timelock: execute inside the lock → 409 \"" + locked.path("message").asText() + "\"");
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), executableAt).toMillis() + 300));

        // 6-10. Execute with the intent hash as the key: preconditions → mainnet gate → re-simulate → validator → signer → submit → confirm.
        JsonNode executed = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", intentHash.value()).exchange().expectStatus().isOk());
        assertThat(executed.path("status").asText()).as("execution=" + executed.path("execution")).isEqualTo("EXECUTED");
        assertThat(executed.path("operationId").asText()).isEqualTo(intentHash.toOperationId().toString());
        assertThat(executed.path("intentHash").asText()).isEqualTo(intentHash.value());
        String signature = executed.path("execution").path("signature").asText();
        assertThat(signature).matches("[1-9A-HJ-NP-Za-km-z]{86,88}");
        assertThat(executed.path("execution").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(executed.path("execution").path("confirmationStatus").asText()).isEqualTo("confirmed");
        assertThat(executed.path("execution").path("signerPublicKey").asText()).isEqualTo(stack.walletAddress);

        // Exactly one sendTransaction, and the bytes the "chain" received are signed by the wallet key the signer holds.
        assertThat(stack.rpc.sends()).isEqualTo(sends0 + 1);
        assertThat(stack.signerRelay.signCalls()).isEqualTo(1);
        assertThat(stack.validatorRelay.validateCalls()).isEqualTo(1);
        byte[] wire = Base64.getDecoder().decode(stack.rpc.lastSent());
        assertThat(wire[0]).isEqualTo((byte) 1);
        byte[] sig = java.util.Arrays.copyOfRange(wire, 1, 65);
        byte[] message = java.util.Arrays.copyOfRange(wire, 65, wire.length);
        assertThat(Base58.encode(sig)).as("the row's signature IS the signature inside the broadcast bytes").isEqualTo(signature);
        assertThat(SolanaKeypair.verify(Base58.decode(stack.walletAddress), message, sig)).as("signed by the wallet key").isTrue();

        // The columns (KAN-500): intent_hash and execution_signature, next to the operation they belong to.
        Map<String, Object> row = db.sql("SELECT operation_id, intent_hash, execution_signature, status FROM action_proposal WHERE id = :id")
                .bind("id", UUID.fromString(proposalId)).fetch().one().block();
        assertThat(row).isNotNull();
        assertThat(row.get("intent_hash")).isEqualTo(intentHash.value());
        assertThat(row.get("execution_signature")).isEqualTo(signature);
        assertThat(row.get("operation_id").toString()).isEqualTo(intentHash.toOperationId().toString());
        assertThat(row.get("status")).isEqualTo("EXECUTED");

        // The audit trail names every stage, in order, with the validator's hashes and attestation and the signer's signature.
        JsonNode detail = json(web.get().uri("/api/cryptobot/proposals/" + proposalId).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        List<String> types = new ArrayList<>();
        detail.path("audit").forEach(e -> types.add(e.path("eventType").asText()));
        assertThat(types).containsSubsequence("PROPOSAL_CREATED", "SIMULATED", "POLICY_EVALUATED", "AWAITING_APPROVAL", "APPROVED",
                "EXECUTION_STARTED", "EXECUTION_VALIDATED", "EXECUTION_SIGNED", "EXECUTION_SUBMITTED", "EXECUTED");
        JsonNode started = eventOf(detail.path("audit"), "EXECUTION_STARTED").path("payload");
        assertThat(started.path("intentHash").asText()).isEqualTo(intentHash.value());
        JsonNode policyEvent = eventOf(detail.path("audit"), "POLICY_EVALUATED").path("payload");
        JsonNode validated = eventOf(detail.path("audit"), "EXECUTION_VALIDATED").path("payload");
        assertThat(validated.path("policyHash").asText()).isEqualTo(stack.policyHash);
        assertThat(validated.path("verdictHash").asText()).matches("sha256:[0-9a-f]{64}").isEqualTo(policyEvent.path("verdictHash").asText());
        assertThat(validated.path("inputHash").asText()).isEqualTo(policyEvent.path("inputHash").asText());
        assertThat(validated.path("validator").asText()).isEqualTo(stack.validatorPublicKey);
        assertThat(validated.path("attestationSignature").asText()).isNotBlank();
        assertThat(eventOf(detail.path("audit"), "EXECUTION_SIGNED").path("payload").path("signature").asText()).isEqualTo(signature);

        // The durable event stream: trade.submitted + trade.confirmed written with the state; the publisher hands them out; no dead letter.
        JsonNode events = json(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/events").header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        List<String> eventTypes = new ArrayList<>();
        events.path("events").forEach(e -> eventTypes.add(e.path("eventType").asText()));
        assertThat(eventTypes).containsExactly("trade.requested", "trade.approved", "trade.submitted", "trade.confirmed");
        assertThat(events.path("deadLetters").size()).isZero();
        assertThat(outboxPublisher.tick().block()).isEqualTo(4);

        // 11. The EXECUTION receipt: anchorable (hash + signature verify), nonce = exec:<operationId>, runtime.runId = the advisor's run.
        JsonNode receipts = json(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/receipts").header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        JsonNode execution = null;
        for (JsonNode r : receipts) {
            if ("EXECUTION".equals(r.path("body").path("kind").asText())) {
                execution = r;
            }
        }
        assertThat(execution).as("EXECUTION receipt among " + receipts.size()).isNotNull();
        String receiptHash = execution.path("receiptHash").asText();
        assertThat(receiptHash).matches("sha256:[0-9a-f]{64}");
        assertThat(execution.path("body").path("nonce").asText()).isEqualTo("exec:" + intentHash.toOperationId());
        assertThat(execution.path("body").path("runtime").path("runId").asText()).as("KAN-500: runtime.runId on the EXECUTION receipt").isEqualTo(runtimeRunId);
        assertThat(execution.path("body").path("refs").path("proposalId").asText()).isEqualTo(proposalId);
        assertThat(execution.path("body").path("parents").size()).as("SIMULATION + STRATEGY parents").isEqualTo(2);
        assertThat(execution.path("canonicalJson").asText()).doesNotContain(signature).doesNotContain(stack.walletAddress);
        JsonNode verified = json(web.post().uri("/api/cryptobot/receipts/" + receiptHash + "/verify").header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        assertThat(verified.path("valid").asBoolean()).isTrue();
        assertThat(verified.path("signatureValid").asBoolean()).isTrue();
        assertThat(verified.path("parentsPresent").asBoolean()).isTrue();

        // 12. Reconciliation: the row is terminal and consistent with the chain — the sweep touches nothing, no mismatch, nothing re-sent.
        double mismatch0 = counter("reconciliation.mismatch");
        assertThat(reconciliation.sweep().block()).isZero();
        ActionProposal stored = proposals.findByIdAndOwner(UUID.fromString(proposalId), user).block();
        assertThat(reconciliation.reconcile(stored).block()).isEqualTo(ReconciliationService.Result.SKIPPED);
        assertThat(counter("reconciliation.mismatch")).isEqualTo(mismatch0);
        assertThat(stack.rpc.sends()).isEqualTo(sends0 + 1);
        assertThat(stack.rpc.statusOf(signature)).isEqualTo("confirmed");

        // 13. Replaying the same intent hash ⇒ same row, same signature, no second transaction.
        JsonNode again = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", intentHash.value()).exchange().expectStatus().isOk());
        assertThat(again.path("execution").path("signature").asText()).isEqualTo(signature);
        assertThat(stack.rpc.sends()).isEqualTo(sends0 + 1);
        assertThat(counter("duplicate.trade.suppressed")).isGreaterThanOrEqualTo(1);

        EVIDENCE.add("executed: proposalId=" + proposalId + " operationId=" + intentHash.toOperationId() + " intentHash=" + intentHash
                + " signature=" + signature + " policyHash=" + stack.policyHash + " verdictHash=" + validated.path("verdictHash").asText()
                + " validator=" + stack.validatorPublicKey + " receipt=" + receiptHash + " runtimeRunId=" + runtimeRunId + " lamports=" + lamports);
    }

    @Test
    @Order(2)
    @DisplayName("validator down at execution time: FAILED at VALIDATE, the signer is never asked, nothing is sent")
    void validatorDownFailsClosed() throws Exception {
        String proposalId = approvedAndUnlocked("KAN-500 e2e validator down");
        int sends0 = stack.rpc.sends();
        int signs0 = stack.signerRelay.signCalls();
        stack.validatorRelay.mode = Relay.Mode.DOWN;
        try {
            JsonNode executed = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).exchange().expectStatus().isOk());
            assertThat(executed.path("status").asText()).isEqualTo("FAILED");
            assertThat(executed.path("execution").path("status").asText()).isEqualTo("FAILED");
            assertThat(executed.path("execution").path("signature").isNull()).isTrue();
            assertThat(executed.path("execution").path("error").asText()).contains("Validator refused");
        } finally {
            stack.validatorRelay.mode = Relay.Mode.NORMAL;
        }
        assertThat(stack.rpc.sends()).isEqualTo(sends0);
        assertThat(stack.signerRelay.signCalls()).isEqualTo(signs0);
        JsonNode detail = json(web.get().uri("/api/cryptobot/proposals/" + proposalId).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        List<String> types = new ArrayList<>();
        detail.path("audit").forEach(e -> types.add(e.path("eventType").asText()));
        assertThat(types).containsSubsequence("APPROVED", "EXECUTION_STARTED", "EXECUTION_FAILED")
                .doesNotContain("EXECUTION_VALIDATED", "EXECUTION_SIGNED", "EXECUTION_SUBMITTED");
        JsonNode failed = eventOf(detail.path("audit"), "EXECUTION_FAILED").path("payload");
        assertThat(failed.path("stage").asText()).isEqualTo("VALIDATE");
        Map<String, Object> row = db.sql("SELECT execution_signature, status FROM action_proposal WHERE id = :id")
                .bind("id", UUID.fromString(proposalId)).fetch().one().block();
        assertThat(row.get("execution_signature")).isNull();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(counter("trade.failed", "stage", "validate", "asset", "SOL")).isGreaterThanOrEqualTo(1);
        EVIDENCE.add("validator down: proposalId=" + proposalId + " → FAILED stage=VALIDATE error=\"" + failed.path("error").asText() + "\" sends=" + (stack.rpc.sends() - sends0));
    }

    @Test
    @Order(3)
    @DisplayName("attestation corrupted on the wire: the real signer refuses (attestation_bad_signature) ⇒ FAILED at SIGN, nothing is sent")
    void invalidAttestationIsRefusedByTheSigner() throws Exception {
        String proposalId = approvedAndUnlocked("KAN-500 e2e bad attestation");
        int sends0 = stack.rpc.sends();
        int signs0 = stack.signerRelay.signCalls();
        stack.validatorRelay.mode = Relay.Mode.TAMPER_ATTESTATION;
        try {
            JsonNode executed = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).exchange().expectStatus().isOk());
            assertThat(executed.path("status").asText()).isEqualTo("FAILED");
            assertThat(executed.path("execution").path("signature").isNull()).isTrue();
            assertThat(executed.path("execution").path("error").asText()).contains("Signer refused").contains("attestation_bad_signature");
        } finally {
            stack.validatorRelay.mode = Relay.Mode.NORMAL;
        }
        assertThat(stack.rpc.sends()).isEqualTo(sends0);
        assertThat(stack.signerRelay.signCalls()).as("the signer was asked once and said no").isEqualTo(signs0 + 1);
        JsonNode detail = json(web.get().uri("/api/cryptobot/proposals/" + proposalId).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
        List<String> types = new ArrayList<>();
        detail.path("audit").forEach(e -> types.add(e.path("eventType").asText()));
        assertThat(types).containsSubsequence("EXECUTION_STARTED", "EXECUTION_FAILED").doesNotContain("EXECUTION_SIGNED", "EXECUTION_SUBMITTED");
        JsonNode failed = eventOf(detail.path("audit"), "EXECUTION_FAILED").path("payload");
        assertThat(failed.path("stage").asText()).isEqualTo("SIGN");
        assertThat(counter("validator.attestations", "result", "issued")).isGreaterThanOrEqualTo(2); // the validator did attest; the signer caught the forgery
        EVIDENCE.add("bad attestation: proposalId=" + proposalId + " → FAILED stage=SIGN error=\"" + failed.path("error").asText() + "\" sends=" + (stack.rpc.sends() - sends0));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A fresh proposal on the wallet, approved, with its timelock waited out. */
    private String approvedAndUnlocked(String reason) throws Exception {
        JsonNode proposed = json(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50},\"reasoningSummary\":\"" + reason + "\"}")
                .exchange().expectStatus().isCreated());
        JsonNode p = proposed.path("proposal");
        assertThat(p.path("status").asText()).as("policy=" + p.path("policy")).isEqualTo("AWAITING_APPROVAL");
        assertThat(p.path("policy").path("executable").asBoolean()).as("executionViolations=" + p.path("policy").path("executionViolations")).isTrue();
        String proposalId = p.path("id").asText();
        JsonNode approved = json(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/approve").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"e2e: ok\"}").exchange().expectStatus().isOk());
        Instant executableAt = Instant.parse(approved.path("approval").path("executableAt").asText());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), executableAt).toMillis() + 300));
        return proposalId;
    }

    private double counter(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private static JsonNode json(WebTestClient.ResponseSpec spec) throws Exception {
        return JSON.readTree(spec.expectBody().returnResult().getResponseBody());
    }

    private static JsonNode eventOf(JsonNode audit, String type) {
        for (JsonNode e : audit) {
            if (type.equals(e.path("eventType").asText())) {
                return e;
            }
        }
        throw new AssertionError("no audit event " + type + " in " + audit);
    }

    private static String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(stack.jwtSecret.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", "operator@e2e.local")
                .claim("authorities", List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
