package io.lifeengine.cryptobot.api.paper;

import io.lifeengine.cryptobot.application.paper.PaperTradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/cryptobot/paper} (KAN-1217, CryptoBot v3): every paper portfolio with its frozen parameters,
 * its window and, from two candles on, the same metrics as a backtest, computed from the stored ticks; whether it
 * beats holding SOL and holding USDC over the exact same window; and the Coinbase-vs-Jupiter price gap. Read-only.
 */
@RestController
@RequestMapping("/api/cryptobot/paper")
public class PaperTradingController {

    private final PaperTradingService service;

    public PaperTradingController(PaperTradingService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Mono<PaperTradingService.Report> report() {
        return service.report();
    }
}
