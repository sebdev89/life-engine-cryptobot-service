package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.domain.RuntimeUnreachableException;
import io.lifeengine.cryptobot.adapters.solana.MainnetDisabledException;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps control-plane failures to HTTP. 404 for foreign ids (never 403: do not leak existence). */
@RestControllerAdvice(basePackages = "io.lifeengine.cryptobot.api.controlplane")
public class ControlPlaneExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneExceptionHandler.class);

    @ExceptionHandler(ControlPlaneExceptions.NotFound.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> notFound(ControlPlaneExceptions.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ControlPlaneDtos.ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ControlPlaneExceptions.InvalidRequest.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> invalid(ControlPlaneExceptions.InvalidRequest ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ControlPlaneDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ControlPlaneExceptions.Conflict.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> conflict(ControlPlaneExceptions.Conflict ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ControlPlaneDtos.ApiError("CONFLICT", ex.getMessage()));
    }

    /** KAN-493: mainnet is fail-closed; nothing was signed, sent or persisted. */
    @ExceptionHandler(MainnetDisabledException.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> mainnetDisabled(MainnetDisabledException ex) {
        log.warn("control_plane_mainnet_disabled cluster={} error={}", ex.cluster().id(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ControlPlaneDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ControlPlaneExceptions.PolicyBlocked.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> policy(ControlPlaneExceptions.PolicyBlocked ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ControlPlaneDtos.ApiError("POLICY_BLOCKED", ex.getMessage(), ex.violations()));
    }

    @ExceptionHandler({ControlPlaneExceptions.UpstreamUnavailable.class, RuntimeUnreachableException.class})
    public ResponseEntity<ControlPlaneDtos.ApiError> upstream(RuntimeException ex) {
        log.warn("control_plane_upstream_failed error={}", ex.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ControlPlaneDtos.ApiError("UPSTREAM_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(SolanaRpcException.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> rpc(SolanaRpcException ex) {
        log.warn("control_plane_rpc_failed method={} code={} error={}", ex.method(), ex.code(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ControlPlaneDtos.ApiError("SOLANA_RPC_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> state(IllegalStateException ex) {
        log.warn("control_plane_illegal_state error={}", ex.toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ControlPlaneDtos.ApiError("ILLEGAL_STATE", ex.getMessage()));
    }
}
