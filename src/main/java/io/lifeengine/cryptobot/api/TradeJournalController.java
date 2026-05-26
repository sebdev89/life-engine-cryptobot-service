package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.api.ObservabilityDtos.CreateTradeJournalEntryRequest;
import io.lifeengine.cryptobot.api.ObservabilityDtos.TradeJournalEntryResponse;
import io.lifeengine.cryptobot.application.TradeJournalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cryptobot/journal")
public class TradeJournalController {

    private final TradeJournalService service;

    public TradeJournalController(TradeJournalService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    public Flux<TradeJournalEntryResponse> list(
            @RequestParam("symbol") String symbol,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.findRecentBySymbol(symbol, limit).map(TradeJournalEntryResponse::from);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Mono<TradeJournalEntryResponse> create(@RequestBody CreateTradeJournalEntryRequest request) {
        return service.create(request.toDomain()).map(TradeJournalEntryResponse::from);
    }
}
