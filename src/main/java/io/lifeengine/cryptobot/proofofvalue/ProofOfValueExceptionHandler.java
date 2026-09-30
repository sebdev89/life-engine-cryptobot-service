package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.api.controlplane.ControlPlaneDtos;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * The Proof of Value errors, in the control plane's {@code ApiError} shape. The generic ones
 * (NotFound 404, InvalidRequest 400, Conflict 409) are mapped by {@code ControlPlaneExceptionHandler},
 * which also covers this package.
 */
@RestControllerAdvice(basePackages = "io.lifeengine.cryptobot.proofofvalue")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProofOfValueExceptionHandler {

    @ExceptionHandler(ProofOfValueExceptions.AcceptanceRejected.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> acceptance(ProofOfValueExceptions.AcceptanceRejected ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ControlPlaneDtos.ApiError("ACCEPTANCE_POLICY_REJECTED", ex.getMessage(), ex.violations()));
    }

    /** {@code @Valid} failures (one detail per field) and unreadable bodies (bad enum, bad timestamp). */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> input(ServerWebInputException ex) {
        List<String> details = ex instanceof WebExchangeBindException bind
                ? bind.getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().toList()
                : List.of(ex.getReason() == null ? "unreadable request" : ex.getReason());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ControlPlaneDtos.ApiError("INVALID_REQUEST", "Invalid request", details));
    }
}
