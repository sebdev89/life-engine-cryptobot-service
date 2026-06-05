package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.MarketReviewDtos.ApiError;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewRequest;
import io.lifeengine.cryptobot.api.MarketReviewDtos.MarketReviewResponse;
import io.lifeengine.cryptobot.application.MarketReviewService;
import io.lifeengine.cryptobot.domain.InvalidSymbolException;
import io.lifeengine.cryptobot.domain.RuntimeUnreachableException;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cryptobot")
public class MarketReviewController {

    private final MarketReviewService service;

    public MarketReviewController(MarketReviewService service) {
        this.service = service;
    }

    @PostMapping(path = "/market-review", consumes = "application/json", produces = "application/json")
    public Mono<MarketReviewResponse> marketReview(
            @RequestBody MarketReviewRequest request,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        if (principal == null) {
            return Mono.error(new IllegalStateException("Missing authenticated principal"));
        }
        String requestedBy =
                principal.email() != null && !principal.email().isBlank()
                        ? principal.email()
                        : (principal.userId() != null ? principal.userId().toString() : null);
        return service.execute(request, principal.rawToken(), requestedBy);
    }

    @ExceptionHandler(InvalidSymbolException.class)
    public ResponseEntity<ApiError> handleInvalidSymbol(InvalidSymbolException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_SYMBOL", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeUnreachableException.class)
    public ResponseEntity<ApiError> handleRuntimeUnreachable(RuntimeUnreachableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError("RUNTIME_UNREACHABLE", ex.getMessage()));
    }
}
