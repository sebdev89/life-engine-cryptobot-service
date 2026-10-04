package io.lifeengine.cryptobot.validator.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal RFC 8785 (JCS) writer for the value tree the policy layer hashes: strings, integers
 * ({@code int}/{@code long}), booleans, lists and string-keyed maps. Nothing else is accepted —
 * a {@code null}, a float or an unknown type is a programming error, not something to "repair"
 * (paper §17: ambiguity ⇒ deny).
 *
 * <p>Same rules as the intent canonicalizer: keys sorted by UTF-16 code units, no
 * whitespace, integers as plain digits, strings escaped only where JSON requires it, with the
 * two-character forms for backspace, tab, newline, form feed, carriage return, quote and
 * backslash, and the lowercase-hex six-character form for the rest of the control range.
 * Package-private on purpose: when the generic canonicalizer lands in {@code domain.intent}
 * this class delegates to it (see an internal ticket handoff).
 */
public final class CanonicalJson {

    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private CanonicalJson() {}

    public static String canonicalize(Map<String, ?> value) {
        StringBuilder sb = new StringBuilder();
        writeMap(sb, value);
        return sb.toString();
    }

    public static byte[] canonicalBytes(Map<String, ?> value) {
        return canonicalize(value).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code sha256:<64 lowercase hex>} of the UTF-8 bytes — the same shape as the intent hash. */
    public static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void write(StringBuilder sb, Object v) {
        if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            if (n > MAX_SAFE_INTEGER || n < -MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("integer outside the safe range: " + n);
            }
            sb.append(n);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> m = (Map<String, ?>) map;
            writeMap(sb, m);
        } else if (v == null) {
            throw new IllegalArgumentException("null is not canonicalizable: omit the field instead");
        } else {
            throw new IllegalArgumentException("not canonicalizable: " + v.getClass().getName());
        }
    }

    private static void writeMap(StringBuilder sb, Map<String, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException("null key");
            }
            if (sorted.put(e.getKey(), e.getValue()) != null) {
                throw new IllegalArgumentException("duplicate key " + e.getKey());
            }
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
