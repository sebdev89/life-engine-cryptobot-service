package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.ObservabilityDtos.CreateMarketObservationRequest;
import io.lifeengine.cryptobot.api.ObservabilityDtos.MarketObservationResponse;
import io.lifeengine.cryptobot.application.MarketObservationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cryptobot/observations")
public class MarketObservationsController {

    private final MarketObservationsService service;

    public MarketObservationsController(MarketObservationsService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Flux<MarketObservationResponse> list(
            @RequestParam("symbol") String symbol,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.findRecentBySymbol(symbol, limit).map(MarketObservationResponse::from);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Mono<MarketObservationResponse> create(@RequestBody CreateMarketObservationRequest request) {
        return service.create(request.toDomain()).map(MarketObservationResponse::from);
    }
}
