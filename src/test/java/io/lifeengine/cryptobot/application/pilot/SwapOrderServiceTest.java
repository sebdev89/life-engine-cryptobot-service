package io.lifeengine.cryptobot.application.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.signer.SignerProperties;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import io.lifeengine.cryptobot.testsupport.InMemorySwapOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * KAN-1249 pilot: proposing and approving swap orders against fakes (Jupiter from real legacy fixtures, an RPC that
 * simulates, a signer that names its key). Who may approve, once, and only within the window.
 */
class SwapOrderServiceTest {

    static final String OWNER = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9";
    static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    static final UUID SOMEONE = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    static final class MovingClock extends Clock {
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

    private MockWebServer jupiter;
    private MockWebServer rpc;
    private MockWebServer signer;
    private final AtomicBoolean simulationOk = new AtomicBoolean(true);
    private final AtomicInteger jti = new AtomicInteger();
    private InMemorySwapOrderRepository repo;
    private SimpleMeterRegistry meters;
    private MovingClock clock;

    @BeforeEach
    void start() throws Exception {
        jupiter = new MockWebServer();
        jupiter.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath() == null ? "" : r.getPath();
                String name = path.startsWith("/swap/v1/quote") ? "quote-sol-usdc.json" : path.startsWith("/swap/v1/swap") ? "swap-sol-usdc.json" : null;
                return name == null ? new MockResponse().setResponseCode(404) : json(fixture(name));
            }
        });
        rpc = new MockWebServer();
        rpc.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String err = simulationOk.get() ? "null" : "{\"InstructionError\":[3,{\"Custom\":6001}]}";
                return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"context\":{\"slot\":1},\"value\":{\"err\":" + err
                        + ",\"logs\":[\"Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 success\"],\"unitsConsumed\":61000}}}");
            }
        });
        signer = new MockWebServer();
        signer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                return json("{\"publicKey\":\"" + OWNER + "\",\"cluster\":\"devnet\",\"maxLamports\":1,\"allowedDestinations\":[]}");
            }
        });
        jupiter.start();
        rpc.start();
        signer.start();
        repo = new InMemorySwapOrderRepository();
        meters = new SimpleMeterRegistry();
        clock = new MovingClock();
    }

    @AfterEach
    void stop() throws Exception {
        jupiter.close();
        rpc.close();
        signer.close();
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    static String fixture(String name) {
        try (var in = SwapOrderServiceTest.class.getResourceAsStream("/jupiter/" + name)) {
            return new String(in.readAllBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    SwapOrderService service(boolean enabled, String approverTenant) {
        ObjectMapper om = new ObjectMapper();
        MarketDataProperties md = new MarketDataProperties("http://localhost:" + jupiter.getPort(), Duration.ofSeconds(3), Map.of(), Map.of(), true);
        String rpcUrl = "http://localhost:" + rpc.getPort();
        SolanaRpcClient solana = new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(rpcUrl, rpcUrl, Duration.ofSeconds(3)), om);
        SignerClient signerClient = new SignerClient(WebClient.builder(),
                new SignerProperties(true, "http://localhost:" + signer.getPort(), "tok", Duration.ofSeconds(3)));
        SwapsProperties props = new SwapsProperties(enabled, APPROVER, approverTenant, null, null, "devnet", null, null, null, null);
        return new SwapOrderService(props, repo, new JupiterSwapClient(WebClient.builder(), md, om), solana, signerClient, meters, clock);
    }

    SwapOrderService service() {
        return service(true, "");
    }

    /** The approver as Auth issues them: RS256 via the JWKS, fresh, a new jti each time, no tenant claim, ROLE_ADMIN. */
    CryptobotPrincipal approver() {
        return approver(clock.now, false, null, "RS256", List.of("ROLE_ADMIN"));
    }

    CryptobotPrincipal approver(Instant iat, boolean tenantPresent, String tenant, String alg, List<String> authorities) {
        return new CryptobotPrincipal(APPROVER, "sebas@x", "ADMIN", authorities, "raw", iat, "jti-" + jti.incrementAndGet(), "sid-1", tenantPresent,
                tenant, alg);
    }

    static CryptobotPrincipal someone() {
        return new CryptobotPrincipal(SOMEONE, "x@y", "USER", List.of("ROLE_USER"), "raw", T0, "jti-x", "sid-x", false, null, "RS256");
    }

    static SwapOrderService.CreateRequest solToUsdc() {
        return new SwapOrderService.CreateRequest(SwapOrder.Direction.SOL_USDC, 10_000_000L, 50);
    }

    SwapOrder created(SwapOrderService s, String key) {
        return s.create(someone(), key, solToUsdc()).block();
    }

    String nonce(SwapOrderService s, SwapOrder o) {
        return s.view(approver(), o.id()).block().approvalNonce();
    }

    static String reason(Mono<?> m) {
        try {
            m.block();
            return null;
        } catch (SwapOrderService.SwapRefused ex) {
            return ex.reason();
        }
    }

    // ---- proposal --------------------------------------------------------------------------------

    @Test
    void aProposalStoresThePreviewItsHashAndTheMinimumItBinds() {
        SwapOrder o = created(service(), "k1");

        assertThat(o.status()).isEqualTo(SwapOrder.Status.PENDING_APPROVAL);
        assertThat(o.owner()).isEqualTo(OWNER);
        assertThat(o.inAmount()).isEqualTo(10_000_000L);
        // floor(1 032 158 × 9950 / 10000)
        assertThat(o.minOut()).isEqualTo(1_026_997L);
        assertThat(o.previewHash()).isEqualTo(SwapOrderService.sha256(o.preview()));
        assertThat(o.preview()).contains("\"transaction\"").contains("\"min_out\":1026997").contains("\"cluster\":\"devnet\"");
        assertThat(o.expiresAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(repo.auditEvents(o.id()).collectList().block()).containsExactly("CREATED");
        assertThat(meters.counter(SwapOrderService.METRIC_ORDERS, "status", "PENDING_APPROVAL").count()).isEqualTo(1.0);
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameOrderToItsCreatorAndIsRefusedToAnyoneElse() {
        SwapOrderService s = service();
        SwapOrder first = created(s, "k-same");
        assertThat(created(s, "k-same").id()).isEqualTo(first.id());
        assertThat(reason(s.create(approver(), "k-same", solToUsdc()))).isEqualTo("idempotency_key_in_use");
    }

    @Test
    void proposalsOutsideThePilotOrWhileDisabledAreRefusedWithoutAnyOrder() {
        SwapOrderService s = service();
        assertThat(reason(s.create(someone(), "k", new SwapOrderService.CreateRequest(SwapOrder.Direction.SOL_USDC, 100_000_001L, 50))))
                .isEqualTo("amount_out_of_bounds");
        assertThat(reason(s.create(someone(), "k", new SwapOrderService.CreateRequest(SwapOrder.Direction.USDC_SOL, 10_000_001L, 50))))
                .isEqualTo("amount_out_of_bounds");
        assertThat(reason(s.create(someone(), "k", new SwapOrderService.CreateRequest(SwapOrder.Direction.SOL_USDC, 1L, 101))))
                .isEqualTo("slippage_out_of_bounds");
        assertThat(reason(s.create(someone(), " ", solToUsdc()))).isEqualTo("idempotency_key_required");
        assertThat(reason(service(false, "").create(someone(), "k", solToUsdc()))).isEqualTo("swaps_disabled");
        simulationOk.set(false);
        assertThat(reason(s.create(someone(), "k-sim", solToUsdc()))).startsWith("simulation_failed");
        assertThat(repo.findByIdempotencyKey("k-sim").block()).isNull();
    }

    // ---- approval --------------------------------------------------------------------------------

    @Test
    void theApproverApprovesOnceWithTheOrdersNonceAndTheWindowRestarts() {
        SwapOrderService s = service();
        SwapOrder o = created(s, "k-ok");
        clock.now = T0.plusSeconds(30);

        SwapOrder approved = s.approve(approver(), o.id(), nonce(s, o)).block();

        assertThat(approved.status()).isEqualTo(SwapOrder.Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(APPROVER);
        assertThat(approved.approvedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(approved.expiresAt()).isEqualTo(T0.plusSeconds(150));
        // what was approved is exactly what was proposed
        assertThat(approved.inAmount()).isEqualTo(o.inAmount());
        assertThat(approved.minOut()).isEqualTo(o.minOut());
        assertThat(approved.previewHash()).isEqualTo(o.previewHash());
        assertThat(repo.auditEvents(o.id()).collectList().block()).containsExactly("CREATED", "APPROVED");
        // single use
        assertThat(reason(s.approve(approver(), o.id(), o.approvalNonce()))).isEqualTo("not_pending");
    }

    @Test
    void onlyTheConfiguredApproverWithAPlatformAdminTokenMayApprove() {
        SwapOrderService s = service();
        SwapOrder o = created(s, "k-who");
        String n = o.approvalNonce();
        assertThat(reason(s.approve(someone(), o.id(), n))).isEqualTo("not_approver");
        // same sub, but a tenant user — and a present-but-empty tenant is not an absent one
        assertThat(reason(s.approve(approver(clock.now, true, "acme", "RS256", List.of("ROLE_ADMIN")), o.id(), n))).isEqualTo("tenant_mismatch");
        assertThat(reason(s.approve(approver(clock.now, true, "", "RS256", List.of("ROLE_ADMIN")), o.id(), n))).isEqualTo("tenant_mismatch");
        assertThat(reason(s.approve(approver(clock.now, false, null, "RS256", List.of("ROLE_USER")), o.id(), n))).isEqualTo("not_admin");
        assertThat(repo.find(o.id()).block().status()).isEqualTo(SwapOrder.Status.PENDING_APPROVAL);
    }

    @Test
    void aConfiguredTenantMustBePresentAndEqual() {
        SwapOrderService s = service(true, "platform");
        SwapOrder o = created(s, "k-tenant");
        assertThat(reason(s.approve(approver(), o.id(), o.approvalNonce()))).isEqualTo("tenant_mismatch");
        assertThat(s.approve(approver(clock.now, true, "platform", "RS256", List.of("ROLE_ADMIN")), o.id(), o.approvalNonce()).block().status())
                .isEqualTo(SwapOrder.Status.APPROVED);
    }

    @Test
    void theTokenMustBeRs256FreshAndCarryJtiAndSid() {
        SwapOrderService s = service();
        SwapOrder o = created(s, "k-token");
        String n = o.approvalNonce();
        assertThat(reason(s.approve(approver(clock.now, false, null, "HS256", List.of("ROLE_ADMIN")), o.id(), n))).isEqualTo("token_not_rs256");
        assertThat(reason(s.approve(approver(clock.now.minus(Duration.ofMinutes(5)).minusSeconds(1), false, null, "RS256", List.of("ROLE_ADMIN")),
                o.id(), n))).isEqualTo("token_not_fresh");
        assertThat(reason(s.approve(approver(clock.now.plusSeconds(31), false, null, "RS256", List.of("ROLE_ADMIN")), o.id(), n)))
                .isEqualTo("token_not_fresh");
        assertThat(reason(s.approve(approver(null, false, null, "RS256", List.of("ROLE_ADMIN")), o.id(), n))).isEqualTo("token_not_fresh");
        CryptobotPrincipal noJti = new CryptobotPrincipal(APPROVER, "s", "ADMIN", List.of("ROLE_ADMIN"), "raw", clock.now, null, "sid", false, null,
                "RS256");
        assertThat(reason(s.approve(noJti, o.id(), n))).isEqualTo("token_without_jti_or_sid");
        // exactly 5 minutes old is still fresh
        assertThat(s.approve(approver(clock.now.minus(Duration.ofMinutes(5)), false, null, "RS256", List.of("ROLE_ADMIN")), o.id(), n).block()
                .status()).isEqualTo(SwapOrder.Status.APPROVED);
    }

    @Test
    void theNonceIsTheOrdersAndOnlyTheApproverCanReadIt() {
        SwapOrderService s = service();
        SwapOrder a = created(s, "k-a");
        SwapOrder b = created(s, "k-b");
        assertThat(s.view(someone(), a.id()).block().approvalNonce()).isNull();
        assertThat(s.view(approver(), a.id()).block().approvalNonce()).isEqualTo(a.approvalNonce());
        // b's nonce does not approve a; no nonce does not either
        assertThat(reason(s.approve(approver(), a.id(), b.approvalNonce()))).isEqualTo("csrf_mismatch");
        assertThat(reason(s.approve(approver(), a.id(), null))).isEqualTo("csrf_mismatch");
        s.approve(approver(), a.id(), a.approvalNonce()).block();
        assertThat(s.view(approver(), a.id()).block().approvalNonce()).as("no nonce once it is no longer pending").isNull();
    }

    @Test
    void oneTokenApprovesOneOrderNeverTwo() {
        SwapOrderService s = service();
        SwapOrder a = created(s, "k-j1");
        SwapOrder b = created(s, "k-j2");
        CryptobotPrincipal sameToken = approver();
        s.approve(sameToken, a.id(), a.approvalNonce()).block();
        assertThat(reason(s.approve(sameToken, b.id(), b.approvalNonce()))).isEqualTo("not_pending_or_token_used");
        assertThat(repo.find(b.id()).block().status()).isEqualTo(SwapOrder.Status.PENDING_APPROVAL);
    }

    @Test
    void anOrderNotApprovedInTimeExpires() {
        SwapOrderService s = service();
        SwapOrder o = created(s, "k-late");
        clock.now = T0.plusSeconds(120);
        assertThat(reason(s.approve(approver(), o.id(), o.approvalNonce()))).isEqualTo("approval_expired");
        assertThat(repo.find(o.id()).block().status()).isEqualTo(SwapOrder.Status.EXPIRED);
        assertThat(repo.auditEvents(o.id()).collectList().block()).containsExactly("CREATED", "EXPIRED");
        // one second before the deadline it would have been fine
        SwapOrder p = created(s, "k-intime");
        clock.now = clock.now.plusSeconds(119);
        assertThat(s.approve(approver(), p.id(), p.approvalNonce()).block().status()).isEqualTo(SwapOrder.Status.APPROVED);
    }

    @Test
    void theCreatorOrTheApproverMayRejectAPendingOrderAndNobodyElse() {
        SwapOrderService s = service();
        SwapOrder o = created(s, "k-rej");
        CryptobotPrincipal other = new CryptobotPrincipal(UUID.randomUUID(), "z", "USER", List.of("ROLE_USER"), "raw");
        assertThat(reason(s.reject(other, o.id()))).isEqualTo("not_creator_or_approver");
        assertThat(s.reject(someone(), o.id()).block().status()).isEqualTo(SwapOrder.Status.REJECTED);
        assertThat(reason(s.reject(approver(), o.id()))).isEqualTo("not_pending");
        assertThat(reason(s.approve(approver(), o.id(), o.approvalNonce()))).isEqualTo("not_pending");
    }

    @Test
    void withNoApproverConfiguredNobodyApproves() {
        ObjectMapper om = new ObjectMapper();
        SwapOrderService base = service();
        SwapOrder o = created(base, "k-none");
        String rpcUrl = "http://localhost:" + rpc.getPort();
        SwapOrderService none = new SwapOrderService(new SwapsProperties(true, null, "", null, null, "devnet", null, null, null, null), repo,
                new JupiterSwapClient(WebClient.builder(), new MarketDataProperties("http://localhost:" + jupiter.getPort(), Duration.ofSeconds(3),
                        Map.of(), Map.of(), true), om),
                new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(rpcUrl, rpcUrl, Duration.ofSeconds(3)), om),
                new SignerClient(WebClient.builder(), new SignerProperties(true, "http://localhost:" + signer.getPort(), "tok", Duration.ofSeconds(3))),
                meters, clock);
        assertThat(reason(none.approve(approver(), o.id(), o.approvalNonce()))).isEqualTo("no_approver_configured");
    }

    @Test
    void disabledMeansNothingAtAll() {
        SwapOrderService off = service(false, "");
        UUID id = UUID.randomUUID();
        assertThat(reason(off.approve(approver(), id, "n"))).isEqualTo("swaps_disabled");
        assertThat(reason(off.reject(approver(), id))).isEqualTo("swaps_disabled");
        assertThat(reason(off.view(approver(), id))).isEqualTo("swaps_disabled");
        StepVerifier.create(off.create(someone(), "k", solToUsdc())).expectError(SwapOrderService.SwapRefused.class).verify();
    }
}
