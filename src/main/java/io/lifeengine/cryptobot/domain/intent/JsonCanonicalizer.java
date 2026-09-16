package io.lifeengine.cryptobot.domain.intent;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON Canonicalization Scheme (RFC 8785) for the subset of JSON an intent may contain: objects,
 * arrays, strings, booleans and <em>integers within the IEEE-754 safe range</em>. Same value tree,
 * same bytes, on any JVM and in any language that implements the RFC.
 *
 * <ul>
 *   <li>Object members sorted by the UTF-16 code units of their names (RFC 8785 §3.2.3), which is
 *       exactly {@link String#compareTo}. Member names are written verbatim: schema field names are
 *       ASCII and are not user data.
 *   <li>No whitespace. Strings escaped per §3.2.2.2: quote, backslash, backspace, form feed, line
 *       feed, carriage return and tab with their short escapes; any other control character below
 *       U+0020 as a six-character lower-case hex escape; everything else literal UTF-8.
 *   <li>String <em>values</em> are Unicode-normalized (NFC) before serialization. This is an
 *       extension over the RFC: the intent is about what the text means, and a precomposed
 *       character must hash like its decomposed form whether the LLM emitted one code point or two.
 *   <li>Numbers: integers only, and only within the safe range (2^53 - 1 in magnitude). Larger
 *       integers (token amounts in minimal units) travel as <b>strings</b>; the schema decides
 *       which, not the serializer. A floating-point or decimal value is a bug and is refused, never
 *       rounded.
 *   <li>{@code null} is refused: an optional field that is absent is <em>omitted</em>, so
 *       "field: null" and "no field" cannot produce two different hashes for the same intent.
 * </ul>
 */
public final class JsonCanonicalizer {

    /** 2^53 - 1: the largest integer every JSON parser is required to round-trip exactly. */
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final BigInteger MAX_SAFE = BigInteger.valueOf(MAX_SAFE_INTEGER);

    private JsonCanonicalizer() {}

    public static byte[] canonicalBytes(Object value) {
        return canonicalize(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalize(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value, "$");
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, String path) {
        if (value == null) {
            throw new IntentSchemaViolation(path, "null is not canonical; omit the field instead");
        }
        if (value instanceof Map<?, ?> map) {
            writeObject(out, map, path);
        } else if (value instanceof Collection<?> list) {
            writeArray(out, list, path);
        } else if (value instanceof CharSequence s) {
            writeString(out, Normalizer.normalize(s, Normalizer.Form.NFC));
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Enum<?> e) {
            writeString(out, e.name());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            writeInteger(out, BigInteger.valueOf(((Number) value).longValue()), path);
        } else if (value instanceof BigInteger big) {
            writeInteger(out, big, path);
        } else if (value instanceof Number) {
            throw new IntentSchemaViolation(path, "floating-point / decimal values are not canonical; use an integer or a decimal string");
        } else {
            throw new IntentSchemaViolation(path, "unsupported value type " + value.getClass().getSimpleName());
        }
    }

    private static void writeInteger(StringBuilder out, BigInteger n, String path) {
        if (n.abs().compareTo(MAX_SAFE) > 0) {
            throw new IntentSchemaViolation(path, "integer outside the safe range must be a string: " + n);
        }
        out.append(n);
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, String path) {
        List<String> keys = new ArrayList<>(map.size());
        for (Object k : map.keySet()) {
            if (!(k instanceof String key)) {
                throw new IntentSchemaViolation(path, "object keys must be strings");
            }
            keys.add(key);
        }
        // String.compareTo compares UTF-16 code units: the RFC 8785 §3.2.3 order, including the
        // surrogate-pair case where U+1F600 sorts before U+FF5E.
        keys.sort(null);
        out.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, key);
            out.append(':');
            write(out, map.get(key), path + "." + key);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Collection<?> list, String path) {
        out.append('[');
        int i = 0;
        for (Object item : list) {
            if (i > 0) {
                out.append(',');
            }
            write(out, item, path + "[" + i + "]");
            i++;
        }
        out.append(']');
    }

    static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
