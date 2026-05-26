package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.ObservabilityDtos.CreateWatchlistEntryRequest;
import io.lifeengine.cryptobot.api.ObservabilityDtos.WatchlistEntryResponse;
import io.lifeengine.cryptobot.application.WatchlistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cryptobot/watchlist")
public class WatchlistController {

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Flux<WatchlistEntryResponse> list(@RequestParam(name = "symbol", required = false) String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return service.findBySymbol(symbol).map(WatchlistEntryResponse::from);
        }
        return service.listActive().map(WatchlistEntryResponse::from);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Mono<WatchlistEntryResponse> create(@RequestBody CreateWatchlistEntryRequest request) {
        return service.create(request.toDomain()).map(WatchlistEntryResponse::from);
    }
}
