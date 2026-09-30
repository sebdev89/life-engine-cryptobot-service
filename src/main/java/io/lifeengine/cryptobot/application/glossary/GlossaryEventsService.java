package io.lifeengine.cryptobot.application.glossary;

import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * turns the glossary events the UI batches into the two Prometheus counters of
 * {@link CryptobotMetrics}: {@code cryptobot_glossary_term_total{term,action}} and
 * {@code cryptobot_glossary_search_total{hit}}.
 *
 * <p>The UI is the only source and it sends nothing about the person: no user, tenant, session,
 * wallet or query text. What arrives is validated here so that a label value is never free text:
 *
 * <ul>
 *   <li>{@code action} ∈ {@code open | search | copy}; anything else is rejected.</li>
 *   <li>{@code term}: trimmed, inner whitespace collapsed, 1–{@value #MAX_TERM_LENGTH} chars of
 *       letters, digits, spaces and the punctuation glossary terms use ({@code . , + / ( ) ' - & _ % ! < >}).
 *       Required for {@code open} and {@code copy}; for {@code search} it is the term the query
 *       resolved to (only when it hit).</li>
 *   <li>{@code search} always increments {@code cryptobot_glossary_search_total{hit}}; {@code hit}
 *       defaults to "a valid term came along".</li>
 * </ul>
 *
 * The service does not carry the glossary itself (that list lives in the UI), so the
 * cardinality bound is the cap in {@link CryptobotMetrics#glossaryLabel}, not an allow-list.
 * A rejected event is counted and dropped: the endpoint never fails the batch for one bad row.
 */
@Service
public class GlossaryEventsService {

    public static final int MAX_TERM_LENGTH = 64;
    public static final int MAX_BATCH = 100;
    public static final Set<String> ACTIONS = Set.of("open", "search", "copy");

    // Verified against the 864 terms of glossary-data.ts: `program_id`, `51% attack`, `require!`,
    // `Signer<'info>` and `Result<T,E>` are real terms, hence _ % ! < > in the tail.
    private static final Pattern TERM = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,+/()'’&_%!<>-]{0," + (MAX_TERM_LENGTH - 1) + "}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** One interaction as the UI reports it. {@code hit} only means something for {@code search}. */
    public record GlossaryEvent(String term, String action, Boolean hit) {}

    /** What the batch did: how many rows fed a counter and how many were dropped as malformed. */
    public record Outcome(int accepted, int rejected) {}

    private final CryptobotMetrics metrics;

    public GlossaryEventsService(CryptobotMetrics metrics) {
        this.metrics = metrics;
    }

    public Outcome accept(List<GlossaryEvent> events) {
        int accepted = 0;
        int rejected = 0;
        for (GlossaryEvent e : events) {
            if (record(e)) {
                accepted++;
            } else {
                rejected++;
            }
        }
        return new Outcome(accepted, rejected);
    }

    private boolean record(GlossaryEvent e) {
        if (e == null || e.action() == null) {
            return false;
        }
        String action = e.action().trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return false;
        }
        String term = normalizeTerm(e.term());
        if ("search".equals(action)) {
            boolean hit = e.hit() != null ? e.hit() : term != null;
            metrics.glossarySearch(hit);
            if (hit && term != null) {
                metrics.glossaryTerm(term, action);
            }
            return true;
        }
        if (term == null) {
            return false;
        }
        metrics.glossaryTerm(term, action);
        return true;
    }

    /** The term as a bounded label value, or {@code null} if it is not something a glossary term looks like. */
    static String normalizeTerm(String raw) {
        if (raw == null) {
            return null;
        }
        String t = WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (t.isEmpty() || t.length() > MAX_TERM_LENGTH || !TERM.matcher(t).matches()) {
            return null;
        }
        return t;
    }
}
