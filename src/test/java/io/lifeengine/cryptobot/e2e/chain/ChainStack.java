package io.lifeengine.cryptobot.e2e.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Everything the service talks to in {@link ChainE2EIT}, started once before the Spring context:
 * a Postgres (Testcontainers, or the one named by {@code CRYPTOBOT_IT_PG_HOST}), the validator and
 * the signer as real processes with keys generated for this run, a relay in front of each, a mock
 * Solana RPC that behaves like a node for the bytes it receives, and a mock Runtime for the advisor.
 * Closed at the end of the class; a shutdown hook kills the processes if the JVM dies first.
 */
final class ChainStack implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";
    /** Same R_v as application-e2e.yml (the service's test-policy-v1 in USD) in the validator's integer units. */
    static final PolicyRules POLICY = new PolicyRules("test-policy-v1", List.of("SOL", "USDC", "USDT"), List.of("REBALANCE"),
            50_000, 250_000, 8_000, 100, 900, 10_000, 25_000);

    final Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
    final Path logs = basedir.resolve("target").resolve("e2e-chain");

    // ---- database ----
    PostgreSQLContainer<?> postgres;
    String jdbcUrl;
    String r2dbcUrl;
    String dbUser;
    String dbPassword;
    String schema;

    // ---- keys and tokens of this run ----
    final SolanaKeypair walletKey = SolanaKeypair.generate();
    final SolanaKeypair validatorKey = SolanaKeypair.generate();
    final String walletAddress = walletKey.publicKeyBase58();
    final String validatorPublicKey = validatorKey.publicKeyBase58();
    final String signerToken = randomToken();
    final String validatorToken = randomToken();
    final String jwtSecret = randomToken() + randomToken();
    final String policyHash = POLICY.hash();

    // ---- processes and relays ----
    Process validator;
    Process signer;
    int validatorPort;
    int signerPort;
    MockWebServer validatorRelayServer;
    MockWebServer signerRelayServer;
    Relay validatorRelay;
    Relay signerRelay;

    // ---- mocks ----
    MockWebServer rpcServer;
    MockWebServer runtimeServer;
    SolanaNode rpc;

    static ChainStack start() {
        ChainStack s = new ChainStack();
        try {
            s.startAll();
            return s;
        } catch (Exception e) {
            s.close();
            throw new IllegalStateException("ChainE2EIT stack did not start: " + e.getMessage(), e);
        }
    }

    private void startAll() throws Exception {
        Files.createDirectories(logs);
        database();

        // The validator first: the signer pins its public key (known already — the key is ours — but the
        // process must answer /identity before the service is allowed to call it).
        validatorPort = freePort();
        validator = launch("validator", jar("validator", "cryptobot-validator"), validatorPort, Map.of(
                "VALIDATOR_PORT", String.valueOf(validatorPort),
                "VALIDATOR_KEYPAIR_JSON", keypairJson(validatorKey),
                "VALIDATOR_TOKEN", validatorToken,
                "VALIDATOR_POLICY_VERSION", POLICY.version(),
                // Pinned: the process refuses to start if its R_v hashes to anything else (paper §17).
                "VALIDATOR_POLICY_HASH", policyHash,
                "VALIDATOR_ENABLED", "true"));

        signerPort = freePort();
        signer = launch("signer", jar("signer", "cryptobot-signer"), signerPort, Map.of(
                "SIGNER_PORT", String.valueOf(signerPort),
                "SIGNER_KEYPAIR_JSON", keypairJson(walletKey),
                "SIGNER_TOKEN", signerToken,
                "SIGNER_CLUSTER", "devnet",
                "SIGNER_ALLOWED_DESTINATIONS", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
                "SIGNER_MAX_LAMPORTS", "2000000000",
                "SIGNER_VALIDATOR_PUBLIC_KEY", validatorPublicKey,
                "SIGNER_REQUIRE_ATTESTATION", "true",
                "SIGNER_ALLOW_MAINNET", "false",
                "SIGNER_ENABLED", "true"));

        validatorRelay = new Relay("http://127.0.0.1:" + validatorPort);
        validatorRelayServer = new MockWebServer();
        validatorRelayServer.setDispatcher(validatorRelay);
        validatorRelayServer.start();
        signerRelay = new Relay("http://127.0.0.1:" + signerPort);
        signerRelayServer = new MockWebServer();
        signerRelayServer.setDispatcher(signerRelay);
        signerRelayServer.start();

        rpc = new SolanaNode();
        rpcServer = new MockWebServer();
        rpcServer.setDispatcher(rpc);
        rpcServer.start();
        runtimeServer = new MockWebServer();
        runtimeServer.setDispatcher(new RuntimeDispatcher());
        runtimeServer.start();

        // Both processes really answer with the identities the service will check.
        JsonNode v = JSON.readTree(get("http://127.0.0.1:" + validatorPort + "/api/validator/identity", "X-Validator-Token", validatorToken));
        if (!policyHash.equals(v.path("policyHash").asText()) || !v.path("pinned").asBoolean() || !validatorPublicKey.equals(v.path("publicKey").asText())) {
            throw new IllegalStateException("validator identity is not the one this run generated: " + v);
        }
        JsonNode s = JSON.readTree(get("http://127.0.0.1:" + signerPort + "/api/signer/identity", "X-Signer-Token", signerToken));
        if (!walletAddress.equals(s.path("publicKey").asText()) || !s.path("attestationRequired").asBoolean()) {
            throw new IllegalStateException("signer identity is not the one this run generated: " + s);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::killProcesses));
    }

    /** {@code http://127.0.0.1:<port>}, no trailing slash — the shape the service's base-url properties expect. */
    String baseUrl(MockWebServer server) {
        return server.url("/").toString().replaceAll("/+$", "");
    }

    // ---- database ------------------------------------------------------------------------------

    private void database() {
        String host = System.getenv("CRYPTOBOT_IT_PG_HOST");
        if (host != null && !host.isBlank()) {
            int port = Integer.parseInt(System.getenv().getOrDefault("CRYPTOBOT_IT_PG_PORT", "5433"));
            String database = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_DB", "life_engine_cryptobot");
            dbUser = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_USER", "life_engine");
            dbPassword = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_PASSWORD", "life_engine");
            schema = "kan500_e2e";
            jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?currentSchema=" + schema;
            r2dbcUrl = "r2dbc:postgresql://" + host + ":" + port + "/" + database + "?options=search_path=" + schema;
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("life_engine_cryptobot").withUsername("cryptobot").withPassword("cryptobot");
        postgres.start();
        dbUser = postgres.getUsername();
        dbPassword = postgres.getPassword();
        schema = "public";
        jdbcUrl = postgres.getJdbcUrl();
        r2dbcUrl = "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName();
    }

    // ---- processes -----------------------------------------------------------------------------

    private Path jar(String module, String artifact) throws IOException {
        Path target = basedir.resolve(module).resolve("target");
        try (Stream<Path> files = Files.exists(target) ? Files.list(target) : Stream.empty()) {
            return files.filter(f -> f.getFileName().toString().startsWith(artifact + "-") && f.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no " + artifact + " jar in " + target
                            + " — build the module first: ./mvnw -f " + module + "/pom.xml -DskipTests package"));
        }
    }

    private Process launch(String name, Path jar, int port, Map<String, String> env) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder pb = new ProcessBuilder(javaBin.toString(), "-Xmx256m", "-XX:TieredStopAtLevel=1", "-jar", jar.toString());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Path log = logs.resolve(name + ".log");
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        String health = "http://127.0.0.1:" + port + "/actuator/health";
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            if (!p.isAlive()) {
                throw new IllegalStateException(name + " exited with " + p.exitValue() + " before becoming healthy; see " + log);
            }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(health)).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 && r.body().contains("UP")) {
                    return p;
                }
            } catch (IOException ignored) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        p.destroyForcibly();
        throw new IllegalStateException(name + " did not become healthy on " + health + " within 120 s; see " + log);
    }

    private void killProcesses() {
        for (Process p : new Process[] {signer, validator}) {
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
        for (Process p : new Process[] {signer, validator}) {
            if (p != null) {
                try {
                    if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
        }
    }

    @Override
    public void close() {
        killProcesses();
        for (MockWebServer s : new MockWebServer[] {validatorRelayServer, signerRelayServer, rpcServer, runtimeServer}) {
            if (s != null) {
                try {
                    s.shutdown();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String get(String url, String header, String value) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header(header, value).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " → " + r.statusCode() + " " + r.body());
        }
        return r.body();
    }

    /** solana-keygen layout: a JSON array of the 64 secret-key bytes. Only ever handed to the process environment. */
    private static String keypairJson(SolanaKeypair k) {
        byte[] secret = k.secretKey();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < secret.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(secret[i] & 0xff);
        }
        return sb.append(']').toString();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ---- the mock chain ------------------------------------------------------------------------

    /**
     * A Solana node for the bytes it is given: {@code sendTransaction} answers with the signature
     * <em>inside</em> the transaction (base58 of bytes 1..65, exactly what a node does), and
     * {@code getSignatureStatuses} knows only what was sent — {@code processed} on the first poll,
     * {@code confirmed} afterwards, {@code null} for anything never broadcast. Balances are fixed:
     * 7 SOL + 300 USDC at $100/SOL ⇒ $1000, SOL 70 %.
     */
    static final class SolanaNode extends Dispatcher {

        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> polls = new ConcurrentHashMap<>();

        int sends() {
            return sent.size();
        }

        String lastSent() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }

        /** {@code confirmed} | {@code processed} | {@code null} — what the node would answer now for that signature. */
        String statusOf(String signature) {
            Integer n = polls.get(signature);
            return n == null ? null : n > 1 ? "confirmed" : "processed";
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
                    case "getBlockHeight" -> "900";
                    case "simulateTransaction" -> "{\"context\":{\"slot\":1},\"value\":{\"err\":null,\"logs\":[\"Program 11111111111111111111111111111111 invoke [1]\",\"Program 11111111111111111111111111111111 success\"],\"unitsConsumed\":150}}";
                    case "sendTransaction" -> {
                        String signedBase64 = body.path("params").get(0).asText();
                        byte[] wire = Base64.getDecoder().decode(signedBase64);
                        if (wire.length < 65 || wire[0] != 1) {
                            yield null; // a node would answer an error; the test asserts it never gets here
                        }
                        String signature = Base58.encode(Arrays.copyOfRange(wire, 1, 65));
                        sent.add(signedBase64);
                        polls.put(signature, 0);
                        yield "\"" + signature + "\"";
                    }
                    case "getSignatureStatuses" -> {
                        String signature = body.path("params").get(0).get(0).asText();
                        Integer n = polls.computeIfPresent(signature, (k, c) -> c + 1);
                        yield n == null ? "{\"context\":{\"slot\":1},\"value\":[null]}"
                                : "{\"context\":{\"slot\":1},\"value\":[{\"slot\":" + (100 + n) + ",\"confirmations\":" + n + ",\"err\":null,\"confirmationStatus\":\""
                                        + (n > 1 ? "confirmed" : "processed") + "\"}]}";
                    }
                    default -> "null";
                };
                if (result == null) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + body.path("id").asLong() + ",\"error\":{\"code\":-32602,\"message\":\"invalid transaction\"}}");
                }
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + body.path("id").asLong() + ",\"result\":" + result + "}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }
    }

    /** The Runtime that answers the advisor: one run, SUCCEEDED, the contract JSON, so the proposal carries a runtimeRunId. */
    static final class RuntimeDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if ("POST".equals(request.getMethod()) && "/api/runtime/runs".equals(path)) {
                UUID runId = UUID.randomUUID();
                return json("{\"runId\":\"" + runId + "\",\"workflowId\":\"crypto.portfolio-advisor.v1\",\"correlationId\":\"c\",\"status\":\"RUNNING\"}");
            }
            if ("GET".equals(request.getMethod()) && path != null && path.startsWith("/api/runtime/runs/")) {
                String runId = path.substring("/api/runtime/runs/".length());
                String output = "{\\\"answer\\\":\\\"SOL is 70% of your wallet; one drawdown moves everything.\\\",\\\"keyRisks\\\":[{\\\"title\\\":\\\"Concentration\\\",\\\"severity\\\":\\\"HIGH\\\",\\\"why\\\":\\\"70% > 60%\\\"}],"
                        + "\\\"suggestedActions\\\":[{\\\"action\\\":\\\"REBALANCE\\\",\\\"asset\\\":\\\"SOL\\\",\\\"targetWeightPct\\\":50,\\\"rationale\\\":\\\"halve the exposure\\\"}],\\\"confidence\\\":0.8,\\\"disclaimer\\\":\\\"not advice\\\",\\\"promptVersion\\\":\\\"crypto-portfolio-advisor-v1\\\"}";
                return json("{\"runId\":\"" + runId + "\",\"workflowId\":\"crypto.portfolio-advisor.v1\",\"status\":\"SUCCEEDED\",\"agentStages\":[{\"stageId\":\"stage-1\",\"status\":\"SUCCEEDED\",\"output\":\"" + output + "\"}],"
                        + "\"llmCalls\":[{\"stageId\":\"stage-1\",\"agentId\":\"crypto-portfolio-advisor-agent\",\"model\":\"qwen3:14b\"}],"
                        + "\"events\":[{\"type\":\"LLM_CALL_SUCCEEDED\",\"attributes\":{\"agentId\":\"crypto-portfolio-advisor-agent\",\"model\":\"qwen3:14b\","
                        + "\"latencyMs\":\"16100\",\"usage\":\"{\\\"prompt_tokens\\\":3512,\\\"completion_tokens\\\":240,\\\"total_tokens\\\":3752}\"}}]}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
        }
    }
}
