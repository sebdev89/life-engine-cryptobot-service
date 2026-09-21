package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.application.receipt.ReceiptProperties;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.domain.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptKind;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AdvisorMessageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The {@code REUSES} edge of Endgame §19 step 3 (KAN-393): a new {@code STRATEGY} that was not
 * created from a fresh advisor run may declare it <em>reused</em> the wallet's most recent
 * {@code MARKET_ANALYSIS} — if, and only if, that analysis is younger than the reuse window and
 * talks about an asset the intent touches. Both conditions are checked against stored facts (the
 * receipt's timestamp; the assistant turn's structured {@code suggestedActions}), never inferred:
 * an analysis whose assets cannot be read is not reused.
 *
 * <p>Why an edge and not a copy: reuse is the signal that an artifact was worth something to a
 * later decision (§9 "reutilización prueba relevancia"). The edge is in the child's hash, so it
 * cannot be added or removed afterwards.
 */
@Component
public class AnalysisReuse {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReuse.class);

    /** What a strategy reused: the analysis receipt, the run it came from, when it was made and the assets it mentioned. */
    public record Reused(String receiptHash, UUID runId, Instant analysedAt, Set<String> assets) {}

    private final ReceiptService receipts;
    private final AdvisorMessageRepository messages;
    private final Duration window;
    private final Clock clock;

    @Autowired
    public AnalysisReuse(ReceiptService receipts, AdvisorMessageRepository messages, ReceiptProperties props) {
        this(receipts, messages, props.reuseWindow(), Clock.systemUTC());
    }

    public AnalysisReuse(ReceiptService receipts, AdvisorMessageRepository messages, Duration window, Clock clock) {
        this.receipts = receipts;
        this.messages = messages;
        this.window = window == null ? ReceiptProperties.DEFAULT_REUSE_WINDOW : window;
        this.clock = clock;
    }

    public Duration window() {
        return window;
    }

    /**
     * The wallet's latest {@code MARKET_ANALYSIS} if it qualifies for {@code intent}; empty otherwise
     * (no analysis, too old, another asset, assets unreadable, reuse disabled). Never errors: a
     * failure to look up is "no reuse", and the strategy is still issued.
     */
    public Mono<Optional<Reused>> find(Wallet wallet, RebalanceIntent intent) {
        if (window.isZero()) {
            return Mono.just(Optional.empty());
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String symbol : intent.targetWeights().keySet()) {
            wanted.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        Instant now = clock.instant();
        return receipts.latest(wallet.id(), ReceiptKind.MARKET_ANALYSIS)
                .filter(analysis -> !analysis.body().completedAt().isBefore(now.minus(window)))
                .flatMap(analysis -> assetsOf(wallet, analysis).map(assets -> {
                    Set<String> overlap = new LinkedHashSet<>(assets);
                    overlap.retainAll(wanted);
                    if (overlap.isEmpty()) {
                        log.debug("analysis_not_reused wallet={} analysis={} assets={} intent={}", wallet.id(), analysis.receiptHash(), assets, wanted);
                        return Optional.<Reused>empty();
                    }
                    UUID runId = analysis.body().runtime() == null || analysis.body().runtime().runId() == null ? null
                            : UUID.fromString(analysis.body().runtime().runId());
                    return Optional.of(new Reused(analysis.receiptHash(), runId, analysis.body().completedAt(), Set.copyOf(assets)));
                }))
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(ex -> {
                    log.warn("analysis_reuse_lookup_failed wallet={} reason={}", wallet.id(), ex.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * The assets the analysis suggested, read from the assistant turn the receipt points at (same
     * {@code runtimeRunId} = the receipt's nonce). The receipt itself carries only the hash of the
     * answer — by design — so the message store is the only place the symbols live.
     */
    @SuppressWarnings("unchecked")
    private Mono<Set<String>> assetsOf(Wallet wallet, IntelligenceReceipt analysis) {
        String runId = analysis.body().nonce();
        return messages.findByWallet(wallet.id(), 200)
                .filter(m -> "assistant".equals(m.role()) && m.runtimeRunId() != null && runId.equals(m.runtimeRunId().toString()))
                .next()
                .map(AnalysisReuse::assetsIn)
                .defaultIfEmpty(Set.of());
    }

    @SuppressWarnings("unchecked")
    static Set<String> assetsIn(AdvisorMessage m) {
        Set<String> assets = new LinkedHashSet<>();
        Object actions = m.structured().get("suggestedActions");
        if (actions instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> action) {
                    Object asset = action.get("asset");
                    if (asset instanceof String s && !s.isBlank()) {
                        assets.add(s.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return assets;
    }
}
