package io.lifeengine.cryptobot.api.backtest;

import io.lifeengine.cryptobot.api.controlplane.ControlPlaneDtos.ApiError;
import io.lifeengine.cryptobot.application.backtest.BacktestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/cryptobot/backtests} (KAN-1216, CryptoBot v2): one strategy over public SOL/USD history,
 * next to holding SOL and holding USDC, net of fees and slippage. Read-only; same authority as the rest of
 * {@code /api/cryptobot/**}.
 */
@RestController
@RequestMapping("/api/cryptobot/backtests")
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Mono<BacktestService.Run> run(@RequestBody BacktestService.Request request) {
        return service.run(request);
    }

    @ExceptionHandler({IllegalArgumentException.class, ServerWebInputException.class})
    ResponseEntity<ApiError> invalid(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_BACKTEST", ex.getMessage()));
    }

    /** The history source failed or answered garbage: no partial series, no result. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> upstream(Exception ex) {
        log.warn("backtest_history_failed error={}", ex.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError("HISTORY_UNAVAILABLE", ex.getMessage()));
    }
}
