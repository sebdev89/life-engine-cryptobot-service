package io.lifeengine.cryptobot.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.application.glossary.GlossaryEventsService.GlossaryEvent;
import io.lifeengine.cryptobot.application.glossary.GlossaryEventsService.Outcome;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KAN-353: what the UI sends becomes bounded counters; anything that is not a glossary event is dropped, never a label. */
class GlossaryEventsServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GlossaryEventsService service = new GlossaryEventsService(new CryptobotMetrics(registry, Set.of()));

    @Test
    @DisplayName("open / copy increment the term counter under the canonical term and action")
    void openAndCopyCountPerTermAndAction() {
        Outcome out = service.accept(List.of(
                new GlossaryEvent("PDA", "open", null),
                new GlossaryEvent("PDA", "open", null),
                new GlossaryEvent("  PDA ", "OPEN", null),
                new GlossaryEvent("slippage", "copy", null)));

        assertThat(out).isEqualTo(new Outcome(4, 0));
        assertThat(termCount("PDA", "open")).isEqualTo(3);
        assertThat(termCount("slippage", "copy")).isEqualTo(1);
    }

    @Test
    @DisplayName("search counts hit/miss; a hit also credits the term it resolved to")
    void searchCountsHitAndMiss() {
        Outcome out = service.accept(List.of(
                new GlossaryEvent("blockhash", "search", true),
                new GlossaryEvent(null, "search", false),
                new GlossaryEvent("zzzzqqqq", "search", false),
                new GlossaryEvent("PDA", "search", null)));

        assertThat(out).isEqualTo(new Outcome(4, 0));
        assertThat(registry.get("cryptobot.glossary.search").tag("hit", "true").counter().count()).isEqualTo(2);
        assertThat(registry.get("cryptobot.glossary.search").tag("hit", "false").counter().count()).isEqualTo(2);
        assertThat(termCount("blockhash", "search")).isEqualTo(1);
        assertThat(termCount("PDA", "search")).isEqualTo(1);
        // a miss never creates a term series, whatever text came along
        assertThat(registry.find("cryptobot.glossary.term").tag("term", "zzzzqqqq").counter()).isNull();
    }

    @Test
    @DisplayName("unknown actions, missing terms and free text are rejected and never become labels")
    void malformedRowsAreRejectedNotLabelled() {
        String tooLong = "a".repeat(GlossaryEventsService.MAX_TERM_LENGTH + 1);
        Outcome out = service.accept(Arrays.asList(
                new GlossaryEvent("PDA", "delete", null),
                new GlossaryEvent(null, "open", null),
                new GlossaryEvent("   ", "copy", null),
                new GlossaryEvent(tooLong, "open", null),
                new GlossaryEvent("<script>alert(1)</script>", "open", null),
                new GlossaryEvent("PDA\ninjected=\"x\"", "open", null),
                new GlossaryEvent("PDA", null, null),
                null,
                new GlossaryEvent("Proof of History", "open", null)));

        assertThat(out).isEqualTo(new Outcome(1, 8));
        Set<String> terms = registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("cryptobot.glossary.term"))
                .flatMap(id -> id.getTags().stream())
                .filter(t -> t.getKey().equals("term"))
                .map(Tag::getValue)
                .collect(Collectors.toSet());
        assertThat(terms).containsExactly("Proof of History");
    }

    @Test
    @DisplayName("terms keep the punctuation the glossary uses and collapse inner whitespace")
    void normalisationKeepsGlossaryPunctuation() {
        assertThat(GlossaryEventsService.normalizeTerm("  Cross-Program   Invocation (CPI) ")).isEqualTo("Cross-Program Invocation (CPI)");
        assertThat(GlossaryEventsService.normalizeTerm("Ed25519")).isEqualTo("Ed25519");
        assertThat(GlossaryEventsService.normalizeTerm("staking líquido")).isEqualTo("staking líquido");
        assertThat(GlossaryEventsService.normalizeTerm("SOL/USDC")).isEqualTo("SOL/USDC");
        assertThat(GlossaryEventsService.normalizeTerm("-leading dash")).isNull();
        assertThat(GlossaryEventsService.normalizeTerm("term=\"x\"")).isNull();
        assertThat(GlossaryEventsService.normalizeTerm("")).isNull();
        assertThat(GlossaryEventsService.normalizeTerm(null)).isNull();
    }

    private double termCount(String term, String action) {
        return registry.get("cryptobot.glossary.term").tag("term", term).tag("action", action).counter().count();
    }
}
