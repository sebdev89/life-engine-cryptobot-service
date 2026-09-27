package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.trading.advisor.AdvisorMessage;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.trading.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a strategy may declare {@code REUSES} over an earlier {@code MARKET_ANALYSIS} (KAN-393,
 * Endgame §19 step 3): same wallet, younger than the window, and about an asset the intent
 * touches — every condition read from stored facts.
 */
class AnalysisReuseTest {

    static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private final Wallet wallet = Fixtures.wallet(SolanaCluster.DEVNET);
    private ReceiptService receipts;
    private AnalysisReuse reuse;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("unit-key"));
        reuse = new AnalysisReuse(receipts, InMemoryControlPlaneRepositories.messages(), Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A MARKET_ANALYSIS receipt completed at {@code at} whose assistant turn suggested {@code assets}. */
    private IntelligenceReceipt analysis(Instant at, List<String> assets) {
        UUID runId = UUID.randomUUID();
        List<Map<String, Object>> actions = assets.stream().<Map<String, Object>>map(a -> Map.of("action", "REBALANCE", "asset", a, "targetWeightPct", 50)).toList();
        InMemoryControlPlaneRepositories.messages().insert(new AdvisorMessage(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), "assistant", "…",
                Map.of("suggestedActions", actions, "model", "qwen3:14b"), runId, at)).block();
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.MARKET_ANALYSIS, wallet.ownerUserId().toString(), wallet.ownerUserId().toString(), "market-agent@1",
                List.of(), List.of(), null, new ReceiptBody.Model("qwen3:14b", "life-engine-runtime", null, null), null,
                new ReceiptBody.RuntimeRef(runId.toString(), null, null, null, null), Map.of(),
                new ReceiptBody.Output(Digests.sha256("answer-" + runId), "advisor-answer/1", null), new ReceiptBody.Compute(10L, 5L, 25, 100L), null,
                ReproducibilityLevel.L0_SIGNED, at.minusSeconds(10), at, runId.toString(), new ReceiptBody.Refs(wallet.id().toString(), null, null));
        // Stored with created_at = `at` (the store orders "latest" by created_at), signed by the real service.
        IntelligenceReceipt sealed = receipts.seal(body);
        return InMemoryControlPlaneRepositories.receipts().insert(new IntelligenceReceipt(sealed.receiptHash(), sealed.domain(), sealed.body(),
                sealed.canonicalJson(), sealed.signature(), null, at), List.of(), List.of(), null).block();
    }

    private static RebalanceIntent intent(String asset) {
        return new RebalanceIntent(Map.of(asset, new BigDecimal("50")), "USDC");
    }

    @Test
    @DisplayName("a fresh analysis about the same asset is reused")
    void reused() {
        IntelligenceReceipt a = analysis(NOW.minus(Duration.ofMinutes(10)), List.of("SOL"));
        Optional<AnalysisReuse.Reused> r = reuse.find(wallet, intent("sol")).block();
        assertThat(r).isPresent();
        assertThat(r.get().receiptHash()).isEqualTo(a.receiptHash());
        assertThat(r.get().assets()).containsExactly("SOL");
        assertThat(r.get().analysedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(r.get().runId()).isEqualTo(UUID.fromString(a.body().nonce()));
    }

    @Test
    @DisplayName("too old, another asset, no analysis, or unreadable assets ⇒ not reused")
    void notReused() {
        assertThat(reuse.find(wallet, intent("SOL")).block()).as("no analysis at all").isEmpty();

        analysis(NOW.minus(Duration.ofMinutes(61)), List.of("SOL"));
        assertThat(reuse.find(wallet, intent("SOL")).block()).as("older than the window").isEmpty();

        InMemoryControlPlaneRepositories.reset();
        analysis(NOW.minus(Duration.ofMinutes(5)), List.of("JUP"));
        assertThat(reuse.find(wallet, intent("SOL")).block()).as("about another asset").isEmpty();

        InMemoryControlPlaneRepositories.reset();
        analysis(NOW.minus(Duration.ofMinutes(5)), List.of());
        assertThat(reuse.find(wallet, intent("SOL")).block()).as("the answer named no asset: nothing to match, nothing claimed").isEmpty();

        InMemoryControlPlaneRepositories.reset();
        IntelligenceReceipt a = analysis(NOW.minus(Duration.ofMinutes(5)), List.of("SOL"));
        InMemoryControlPlaneRepositories.MESSAGES.clear();
        assertThat(reuse.find(wallet, intent("SOL")).block()).as("receipt without its assistant turn: assets unknown ⇒ no reuse (" + a.receiptHash() + ")").isEmpty();
    }

    @Test
    @DisplayName("the latest analysis is the one considered; the window can be disabled")
    void latestAndDisabled() {
        analysis(NOW.minus(Duration.ofMinutes(50)), List.of("SOL"));
        IntelligenceReceipt latest = analysis(NOW.minus(Duration.ofMinutes(2)), List.of("JUP", "SOL"));
        Optional<AnalysisReuse.Reused> r = reuse.find(wallet, intent("SOL")).block();
        assertThat(r).isPresent();
        assertThat(r.get().receiptHash()).isEqualTo(latest.receiptHash());
        assertThat(r.get().assets()).containsExactlyInAnyOrder("JUP", "SOL");

        AnalysisReuse off = new AnalysisReuse(receipts, InMemoryControlPlaneRepositories.messages(), Duration.ZERO, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(off.find(wallet, intent("SOL")).block()).isEmpty();
        assertThat(off.window()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("assets are read from suggestedActions only, upper-cased, blanks and junk ignored")
    void assetsIn() {
        AdvisorMessage m = new AdvisorMessage(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), "assistant", "…",
                Map.of("suggestedActions", List.of(Map.of("asset", "sol"), Map.of("asset", " "), Map.of("action", "HOLD"), "junk", Map.of("asset", "Jup")),
                        "keyRisks", List.of(Map.of("asset", "USDC"))), UUID.randomUUID(), NOW);
        assertThat(AnalysisReuse.assetsIn(m)).containsExactly("SOL", "JUP");
        assertThat(AnalysisReuse.assetsIn(new AdvisorMessage(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), "assistant", "…", Map.of(), null, NOW))).isEmpty();
    }
}
