package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-604 (audit §17 G15, §26, §27 demo 10): "tampered receipt fails verify" through the real HTTP
 * API, not just {@code ReceiptServiceTest}'s direct calls into {@link ReceiptService#verify}.
 *
 * <h2>Why this does not tamper "the body of the request"</h2>
 *
 * {@code POST /api/cryptobot/receipts/{receiptHash}/verify} ({@link ReceiptsController#verify})
 * takes no request body — it re-reads whatever is stored under {@code receiptHash} and re-verifies
 * that. There is no route yet that accepts a receipt (or a body) from the caller and verifies
 * <em>that</em>; that is KAN-597 ("additive API… body-based {@code /receipts/verify}"), phase 2 of
 * the mandate, not built here (KAN-604's scope is tests, not new endpoints). So "1 byte altered in
 * the body" is exercised the only way today's endpoint can observe it: the byte is flipped in what
 * is <em>persisted</em> (as if storage or the wire between the service and its own database had
 * corrupted it), and the same {@code POST …/verify} the API exposes today is asked to catch it.
 * {@link ReceiptService#verify(IntelligenceReceipt)} recomputes the hash and the body's canonical
 * form from what is stored and compares — tampering the stored {@code canonicalJson} alone (without
 * touching {@code receiptHash}, {@code body} or {@code signature}) is enough to fail both
 * {@code hashMatchesCanonical} and {@code bodyMatchesCanonical}, which is enough to fail
 * {@code valid}, even though the signature (which only covers the hash) still verifies.
 */
@SpringBootTest(classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ReceiptTamperVerifyApiTest {

    @Autowired private WebTestClient web;
    @Autowired private ReceiptService receipts;

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
    }

    private static ReceiptBody body(UUID owner, String nonce) {
        return new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, owner.toString(), owner.toString(), "it-agent@1", List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot-" + nonce))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(Digests.sha256("output-of-" + nonce), "test/1", null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L0_SIGNED, Instant.parse("2026-09-21T12:00:00Z"), Instant.parse("2026-09-21T12:00:01Z"), nonce, null);
    }

    private static String flipOneByte(String s) {
        int mid = s.length() / 2;
        char c = s.charAt(mid);
        char flipped = c == '0' ? '1' : '0';
        return s.substring(0, mid) + flipped + s.substring(mid + 1);
    }

    @Test
    @DisplayName("a receipt tampered at rest (one byte flipped in the stored canonical JSON) fails POST /receipts/{hash}/verify — hash and body checks fail even though the signature (over the hash alone) still verifies")
    void tamperedReceiptFailsVerifyThroughTheRealApi() {
        UUID owner = UUID.randomUUID();
        String token = bearer(owner);
        ReceiptBody b = body(owner, "kan604-" + UUID.randomUUID());
        IntelligenceReceipt issued = receipts.issue(ReceiptDraft.of(b)).block();
        assertThat(issued).isNotNull();

        // Sanity: the untampered receipt verifies through the same API.
        web.post().uri("/api/cryptobot/receipts/" + issued.receiptHash() + "/verify").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(true)
                .jsonPath("$.hashMatchesCanonical").isEqualTo(true)
                .jsonPath("$.bodyMatchesCanonical").isEqualTo(true)
                .jsonPath("$.signatureValid").isEqualTo(true);

        // Tamper what is stored, in place, leaving receiptHash/body/signature/anchor/createdAt untouched:
        // the storage-level equivalent of "1 byte altered in the body" for an endpoint that has no
        // caller-supplied body to alter (see class Javadoc — KAN-597 is the body-based route).
        String tampered = flipOneByte(issued.canonicalJson());
        assertThat(tampered).isNotEqualTo(issued.canonicalJson());
        IntelligenceReceipt corrupted = new IntelligenceReceipt(issued.receiptHash(), issued.domain(), issued.body(), tampered, issued.signature(),
                issued.anchor(), issued.createdAt());
        InMemoryControlPlaneRepositories.RECEIPTS.put(issued.receiptHash(), corrupted);

        web.post().uri("/api/cryptobot/receipts/" + issued.receiptHash() + "/verify").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(false)
                .jsonPath("$.hashMatchesCanonical").isEqualTo(false)
                .jsonPath("$.bodyMatchesCanonical").isEqualTo(false)
                .jsonPath("$.signatureValid").isEqualTo(true)
                .jsonPath("$.parentsPresent").isEqualTo(true);
    }

    private static String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", "operator@test.local")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
