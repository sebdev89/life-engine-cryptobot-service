package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.application.paper.PaperRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Same contract as {@code PaperR2dbcStore} (V16), in memory: first insert, strictly-later updates, idempotent price checks. */
public class InMemoryPaperRepository implements PaperRepository {

    private final Map<String, Portfolio> portfolios = new ConcurrentHashMap<>();
    private final Map<String, List<Tick>> ticks = new ConcurrentHashMap<>();
    private final Map<Instant, PriceCheck> checks = new TreeMap<>();

    @Override
    public Mono<Portfolio> find(String id) {
        return Mono.justOrEmpty(portfolios.get(id));
    }

    @Override
    public Flux<Portfolio> findAll() {
        return Flux.fromIterable(portfolios.values()).sort(Comparator.comparing(Portfolio::id));
    }

    @Override
    public synchronized Mono<Void> record(Portfolio after, Tick tick, boolean first) {
        Portfolio current = portfolios.get(after.id());
        if (first ? current != null : current == null || !current.lastBarTime().isBefore(after.lastBarTime())) {
            return Mono.error(new IllegalStateException("paper portfolio " + after.id() + " already at or past " + after.lastBarTime()));
        }
        portfolios.put(after.id(), after);
        ticks.computeIfAbsent(after.id(), k -> new ArrayList<>()).add(tick);
        return Mono.empty();
    }

    @Override
    public Flux<Tick> ticks(String portfolioId) {
        return Flux.fromIterable(List.copyOf(ticks.getOrDefault(portfolioId, List.of())));
    }

    @Override
    public synchronized Mono<Void> recordPriceCheck(PriceCheck check) {
        checks.putIfAbsent(check.barTime(), check);
        return Mono.empty();
    }

    @Override
    public synchronized Flux<PriceCheck> priceChecks() {
        return Flux.fromIterable(List.copyOf(checks.values()));
    }
}
