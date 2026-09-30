package io.lifeengine.cryptobot.application.receipt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cryptobot.receipts.*}.
 *
 * <ul>
 *   <li>{@code key-id} — name of the signing key, written into every receipt for rotation.
 *   <li>{@code signing-key} — the 64-byte Ed25519 secret ({@code seed ‖ publicKey}) in base64 or
 *       hex. <b>A secret</b>: it comes from {@code secrets/} via {@code CRYPTOBOT_RECEIPT_SIGNING_KEY},
 *       never from a file in the repo. Empty ⇒ an ephemeral key is generated at startup (dev only;
 *       the log says so at WARN).
 *   <li>{@code salt-secret} — the secret the per-tenant commitment salts are derived from
 *       ({@code HMAC-SHA256(salt-secret, tenantId)}). Same rules as the signing key; empty ⇒ ephemeral.
 *   <li>{@code price-table-version} — the version label of the cost table the receipt quotes.
 *       Cost is an estimate for local models and is labelled as such; a receipt without a cost is
 *       an honest receipt.
 *   <li>{@code reuse-window} — an internal ticket: how old a {@code MARKET_ANALYSIS} may be for a new
 *       {@code STRATEGY} of the same wallet and asset to declare {@code REUSES} it instead of
 *       requiring a fresh run (Endgame §19 step 3: "&lt; 1 h"). {@code PT0S} disables reuse.
 * </ul>
 */
@ConfigurationProperties(prefix = "cryptobot.receipts")
public record ReceiptProperties(String keyId, String signingKey, String saltSecret, String priceTableVersion, Duration reuseWindow) {

    public static final Duration DEFAULT_REUSE_WINDOW = Duration.ofHours(1);

    public ReceiptProperties {
        keyId = keyId == null || keyId.isBlank() ? null : keyId.trim();
        signingKey = signingKey == null || signingKey.isBlank() ? null : signingKey.trim();
        saltSecret = saltSecret == null || saltSecret.isBlank() ? null : saltSecret.trim();
        priceTableVersion = priceTableVersion == null || priceTableVersion.isBlank() ? "none" : priceTableVersion.trim();
        reuseWindow = reuseWindow == null ? DEFAULT_REUSE_WINDOW : reuseWindow.isNegative() ? Duration.ZERO : reuseWindow;
    }
}
