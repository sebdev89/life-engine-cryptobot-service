package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.ObservabilityDtos.CreatePriceZoneRequest;
import io.lifeengine.cryptobot.api.ObservabilityDtos.PriceZoneResponse;
import io.lifeengine.cryptobot.application.PriceZonesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cryptobot/zones")
public class PriceZonesController {

    private final PriceZonesService service;

    public PriceZonesController(PriceZonesService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Flux<PriceZoneResponse> list(@RequestParam("symbol") String symbol) {
        return service.findBySymbol(symbol).map(PriceZoneResponse::from);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Mono<PriceZoneResponse> create(@RequestBody CreatePriceZoneRequest request) {
        return service.create(request.toDomain()).map(PriceZoneResponse::from);
    }
}
