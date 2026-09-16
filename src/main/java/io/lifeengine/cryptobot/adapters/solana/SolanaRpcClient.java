package io.lifeengine.cryptobot.adapters.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Minimal Solana JSON-RPC client over {@link WebClient}. Only the handful of methods CryptoBot
 * needs; no SDK dependency. Every call is scoped to a {@link SolanaCluster} so the same bean
 * serves devnet reads/writes and mainnet reads.
 */
@Component
public class SolanaRpcClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaRpcClient.class);

    public static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    public static final String TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
    public static final long LAMPORTS_PER_SOL = 1_000_000_000L;
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;
    private final SolanaRpcProperties properties;
    private final ObjectMapper objectMapper;
    private final CryptobotMetrics metrics;
    private final AtomicLong requestIds = new AtomicLong(1);

    /** Test-friendly: no metrics exported. */
    public SolanaRpcClient(WebClient.Builder builder, SolanaRpcProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, CryptobotMetrics.noop());
    }

    @Autowired
    public SolanaRpcClient(WebClient.Builder builder, SolanaRpcProperties properties, ObjectMapper objectMapper, CryptobotMetrics metrics) {
        // A busy mainnet wallet returns hundreds of token accounts in jsonParsed form — well over
        // WebClient's 256 KiB default. 16 MiB keeps the reader honest without being unbounded.
        this.webClient = builder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public record TokenAccountBalance(
            String mint, String tokenAccount, String programId, BigInteger amountRaw, int decimals, BigDecimal uiAmount) {}

    public record SignatureInfo(String signature, long slot, Instant blockTime, boolean failed, String memo) {}

    public record LatestBlockhash(String blockhash, long lastValidBlockHeight) {}

    public record SimulationResult(boolean ok, String error, List<String> logs, Long unitsConsumed) {
        public SimulationResult {
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }

    public record SignatureStatus(String signature, String confirmationStatus, boolean failed, String error) {}

    // ---- reads --------------------------------------------------------------------------------

    public Mono<Long> getBalanceLamports(SolanaCluster cluster, String address) {
        return call(cluster, "getBalance", List.of(address, Map.of("commitment", "confirmed")))
                .map(result -> result.path("value").asLong());
    }

    /** SPL + Token-2022 accounts owned by {@code address}, with parsed balances. Zero balances included. */
    public Mono<List<TokenAccountBalance>> getTokenAccountsByOwner(SolanaCluster cluster, String address) {
        return Flux.concat(
                        tokenAccounts(cluster, address, TOKEN_PROGRAM_ID),
                        tokenAccounts(cluster, address, TOKEN_2022_PROGRAM_ID))
                .collectList();
    }

    private Flux<TokenAccountBalance> tokenAccounts(SolanaCluster cluster, String address, String programId) {
        Map<String, Object> filter = Map.of("programId", programId);
        Map<String, Object> config = Map.of("encoding", "jsonParsed", "commitment", "confirmed");
        return call(cluster, "getTokenAccountsByOwner", List.of(address, filter, config))
                .flatMapMany(
                        result -> {
                            List<TokenAccountBalance> out = new ArrayList<>();
                            for (JsonNode entry : result.path("value")) {
                                JsonNode info = entry.path("account").path("data").path("parsed").path("info");
                                JsonNode amount = info.path("tokenAmount");
                                if (info.isMissingNode() || amount.isMissingNode()) {
                                    continue;
                                }
                                out.add(
                                        new TokenAccountBalance(
                                                info.path("mint").asText(),
                                                entry.path("pubkey").asText(),
                                                programId,
                                                new BigInteger(amount.path("amount").asText("0")),
                                                amount.path("decimals").asInt(0),
                                                new BigDecimal(amount.path("uiAmountString").asText("0"))));
                            }
                            return Flux.fromIterable(out);
                        });
    }

    public Mono<List<SignatureInfo>> getSignaturesForAddress(SolanaCluster cluster, String address, int limit) {
        int capped = Math.max(1, Math.min(limit, 50));
        return call(cluster, "getSignaturesForAddress", List.of(address, Map.of("limit", capped, "commitment", "confirmed")))
                .map(
                        result -> {
                            List<SignatureInfo> out = new ArrayList<>();
                            for (JsonNode entry : result) {
                                JsonNode bt = entry.path("blockTime");
                                out.add(
                                        new SignatureInfo(
                                                entry.path("signature").asText(),
                                                entry.path("slot").asLong(),
                                                bt.isNumber() ? Instant.ofEpochSecond(bt.asLong()) : null,
                                                !entry.path("err").isNull() && !entry.path("err").isMissingNode(),
                                                entry.path("memo").isNull() ? null : entry.path("memo").asText(null)));
                            }
                            return out;
                        });
    }

    public Mono<LatestBlockhash> getLatestBlockhash(SolanaCluster cluster) {
        return call(cluster, "getLatestBlockhash", List.of(Map.of("commitment", "finalized")))
                .map(
                        result ->
                                new LatestBlockhash(
                                        result.path("value").path("blockhash").asText(),
                                        result.path("value").path("lastValidBlockHeight").asLong()));
    }

    /**
     * Simulates a serialised transaction. With {@code sigVerify=false} an unsigned transaction can be
     * simulated, which is exactly what the approval step needs: prove it would succeed before anyone
     * signs anything.
     */
    public Mono<SimulationResult> simulateTransaction(SolanaCluster cluster, String transactionBase64, boolean sigVerify) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("encoding", "base64");
        config.put("sigVerify", sigVerify);
        config.put("replaceRecentBlockhash", !sigVerify);
        config.put("commitment", "confirmed");
        return call(cluster, "simulateTransaction", List.of(transactionBase64, config))
                .map(
                        result -> {
                            JsonNode value = result.path("value");
                            JsonNode err = value.path("err");
                            List<String> logs = new ArrayList<>();
                            for (JsonNode l : value.path("logs")) {
                                logs.add(l.asText());
                            }
                            boolean ok = err.isNull() || err.isMissingNode();
                            JsonNode units = value.path("unitsConsumed");
                            return new SimulationResult(ok, ok ? null : err.toString(), logs, units.isNumber() ? units.asLong() : null);
                        });
    }

    // ---- writes (devnet only, enforced by PolicyEngine upstream) ------------------------------

    public Mono<String> sendTransaction(SolanaCluster cluster, String signedTransactionBase64) {
        Map<String, Object> config = Map.of("encoding", "base64", "skipPreflight", false, "preflightCommitment", "confirmed");
        return call(cluster, "sendTransaction", List.of(signedTransactionBase64, config)).map(JsonNode::asText);
    }

    public Mono<SignatureStatus> getSignatureStatus(SolanaCluster cluster, String signature) {
        return call(cluster, "getSignatureStatuses", List.of(List.of(signature), Map.of("searchTransactionHistory", true)))
                .map(
                        result -> {
                            JsonNode status = result.path("value").path(0);
                            if (status.isNull() || status.isMissingNode()) {
                                return new SignatureStatus(signature, null, false, null);
                            }
                            JsonNode err = status.path("err");
                            boolean failed = !(err.isNull() || err.isMissingNode());
                            return new SignatureStatus(
                                    signature,
                                    status.path("confirmationStatus").asText(null),
                                    failed,
                                    failed ? err.toString() : null);
                        });
    }

    public Mono<String> requestAirdrop(SolanaCluster cluster, String address, long lamports) {
        return call(cluster, "requestAirdrop", List.of(address, lamports)).map(JsonNode::asText);
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private Mono<JsonNode> call(SolanaCluster cluster, String method, List<Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.getAndIncrement());
        body.put("method", method);
        body.put("params", params);
        return webClient
                .post()
                .uri(properties.urlFor(cluster))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(this::parse)
                .flatMap(
                        node -> {
                            JsonNode error = node.path("error");
                            if (!error.isMissingNode() && !error.isNull()) {
                                return Mono.error(
                                        new SolanaRpcException(
                                                method,
                                                error.path("code").asInt(),
                                                error.path("message").asText("rpc error"),
                                                error.path("data").isMissingNode() ? null : error.path("data").toString()));
                            }
                            return Mono.just(node.path("result"));
                        })
                .onErrorMap(
                        ex -> !(ex instanceof SolanaRpcException),
                        ex -> new SolanaRpcException(method, -1, "Solana RPC call failed: " + ex.getMessage(), null, ex))
                .doOnError(ex -> {
                    metrics.solanaRpcError(method, cluster.id(), errorKind(ex));
                    log.warn("solana_rpc_failed cluster={} method={} error={}", cluster.id(), method, ex.getMessage());
                });
    }

    /** {@code rpc}: the node answered with a JSON-RPC error · {@code timeout} · {@code transport}: anything else (connection, HTTP, bad JSON). */
    static String errorKind(Throwable ex) {
        if (ex instanceof SolanaRpcException rpc && rpc.getCause() == null) {
            return "rpc";
        }
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        return cause instanceof java.util.concurrent.TimeoutException ? "timeout" : "transport";
    }

    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON-RPC response", e);
        }
    }
}
