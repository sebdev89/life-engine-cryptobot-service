package io.lifeengine.cryptobot.domain.receipt;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of the memo transaction (Endgame §11): {@code ir/1 root=<sha256:…> n=<count> ts=<ISO-8601 Z>}.
 *
 * <p>Everything a verifier needs is in the memo: the schema of the receipts, the Merkle root, how
 * many receipts it covers and when the batch was closed. Nothing else: no tenant, no hash of an
 * individual receipt, no prompt. The format is deliberately a fixed regular expression — both this
 * service and the isolated signer parse it, and the signer refuses to sign a memo that does not
 * match it exactly (a memo transaction is the only non-transfer the signer signs).
 */
public record AnchorMemo(String root, int count, Instant ts) {

    public static final String SCHEMA = "ir/1";
    public static final int MAX_BYTES = 128;
    private static final Pattern FORMAT = Pattern.compile(
            "^ir/1 root=(sha256:[0-9a-f]{64}) n=([1-9][0-9]{0,8}) ts=([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z)$");

    public AnchorMemo {
        root = Digests.requireHash("root", root);
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        ts = ts.truncatedTo(ChronoUnit.SECONDS);
    }

    public String text() {
        return SCHEMA + " root=" + root + " n=" + count + " ts=" + DateTimeFormatter.ISO_INSTANT.format(ts);
    }

    public static Optional<AnchorMemo> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AnchorMemo(m.group(1), Integer.parseInt(m.group(2)), Instant.parse(m.group(3))));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static boolean matches(String text) {
        return parse(text).isPresent();
    }
}
