package io.lifeengine.cryptobot.core.receipts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hash primitives of the receipt layer, written once (Endgame §6):
 *
 * <ul>
 *   <li>{@link #sha256(byte[])} — plain SHA-256, rendered {@code sha256:<64 lower-case hex>} like
 *       the intent hash and the policy hash, so every hash in the system is
 *       self-describing and recomputable with {@code sha256sum}.
 *   <li>{@link #domainSeparated(String, byte[])} — {@code SHA-256(domain ‖ 0x00 ‖ bytes)}: the
 *       receipt id. The tag makes a receipt hash unequal to the hash of its own canonical JSON and
 *       to any other structure that happens to serialise to the same bytes.
 *   <li>{@link #commitment(byte[], byte[])} — {@code SHA-256(salt ‖ 0x00 ‖ bytes)}: what a
 *       guessable text (the user's question, the prompt) becomes before it leaves the service.
 *       Without the salt a verifier cannot confirm "was the prompt X?" by brute force; with it —
 *       revealed in an audit — the commitment opens exactly.
 *   <li>{@link #tenantSalt(byte[], String)} — {@code HMAC-SHA256(secret, tenantId)}: one salt per
 *       tenant, derived, never stored, never on-chain.
 * </ul>
 */
public final class Digests {

    public static final String PREFIX = "sha256:";
    private static final Pattern FORMAT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final byte SEPARATOR = 0x00;

    private Digests() {}

    public static String sha256(byte[] bytes) {
        return render(digest(bytes));
    }

    public static String sha256(String utf8) {
        return sha256(utf8.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code sha256:…} of {@code domain ‖ 0x00 ‖ bytes}. */
    public static String domainSeparated(String domain, byte[] bytes) {
        return render(domainSeparatedBytes(domain, bytes));
    }

    public static byte[] domainSeparatedBytes(String domain, byte[] bytes) {
        byte[] tag = domain.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[tag.length + 1 + bytes.length];
        System.arraycopy(tag, 0, message, 0, tag.length);
        message[tag.length] = SEPARATOR;
        System.arraycopy(bytes, 0, message, tag.length + 1, bytes.length);
        return digest(message);
    }

    /** {@code sha256:…} of {@code salt ‖ 0x00 ‖ bytes}; with an empty salt it is a plain hash of the bytes. */
    public static String commitment(byte[] salt, byte[] bytes) {
        byte[] message = new byte[salt.length + 1 + bytes.length];
        System.arraycopy(salt, 0, message, 0, salt.length);
        message[salt.length] = SEPARATOR;
        System.arraycopy(bytes, 0, message, salt.length + 1, bytes.length);
        return render(digest(message));
    }

    /** 32-byte salt of one tenant: {@code HMAC-SHA256(secret, tenantId)}. */
    public static byte[] tenantSalt(byte[] secret, String tenantId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(tenantId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean isHash(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    public static String requireHash(String field, String value) {
        String v = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (!isHash(v)) {
            throw new IllegalArgumentException(field + " must be sha256:<64 lower-case hex>, got " + value);
        }
        return v;
    }

    /** The 32 raw bytes of a {@code sha256:…} string. */
    public static byte[] bytes(String hash) {
        return HexFormat.of().parseHex(requireHash("hash", hash).substring(PREFIX.length()));
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String render(byte[] digest) {
        return PREFIX + HexFormat.of().formatHex(digest);
    }
}
