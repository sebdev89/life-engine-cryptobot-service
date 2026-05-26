package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.ObservabilityDtos.IndicatorSnapshotResponse;
import io.lifeengine.cryptobot.application.IndicatorsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/cryptobot/indicators")
public class IndicatorsController {

    private final IndicatorsService service;

    public IndicatorsController(IndicatorsService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Flux<IndicatorSnapshotResponse> list(
            @RequestParam("symbol") String symbol,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.findRecentBySymbol(symbol, limit).map(IndicatorSnapshotResponse::from);
    }
}
