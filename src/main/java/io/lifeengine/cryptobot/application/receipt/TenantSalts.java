package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.domain.receipt.Digests;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * The per-tenant salt of the privacy commitments (Endgame §6, "Privacidad"): a short guessable
 * text — the user's question, the prompt — is hashed as {@code H(salt ‖ 0x00 ‖ text)} so nobody
 * outside the service can confirm "was the prompt X?" by trying candidates. The salt is derived
 * ({@code HMAC-SHA256(secret, tenantId)}), never stored, and revealed only in an audit.
 */
public final class TenantSalts {

    private final byte[] secret;

    public TenantSalts(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("salt secret is required");
        }
        this.secret = secret.clone();
    }

    public byte[] saltOf(String tenantId) {
        return Digests.tenantSalt(secret, tenantId);
    }

    /**
     * {@code sha256:…} commitment of a text under the tenant's salt. The text is NFC-normalised
     * first, as the canonicaliser does with every string: a question typed with a precomposed
     * {@code ñ} and the same question with {@code n + U+0303} are the same idea and commit to the
     * same value.
     */
    public String commit(String tenantId, String text) {
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return Digests.commitment(saltOf(tenantId), nfc.getBytes(StandardCharsets.UTF_8));
    }
}
