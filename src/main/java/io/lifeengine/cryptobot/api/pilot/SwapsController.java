package io.lifeengine.cryptobot.api.pilot;

import io.lifeengine.cryptobot.application.pilot.SwapExecutor;
import io.lifeengine.cryptobot.application.pilot.SwapOrder;
import io.lifeengine.cryptobot.application.pilot.SwapOrderService;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * KAN-1249 pilot swap orders. {@code POST /swaps} proposes (needs {@code Idempotency-Key}); {@code GET /swaps/{id}}
 * shows it (the approval nonce only to the approver); {@code POST /swaps/{id}/approve} approves (needs
 * {@code X-CSRF-Token}); {@code POST /swaps/{id}/reject} rejects. See {@link SwapOrderService} for who may approve.
 * {@code POST /swaps/{id}/execute} (the approver) executes an approved order once; {@code POST /swaps/{id}/reconcile}
 * (creator or approver) asks the chain where an in-flight order is. See {@link SwapExecutor}.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/swaps", produces = "application/json")
public class SwapsController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    public record CreateSwap(SwapOrder.Direction direction, Long amount, Integer maxSlippageBps) {}

    /** The preview the approver saw ({@code previewHash} from {@code GET /swaps/{id}}): approving binds exactly it. */
    public record ApproveSwap(String previewHash) {}

    /** The order as the caller may see it; {@code approvalNonce} is null unless the caller is the approver and it is pending. */
    public record SwapView(UUID id, String status, String direction, String cluster, String owner, long inAmount, long minOut, int maxSlippageBps,
            String previewHash, String preview, UUID createdBy, UUID approvedBy, Instant approvedAt, Instant expiresAt, String txSignature,
            String error, Instant createdAt, String approvalNonce) {

        static SwapView of(SwapOrder o, String nonce) {
            return new SwapView(o.id(), o.status().name(), o.direction().name(), o.cluster(), o.owner(), o.inAmount(), o.minOut(), o.maxSlippageBps(),
                    o.previewHash(), o.preview(), o.createdBy(), o.approvedBy(), o.approvedAt(), o.expiresAt(), o.txSignature(), o.error(),
                    o.createdAt(), nonce);
        }
    }

    private final SwapOrderService swaps;
    private final SwapExecutor executor;

    public SwapsController(SwapOrderService swaps, SwapExecutor executor) {
        this.swaps = swaps;
        this.executor = executor;
    }

    @PostMapping(consumes = "application/json")
    public Mono<ResponseEntity<?>> create(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key, @RequestBody CreateSwap body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        SwapOrderService.CreateRequest req = body == null || body.amount() == null || body.maxSlippageBps() == null
                ? null : new SwapOrderService.CreateRequest(body.direction(), body.amount(), body.maxSlippageBps());
        return swaps.create(principal, key, req)
                .flatMap(o -> swaps.view(principal, o.id()))
                .<ResponseEntity<?>>map(v -> ResponseEntity.status(HttpStatus.CREATED).body(SwapView.of(v.order(), v.approvalNonce())))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> get(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return swaps.view(principal, id)
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok(SwapView.of(v.order(), v.approvalNonce())))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<?>> approve(@PathVariable UUID id, @RequestHeader(value = CSRF_HEADER, required = false) String csrf,
            @RequestBody(required = false) ApproveSwap body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return swaps.approve(principal, id, csrf, body == null ? null : body.previewHash())
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(SwapView.of(o, null)))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    @PostMapping("/{id}/reject")
    public Mono<ResponseEntity<?>> reject(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return swaps.reject(principal, id)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(SwapView.of(o, null)))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    @PostMapping("/{id}/execute")
    public Mono<ResponseEntity<?>> execute(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return executor.execute(principal, id)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(SwapView.of(o, null)))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    @PostMapping("/{id}/reconcile")
    public Mono<ResponseEntity<?>> reconcile(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return executor.reconcile(principal, id)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(SwapView.of(o, null)))
                .onErrorResume(SwapOrderService.SwapRefused.class, SwapsController::refused);
    }

    static Mono<ResponseEntity<?>> refused(SwapOrderService.SwapRefused ex) {
        HttpStatus status = switch (ex.kind()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return Mono.just(ResponseEntity.status(status).body(Map.of("reason", ex.reason())));
    }
}
