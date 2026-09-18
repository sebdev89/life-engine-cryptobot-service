package io.lifeengine.cryptobot.domain.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden vectors ({@code receipt/vectors-v1.json}, KAN-391). Every canonical string, hash and
 * signature in the file was produced outside this codebase (python {@code json} + {@code hashlib}
 * + {@code cryptography}, see the workspace scratch {@code scripts/kan391-gen-vectors.py}), so
 * this is the check that an implementation written from the spec agrees with this code on:
 *
 * <ul>
 *   <li>the canonical form of a body (RFC 8785: key order, no whitespace, escapes, NFC, sorted parents/inputs),
 *   <li>{@code receiptHash = SHA-256(domain ‖ 0x00 ‖ canonical)},
 *   <li>the Ed25519 signature over the domain-tagged hash (deterministic per RFC 8032, so it is byte-comparable),
 *   <li>the per-tenant salt and the commitments of a guessable text.
 * </ul>
 */
class ReceiptVectorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode load() throws Exception {
        try (InputStream in = ReceiptVectorsTest.class.getResourceAsStream("/receipt/vectors-v1.json")) {
            return JSON.readTree(in);
        }
    }

    private static ReceiptSigningKey key(JsonNode file) {
        return ReceiptSigningKey.fromSecretKey(file.path("key").path("key_id").asText(),
                Base64.getDecoder().decode(file.path("key").path("secret_key_base64").asText()));
    }

    @Test
    void domainsAndKeyAreTheOnesInTheFile() throws Exception {
        JsonNode file = load();
        assertThat(file.path("hash_domain").asText()).isEqualTo(ReceiptCanonicalizer.HASH_DOMAIN);
        assertThat(file.path("signature_domain").asText()).isEqualTo(ReceiptCanonicalizer.SIGNATURE_DOMAIN);
        ReceiptSigningKey key = key(file);
        assertThat(key.publicKeyHex()).isEqualTo(file.path("key").path("public_key_hex").asText());
        assertThat(file.path("vectors")).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void tenantSaltAndCommitmentsMatchTheOnesComputedOutsideTheCode() throws Exception {
        JsonNode file = load();
        byte[] secret = file.path("salt").path("secret_utf8").asText().getBytes(StandardCharsets.UTF_8);
        String tenant = file.path("salt").path("tenant_id").asText();
        assertThat(HexFormat.of().formatHex(Digests.tenantSalt(secret, tenant))).isEqualTo(file.path("salt").path("tenant_salt_hex").asText());
        TenantSalts salts = new TenantSalts(secret);
        for (JsonNode c : file.path("commitments")) {
            assertThat(salts.commit(tenant, c.path("text").asText())).as(c.path("name").asText()).isEqualTo(c.path("commitment").asText());
            if (c.hasNonNull("text_nfd")) {
                assertThat(c.path("text_nfd").asText()).isNotEqualTo(c.path("text").asText());
                assertThat(salts.commit(tenant, c.path("text_nfd").asText())).as("NFD form").isEqualTo(c.path("commitment").asText());
            }
        }
        // Another tenant, same text: another commitment. The salt is what stops "was the question X?".
        assertThat(salts.commit("other-tenant", file.path("commitments").get(0).path("text").asText()))
                .isNotEqualTo(file.path("commitments").get(0).path("commitment").asText());
    }

    @TestFactory
    List<DynamicTest> everyVectorReproduces() throws Exception {
        JsonNode file = load();
        ReceiptSigningKey key = key(file);
        ReceiptService service = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), key);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : file.path("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.path("name").asText(), () -> {
                Map<String, Object> tree = JSON.convertValue(v.path("body"), new TypeReference<Map<String, Object>>() {});
                ReceiptBody body = ReceiptBody.fromMap(tree);

                String canonical = ReceiptCanonicalizer.canonicalJson(body);
                assertThat(canonical).isEqualTo(v.path("canonical").asText());
                String hash = ReceiptCanonicalizer.receiptHash(canonical.getBytes(StandardCharsets.UTF_8));
                assertThat(hash).isEqualTo(v.path("receipt_hash").asText());

                byte[] signature = key.sign(ReceiptCanonicalizer.signingMessage(hash));
                assertThat(Base64.getEncoder().encodeToString(signature)).isEqualTo(v.path("signature_base64").asText());
                assertThat(key.verify(ReceiptCanonicalizer.signingMessage(hash), signature)).isTrue();
                assertThat(ReceiptSigningKey.verify(key.publicKeyBytes(), ReceiptCanonicalizer.signingMessage(hash), signature)).isTrue();

                // The service seals to exactly the same thing.
                IntelligenceReceipt sealed = service.seal(body);
                assertThat(sealed.receiptHash()).isEqualTo(hash);
                assertThat(sealed.canonicalJson()).isEqualTo(canonical);
                assertThat(sealed.signature().signatureBase64()).isEqualTo(v.path("signature_base64").asText());
                assertThat(sealed.signature().keyId()).isEqualTo(key.keyId());

                // And the stored JSON tree round-trips: fromMap(toMap(x)) canonicalises identically.
                assertThat(ReceiptCanonicalizer.canonicalJson(ReceiptBody.fromMap(body.toMap()))).isEqualTo(canonical);
            }));
        }
        return tests;
    }

    @Test
    void theChildHashCommitsToItsParents() throws Exception {
        JsonNode file = load();
        JsonNode risk = file.path("vectors").get(1);
        JsonNode riskOtherParent = file.path("vectors").get(4);
        assertThat(riskOtherParent.path("body").path("output").toString()).isEqualTo(risk.path("body").path("output").toString());
        assertThat(riskOtherParent.path("body").path("parents").toString()).isNotEqualTo(risk.path("body").path("parents").toString());
        assertThat(riskOtherParent.path("receipt_hash").asText()).isNotEqualTo(risk.path("receipt_hash").asText());
    }
}
