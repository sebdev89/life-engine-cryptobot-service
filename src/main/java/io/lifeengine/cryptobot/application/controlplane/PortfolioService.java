package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.marketdata.PriceProvider;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioChange;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.portfolio.Position;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.PortfolioSnapshotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Reads the chain, values it, persists the snapshot, and answers "what changed?". */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record PortfolioView(PortfolioSnapshot snapshot, RiskReport risk, PortfolioDiff changes) {}

    private final SolanaRpcClient rpc;
    private final PriceProvider prices;
    private final TokenRegistry registry;
    private final PortfolioSnapshotRepository snapshots;
    private final RiskEngine riskEngine;
    private final Clock clock;

    public PortfolioService(
            SolanaRpcClient rpc,
            PriceProvider prices,
            TokenRegistry registry,
            PortfolioSnapshotRepository snapshots,
            RiskEngine riskEngine) {
        this.rpc = rpc;
        this.prices = prices;
        this.registry = registry;
        this.snapshots = snapshots;
        this.riskEngine = riskEngine;
        this.clock = Clock.systemUTC();
    }

    /** Reads the chain and stores a fresh snapshot; returns it with risk + diff vs the previous one. */
    public Mono<PortfolioView> refresh(Wallet wallet) {
        Mono<Long> lamports = rpc.getBalanceLamports(wallet.cluster(), wallet.address());
        Mono<List<SolanaRpcClient.TokenAccountBalance>> tokens = rpc.getTokenAccountsByOwner(wallet.cluster(), wallet.address());
        Mono<Integer> txCount = rpc.getSignaturesForAddress(wallet.cluster(), wallet.address(), 20)
                .map(List::size)
                .onErrorReturn(0);
        return Mono.zip(lamports, tokens, txCount)
                .flatMap(t -> value(wallet, t.getT1(), t.getT2(), t.getT3()))
                .flatMap(snapshot -> previous(wallet.id()).map(Optional::of).defaultIfEmpty(Optional.empty())
                        .flatMap(prev -> snapshots.insert(snapshot).map(saved -> view(saved, prev.orElse(null)))));
    }

    /** Latest stored snapshot (or a fresh one if none exists yet). */
    public Mono<PortfolioView> latest(Wallet wallet) {
        return snapshots.findRecent(wallet.id(), 2)
                .collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return refresh(wallet);
                    }
                    PortfolioSnapshot current = list.get(0);
                    PortfolioSnapshot prev = list.size() > 1 ? list.get(1) : null;
                    return Mono.just(view(current, prev));
                });
    }

    public Mono<PortfolioSnapshot> snapshot(UUID id) {
        return snapshots.findById(id);
    }

    private Mono<PortfolioSnapshot> previous(UUID walletId) {
        return snapshots.findRecent(walletId, 1).next();
    }

    private PortfolioView view(PortfolioSnapshot current, PortfolioSnapshot prev) {
        PortfolioDiff diff = prev == null ? null : diff(prev, current);
        return new PortfolioView(current, riskEngine.evaluate(current, diff), diff);
    }

    private Mono<PortfolioSnapshot> value(Wallet wallet, long lamports, List<SolanaRpcClient.TokenAccountBalance> tokens, int txCount) {
        // Aggregate raw holdings by mint (a wallet can hold several token accounts of one mint).
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> decimals = new LinkedHashMap<>();
        amounts.put(TokenRegistry.NATIVE_SOL_MINT, BigDecimal.valueOf(lamports).divide(BigDecimal.valueOf(SolanaRpcClient.LAMPORTS_PER_SOL), 9, RoundingMode.DOWN));
        decimals.put(TokenRegistry.NATIVE_SOL_MINT, 9);
        for (SolanaRpcClient.TokenAccountBalance t : tokens) {
            if (t.uiAmount() == null || t.uiAmount().signum() == 0) {
                continue;
            }
            amounts.merge(t.mint(), t.uiAmount(), BigDecimal::add);
            decimals.putIfAbsent(t.mint(), t.decimals());
        }
        Set<String> priceMints = new LinkedHashSet<>();
        amounts.keySet().forEach(m -> registry.priceMintOf(m).ifPresent(priceMints::add));

        return prices.prices(priceMints)
                .map(quotes -> {
                    List<Position> positions = new ArrayList<>();
                    BigDecimal total = BigDecimal.ZERO;
                    Set<String> sources = new LinkedHashSet<>();
                    for (Map.Entry<String, BigDecimal> e : amounts.entrySet()) {
                        String mint = e.getKey();
                        Optional<String> priceMint = registry.priceMintOf(mint);
                        PriceProvider.PriceQuote q = priceMint.map(quotes::get).orElse(null);
                        BigDecimal price = q == null ? null : q.priceUsd();
                        BigDecimal value = price == null ? null : e.getValue().multiply(price).setScale(6, RoundingMode.HALF_UP);
                        if (value != null) {
                            total = total.add(value);
                            sources.add(q.source());
                        }
                        positions.add(new Position(mint, registry.symbolOf(mint), e.getValue(), decimals.get(mint), price, value, null,
                                registry.isStable(mint), TokenRegistry.NATIVE_SOL_MINT.equals(mint), q == null ? null : q.source()));
                    }
                    final BigDecimal totalUsd = total.setScale(6, RoundingMode.HALF_UP);
                    List<Position> weighted = positions.stream()
                            .map(p -> new Position(p.mint(), p.symbol(), p.amount(), p.decimals(), p.priceUsd(), p.valueUsd(),
                                    p.valueUsd() == null || totalUsd.signum() == 0 ? null
                                            : p.valueUsd().multiply(HUNDRED).divide(totalUsd, 4, RoundingMode.HALF_UP),
                                    p.stable(), p.nativeSol(), p.priceSource()))
                            .sorted((a, b) -> nz(b.valueUsd()).compareTo(nz(a.valueUsd())))
                            .toList();
                    String source = sources.isEmpty() ? "none" : String.join("+", sources);
                    log.info("portfolio_valued walletId={} cluster={} positions={} totalUsd={} priceSource={}",
                            wallet.id(), wallet.cluster().id(), weighted.size(), totalUsd, source);
                    return new PortfolioSnapshot(UUID.randomUUID(), wallet.id(), clock.instant(), totalUsd, weighted, source, txCount);
                });
    }

    static PortfolioDiff diff(PortfolioSnapshot prev, PortfolioSnapshot curr) {
        Map<String, Position> before = new LinkedHashMap<>();
        prev.positions().forEach(p -> before.put(p.symbol(), p));
        Map<String, Position> after = new LinkedHashMap<>();
        curr.positions().forEach(p -> after.put(p.symbol(), p));
        Set<String> symbols = new LinkedHashSet<>(after.keySet());
        symbols.addAll(before.keySet());

        List<PortfolioChange> changes = new ArrayList<>();
        String largest = null;
        BigDecimal largestDelta = BigDecimal.ZERO;
        for (String s : symbols) {
            Position b = before.get(s);
            Position a = after.get(s);
            BigDecimal wb = b == null ? BigDecimal.ZERO : nz(b.weightPct());
            BigDecimal wa = a == null ? BigDecimal.ZERO : nz(a.weightPct());
            BigDecimal delta = wa.subtract(wb);
            boolean changed = b == null || a == null
                    || nz(b.amount()).compareTo(nz(a.amount())) != 0
                    || nz(b.valueUsd()).compareTo(nz(a.valueUsd())) != 0;
            if (changed) {
                changes.add(new PortfolioChange(s,
                        b == null ? null : b.amount(), a == null ? null : a.amount(),
                        b == null ? null : b.valueUsd(), a == null ? null : a.valueUsd(),
                        b == null ? null : b.weightPct(), a == null ? null : a.weightPct(), delta));
            }
            if (delta.abs().compareTo(largestDelta.abs()) > 0) {
                largestDelta = delta;
                largest = s;
            }
        }
        BigDecimal tb = nz(prev.totalUsd());
        BigDecimal ta = nz(curr.totalUsd());
        BigDecimal pct = tb.signum() == 0 ? null : ta.subtract(tb).multiply(HUNDRED).divide(tb, 4, RoundingMode.HALF_UP);
        return new PortfolioDiff(prev.capturedAt(), curr.capturedAt(), tb, ta, pct, changes, largest, largestDelta);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
