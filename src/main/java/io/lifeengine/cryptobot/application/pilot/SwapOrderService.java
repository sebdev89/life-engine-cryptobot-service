package io.lifeengine.cryptobot.application.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.jupiter.SwapQuote;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwap;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwapCheck;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * KAN-1249 pilot: proposing and approving swap orders. Nothing here signs or sends — execution is a separate step
 * that only takes {@code APPROVED} orders (b2b).
 *
 * <p><b>Proposal</b> ({@code POST /swaps}, any authenticated user, idempotent per {@code Idempotency-Key}): Jupiter
 * quotes and builds a legacy transaction for the signer's public key, {@link UnsignedSwapCheck} checks it, the RPC
 * simulates it, and the order stores that preview, its SHA-256 and the minimum output it binds
 * ({@code floor(out × (10000 − slippage) / 10000)}, the bound the chain enforces).
 *
 * <p><b>Approval</b> ({@code POST /swaps/{id}/approve}). User tokens from Auth carry no {@code aud} today (KAN-1285),
 * so any valid user token could be presented here; the defense is every one of these, from server configuration:
 * {@code sub} is {@code cryptobot.swaps.approver-user-id}; the {@code tenant} claim is absent (or equals the configured
 * one — present-but-empty is not absent); {@code CRYPTOBOT_ADMIN} (KAN-1323; not the platform ROLE_ADMIN); the signature was verified as RS256 through the JWKS;
 * {@code iat} at most {@code approval-iat-max-age} old; {@code jti} and {@code sid} present and the {@code jti} never used
 * for an approval before (UNIQUE in the database, so a restart does not forget it); {@code X-CSRF-Token} equal to the
 * order's nonce, which {@code GET /swaps/{id}} only returns to that approver; the order still pending and inside its
 * TTL. The transition is a conditional update — single use — and leaves an audit row with who, when and the preview
 * hash. When Auth issues {@code aud}, this adds {@code aud=cryptobot}.
 */
public class SwapOrderService {

    private static final Logger log = LoggerFactory.getLogger(SwapOrderService.class);
    public static final String METRIC_ORDERS = "cryptobot.swaps.orders";
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A request the service will not carry out, with a stable reason and the kind of failure it is. */
    public static final class SwapRefused extends RuntimeException {
        public enum Kind { BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT, UNAVAILABLE, RATE_LIMITED }

        private final Kind kind;
        private final String reason;

        public SwapRefused(Kind kind, String reason) {
            super(reason);
            this.kind = kind;
            this.reason = reason;
        }

        public Kind kind() {
            return kind;
        }

        public String reason() {
            return reason;
        }
    }

    public record CreateRequest(SwapOrder.Direction direction, long amount, int maxSlippageBps) {}

    /** What a caller may see of an order; {@code approvalNonce} only for the approver, only while it is pending. */
    public record View(SwapOrder order, String approvalNonce) {}

    private final SwapsProperties props;
    private final SwapOrderRepository repo;
    private final JupiterSwapClient jupiter;
    private final SolanaRpcClient rpc;
    private final SignerClient signer;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ObjectMapper canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    /** Proposal timestamps per creator within the last hour (each proposal calls Jupiter and the RPC). */
    private final Map<UUID, java.util.Deque<Instant>> proposals = new java.util.concurrent.ConcurrentHashMap<>();

    public SwapOrderService(SwapsProperties props, SwapOrderRepository repo, JupiterSwapClient jupiter, SolanaRpcClient rpc, SignerClient signer,
            MeterRegistry meters, Clock clock) {
        this.props = props;
        this.repo = repo;
        this.jupiter = jupiter;
        this.rpc = rpc;
        this.signer = signer;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- proposal --------------------------------------------------------------------------------

    public Mono<SwapOrder> create(CryptobotPrincipal principal, String idempotencyKey, CreateRequest req) {
        if (!props.enabled()) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        if (principal == null || principal.userId() == null) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, "unauthenticated"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.BAD_REQUEST, "idempotency_key_required"));
        }
        if (req == null || req.direction() == null || req.amount() <= 0 || req.amount() > props.maxIn(req.direction())) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.BAD_REQUEST, "amount_out_of_bounds"));
        }
        if (req.maxSlippageBps() < 0 || req.maxSlippageBps() > props.maxSlippageBps()) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.BAD_REQUEST, "slippage_out_of_bounds"));
        }
        String key = idempotencyKey.trim();
        return repo.findByIdempotencyKey(principal.userId(), key)
                .switchIfEmpty(Mono.defer(() -> {
                    if (!admitProposal(principal.userId())) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.RATE_LIMITED, "too_many_proposals"));
                    }
                    return propose(principal, key, req)
                            // the same creator and key raced in: the other request created it; return that one
                            .onErrorResume(org.springframework.dao.DataIntegrityViolationException.class,
                                    ex -> repo.findByIdempotencyKey(principal.userId(), key));
                }));
    }

    private Mono<SwapOrder> propose(CryptobotPrincipal principal, String key, CreateRequest req) {
        SolanaCluster cluster = SolanaCluster.parse(props.cluster());
        return signer.identity()
                .flatMap(id -> id.isPresent() ? Mono.just(id.get().publicKey())
                        : Mono.<String>error(new SwapRefused(SwapRefused.Kind.UNAVAILABLE, "signer_unavailable")))
                .flatMap(owner -> jupiter.quote(req.direction().inputMint(), req.direction().outputMint(), req.amount(), req.maxSlippageBps())
                        .flatMap(quote -> jupiter.buildSwap(quote, owner).flatMap(swap -> {
                            UnsignedSwapCheck.check(swap.transactionBase64(), owner);
                            return rpc.simulateTransaction(cluster, swap.transactionBase64(), false).flatMap(sim -> {
                                if (!sim.ok()) {
                                    // the client gets a stable code; the RPC's detail stays in the log
                                    log.warn("swap_order_simulation_failed direction={} amount={} error={}", req.direction(), req.amount(), sim.error());
                                    return Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "simulation_failed"));
                                }
                                long minOut = BigInteger.valueOf(quote.outAmount()).multiply(BigInteger.valueOf(10_000 - req.maxSlippageBps()))
                                        .divide(BigInteger.valueOf(10_000)).longValueExact();
                                String preview = preview(req, props.cluster(), owner, minOut, quote, swap, sim);
                                Instant now = clock.instant();
                                SwapOrder order = new SwapOrder(UUID.randomUUID(), key, principal.userId(), SwapOrder.Status.PENDING_APPROVAL,
                                        req.direction(), props.cluster(), owner, req.amount(), minOut, req.maxSlippageBps(), preview, sha256(preview),
                                        nonce(), null, null, null, now.plus(props.approvalTtl()), null, null, now, now);
                                return repo.insert(order)
                                        .flatMap(saved -> repo.audit(saved.id(), "CREATED", principal.userId(), saved.previewHash(), null)
                                                .thenReturn(saved))
                                        .doOnNext(saved -> {
                                            count(saved.status());
                                            log.info("swap_order_created id={} direction={} cluster={} in_amount={} min_out={} preview_hash={}",
                                                    saved.id(), saved.direction(), saved.cluster(), saved.inAmount(), saved.minOut(), saved.previewHash());
                                        });
                            });
                        })));
    }

    private String preview(CreateRequest req, String cluster, String owner, long minOut, SwapQuote quote, UnsignedSwap swap,
            SolanaRpcClient.SimulationResult sim) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("direction", req.direction().name());
        p.put("cluster", cluster);
        p.put("owner", owner);
        p.put("in_amount", req.amount());
        p.put("min_out", minOut);
        p.put("max_slippage_bps", req.maxSlippageBps());
        p.put("quote", quote.raw());
        p.put("transaction", swap.transactionBase64());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ok", sim.ok());
        s.put("units", sim.unitsConsumed());
        s.put("logs", sim.logs());
        p.put("simulation", s);
        try {
            return canonical.writeValueAsString(p);
        } catch (Exception ex) {
            throw new IllegalStateException("preview not serializable", ex);
        }
    }

    // ---- approval ----------------------------------------------------------------------------------

    /** @param previewHash the preview the approver saw: it must be this order's, exactly (explicit binding) */
    public Mono<SwapOrder> approve(CryptobotPrincipal principal, UUID id, String csrfToken, String previewHash) {
        if (!props.enabled()) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        String identity = approverIdentityRefusal(principal);
        if (identity != null) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, identity));
        }
        String staleReason = freshTokenRefusal(principal);
        if (staleReason != null) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, staleReason));
        }
        Instant now = clock.instant();
        return repo.find(id)
                .switchIfEmpty(Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "order_not_found")))
                .flatMap(order -> {
                    if (order.status() != SwapOrder.Status.PENDING_APPROVAL) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "not_pending"));
                    }
                    if (!now.isBefore(order.expiresAt())) {
                        return repo.transition(order.id(), SwapOrder.Status.PENDING_APPROVAL, SwapOrder.Status.EXPIRED, "approval window elapsed")
                                .flatMap(e -> repo.audit(e.id(), "EXPIRED", null, e.previewHash(), null).doOnSuccess(v -> count(e.status())))
                                .then(Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "approval_expired")));
                    }
                    if (csrfToken == null || !MessageDigest.isEqual(csrfToken.getBytes(StandardCharsets.UTF_8),
                            order.approvalNonce().getBytes(StandardCharsets.UTF_8))) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, "csrf_mismatch"));
                    }
                    if (previewHash == null || !previewHash.equals(order.previewHash())) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "preview_mismatch"));
                    }
                    return repo.approve(order.id(), principal.userId(), now, principal.jti(), now.plus(props.approvalTtl()))
                            .switchIfEmpty(Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "not_pending_or_token_used")))
                            .flatMap(approved -> repo.audit(approved.id(), "APPROVED", principal.userId(), approved.previewHash(),
                                    "jti=" + principal.jti() + " sid=" + principal.sessionId()).thenReturn(approved))
                            .doOnNext(approved -> {
                                count(approved.status());
                                log.info("swap_order_approved id={} approver={} preview_hash={} execute_before={}", approved.id(),
                                        approved.approvedBy(), approved.previewHash(), approved.expiresAt());
                            });
                });
    }

    public Mono<SwapOrder> reject(CryptobotPrincipal principal, UUID id) {
        if (!props.enabled()) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        return repo.find(id)
                .switchIfEmpty(Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "order_not_found")))
                .flatMap(order -> {
                    boolean creator = principal != null && order.createdBy().equals(principal.userId());
                    if (!creator && approverIdentityRefusal(principal) != null) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, "not_creator_or_approver"));
                    }
                    return repo.transition(order.id(), SwapOrder.Status.PENDING_APPROVAL, SwapOrder.Status.REJECTED, "rejected")
                            .switchIfEmpty(Mono.error(new SwapRefused(SwapRefused.Kind.CONFLICT, "not_pending")))
                            .flatMap(r -> repo.audit(r.id(), "REJECTED", principal.userId(), r.previewHash(), null).thenReturn(r))
                            .doOnNext(r -> count(r.status()));
                });
    }

    public Mono<View> view(CryptobotPrincipal principal, UUID id) {
        if (!props.enabled()) {
            return Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        return repo.find(id)
                .switchIfEmpty(Mono.error(new SwapRefused(SwapRefused.Kind.NOT_FOUND, "order_not_found")))
                .flatMap(order -> {
                    boolean approver = approverIdentityRefusal(principal) == null;
                    boolean creator = principal != null && order.createdBy().equals(principal.userId());
                    if (!approver && !creator) {
                        return Mono.error(new SwapRefused(SwapRefused.Kind.FORBIDDEN, "not_creator_or_approver"));
                    }
                    return Mono.just(new View(order, order.status() == SwapOrder.Status.PENDING_APPROVAL && approver ? order.approvalNonce() : null));
                });
    }

    // ---- who may approve ----------------------------------------------------------------------------

    /** null = this principal is the configured approver (sub, tenant, CRYPTOBOT_ADMIN); otherwise why not. */
    String approverIdentityRefusal(CryptobotPrincipal p) {
        if (props.approverUserId() == null) {
            return "no_approver_configured";
        }
        if (p == null || !props.approverUserId().equals(p.userId())) {
            return "not_approver";
        }
        boolean tenantOk = props.approverTenant().isEmpty() ? !p.tenantPresent() : p.tenantPresent() && props.approverTenant().equals(p.tenant());
        if (!tenantOk) {
            return "tenant_mismatch";
        }
        // KAN-1323 (ADR-SEC-002): CryptoBot's own admin permission, never the platform's ROLE_ADMIN by itself
        if (!p.authorities().contains(io.lifeengine.cryptobot.security.CryptobotJwtService.CRYPTOBOT_ADMIN)) {
            return "not_admin";
        }
        return null;
    }

    /** null = the token itself may approve: RS256 through the JWKS, fresh iat, jti and sid present. */
    String freshTokenRefusal(CryptobotPrincipal p) {
        if (!"RS256".equals(p.algorithm())) {
            return "token_not_rs256";
        }
        if (p.jti() == null || p.jti().isBlank() || p.sessionId() == null || p.sessionId().isBlank()) {
            return "token_without_jti_or_sid";
        }
        Instant now = clock.instant();
        if (p.issuedAt() == null || p.issuedAt().isAfter(now.plus(CLOCK_SKEW)) || p.issuedAt().isBefore(now.minus(props.approvalIatMaxAge()))) {
            return "token_not_fresh";
        }
        return null;
    }

    /** Rolling one-hour window per creator; true = admitted (and counted). */
    private boolean admitProposal(UUID creator) {
        Instant now = clock.instant();
        java.util.Deque<Instant> window = proposals.computeIfAbsent(creator, k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(Duration.ofHours(1)))) {
                window.removeFirst();
            }
            if (window.size() >= props.createsPerHour()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void count(SwapOrder.Status status) {
        meters.counter(METRIC_ORDERS, "status", status.name()).increment();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String nonce() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
