package io.lifeengine.cryptobot.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes the JSON an agent would emit: <em>not</em> canonical on purpose. Keys in a random
 * order, integers sometimes as numbers and sometimes as strings of digits, random whitespace —
 * everything the schema accepts and the canonicalizer must normalize before hashing. A
 * {@link Raw} value is written verbatim, which is how the serialization attacks are built.
 */
final class Json {

    /** Verbatim JSON text (an attack payload, a float, a duplicate key…). */
    record Raw(String text) {}

    private Json() {}

    static String write(Map<String, Object> fields, Random rnd) {
        List<String> keys = new ArrayList<>(fields.keySet());
        Collections.shuffle(keys, rnd);
        boolean pretty = rnd.nextBoolean();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                sb.append("\n  ");
            }
            sb.append('"').append(escape(k)).append('"').append(pretty ? ": " : ":");
            value(sb, fields.get(k), rnd, pretty);
        }
        if (pretty) {
            sb.append('\n');
        }
        return sb.append('}').toString();
    }

    /** Canonical-ish (insertion order, compact) — for attacks that need a stable shape. */
    static String writeStable(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            value(sb, e.getValue(), null, false);
        }
        return sb.append('}').toString();
    }

    private static void value(StringBuilder sb, Object v, Random rnd, boolean pretty) {
        if (v instanceof Raw r) {
            sb.append(r.text());
        } else if (v instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            boolean quoted = rnd != null && rnd.nextInt(3) == 0;
            if (quoted) {
                sb.append('"').append(v).append('"');
            } else {
                sb.append(v);
            }
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                value(sb, e.getValue(), rnd, pretty);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                value(sb, l.get(i), rnd, pretty);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported: " + v.getClass());
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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
        return sb.toString();
    }
}
