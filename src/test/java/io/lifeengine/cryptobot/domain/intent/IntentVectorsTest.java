package io.lifeengine.cryptobot.domain.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Fixed vectors, versioned with the schema ({@code src/test/resources/intent/vectors-v1.json}).
 * The hashes in the file were computed outside the JVM ({@code printf '%s' C | sha256sum}), so
 * this test pins the canonical form against an independent oracle, not against itself.
 */
class IntentVectorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    List<DynamicTest> vectorsV1() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/intent/vectors-v1.json")) {
            root = JSON.readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.path("vectors")) {
            String name = v.path("name").asText();
            String input = JSON.writeValueAsString(v.path("input"));
            String canonical = v.path("canonical").asText();
            String hash = v.path("hash").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                TradingIntent intent = IntentSchema.parse(input);
                assertThat(intent.canonicalJson()).isEqualTo(canonical);
                assertThat(intent.hash().value()).isEqualTo(hash);
                // The file's hash really is SHA-256 of the file's canonical string.
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
                assertThat("sha256:" + HexFormat.of().formatHex(digest)).isEqualTo(hash);
                // Re-parsing the canonical text is a fixed point.
                assertThat(IntentSchema.parse(canonical).hash()).isEqualTo(intent.hash());
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(5);
        return tests;
    }

    @Test
    void theTwoSwapVectorsAreTheSameIntent() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/intent/vectors-v1.json")) {
            root = JSON.readTree(in);
        }
        TradingIntent clean = IntentSchema.parse(JSON.writeValueAsString(root.path("vectors").get(0).path("input")));
        TradingIntent sloppy = IntentSchema.parse(JSON.writeValueAsString(root.path("vectors").get(1).path("input")));
        assertThat(sloppy).isEqualTo(clean);
        assertThat(sloppy.hash()).isEqualTo(clean.hash());
        assertThat(sloppy.hash().toOperationId()).isEqualTo(clean.hash().toOperationId());
    }
}
