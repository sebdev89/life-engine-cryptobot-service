package io.lifeengine.cryptobot.domain.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonCanonicalizerTest {

    @Test
    void sortsMembersByUtf16CodeUnits_rfc8785_section3_2_3() {
        // The RFC's own example: the emoji (surrogate pair D83D DE00) sorts before U+FB33, and
        // U+0080 is written literally because only controls below U+0020 are escaped.
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("\u20ac", "Euro Sign");
        in.put("\r", "Carriage Return");
        in.put("\ufb33", "Hebrew Letter Dalet With Dagesh");
        in.put("1", "One");
        in.put("\ud83d\ude00", "Emoji: Grinning Face");
        in.put("\u0080", "Control");
        in.put("\u00f6", "Latin Small Letter O With Diaeresis");

        assertThat(JsonCanonicalizer.canonicalize(in)).isEqualTo(
                "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\",\"\u00f6\":\"Latin Small Letter O With Diaeresis\","
                        + "\"\u20ac\":\"Euro Sign\",\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}");
    }

    @Test
    void escapesExactlyTheRfcSet_andOtherControlsAsLowerCaseHex() {
        String s = "q\"b\\s\bf\fn\nr\rt\tz\u0001\u001f\u007f";
        assertThat(JsonCanonicalizer.canonicalize(s))
                .isEqualTo("\"q\\\"b\\\\s\\bf\\fn\\nr\\rt\\tz\\u0001\\u001f\u007f\"");
    }

    @Test
    void sameTreeInDifferentInsertionOrderGivesSameBytes() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", List.of("x", Map.of("k", true)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", List.of("x", Map.of("k", true)));
        b.put("z", 1L);
        assertThat(JsonCanonicalizer.canonicalBytes(a)).isEqualTo(JsonCanonicalizer.canonicalBytes(b));
        assertThat(JsonCanonicalizer.canonicalize(a)).isEqualTo("{\"a\":[\"x\",{\"k\":true}],\"z\":1}");
    }

    @Test
    void normalizesStringValuesToNfc() {
        String composed = "caf\u00e9";          // é as one code point
        String decomposed = "cafe\u0301";       // e + combining acute
        assertThat(JsonCanonicalizer.canonicalize(Map.of("s", composed)))
                .isEqualTo(JsonCanonicalizer.canonicalize(Map.of("s", decomposed)))
                .isEqualTo("{\"s\":\"caf\u00e9\"}");
    }

    @Test
    void enumsAreWrittenAsTheirName() {
        assertThat(JsonCanonicalizer.canonicalize(Map.of("action", IntentAction.SWAP))).isEqualTo("{\"action\":\"SWAP\"}");
    }

    @Test
    void refusesNull_floats_decimals_andUnsafeIntegers() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(withNull))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("$.a").hasMessageContaining("null");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Map.of("a", 1.5)))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("floating-point");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Map.of("a", new BigDecimal("1.0"))))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("floating-point");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Map.of("a", JsonCanonicalizer.MAX_SAFE_INTEGER + 1)))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("safe range");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(List.of(BigInteger.TWO.pow(64))))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("$[0]");
        assertThat(JsonCanonicalizer.canonicalize(Map.of("a", JsonCanonicalizer.MAX_SAFE_INTEGER, "b", -JsonCanonicalizer.MAX_SAFE_INTEGER)))
                .isEqualTo("{\"a\":9007199254740991,\"b\":-9007199254740991}");
    }

    @Test
    void refusesNonStringKeysAndUnknownTypes() {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Map.of(1, "x")))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("keys must be strings");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Map.of("t", new Object())))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("unsupported value type");
    }
}
