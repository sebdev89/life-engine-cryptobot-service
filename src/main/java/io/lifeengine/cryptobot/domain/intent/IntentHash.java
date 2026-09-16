package io.lifeengine.cryptobot.domain.intent;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code H_I = SHA-256(C)} over the canonical bytes of an intent (paper §7). Rendered as
 * {@code sha256:<64 lower-case hex>} so it is self-describing wherever it travels (rows, events,
 * receipts, memos) and anyone can recompute it with {@code sha256sum} on the canonical string.
 *
 * <p>The hash is the identity of the intent through the whole pipeline. Where a {@link UUID} is
 * required (the execution {@code operationId} of KAN-403), it is the first 128 bits of the hash
 * — deterministic, so re-executing the same intent is idempotent by construction.
 */
public record IntentHash(String value) {

    public static final String PREFIX = "sha256:";
    private static final Pattern FORMAT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public IntentHash {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IntentSchemaViolation("intent_hash", "must be sha256:<64 lower-case hex>, got " + value);
        }
    }

    public static IntentHash of(byte[] canonicalBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            return new IntentHash(PREFIX + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static IntentHash parse(String value) {
        return new IntentHash(value == null ? null : TradingIntent.text("intent_hash", value).toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(value.substring(PREFIX.length()));
    }

    public String hex() {
        return value.substring(PREFIX.length());
    }

    /** The first 16 bytes of the digest as a UUID: the execution {@code operationId} of this intent. */
    public UUID toOperationId() {
        ByteBuffer buf = ByteBuffer.wrap(bytes(), 0, 16);
        return new UUID(buf.getLong(), buf.getLong());
    }

    @Override
    public String toString() {
        return value;
    }
}
