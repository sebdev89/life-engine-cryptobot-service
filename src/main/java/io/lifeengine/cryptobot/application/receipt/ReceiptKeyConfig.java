package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the receipt signing key and the tenant-salt secret from {@link ReceiptProperties}. The
 * secrets are never logged; only the key id and the public key are.
 */
@Configuration
public class ReceiptKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(ReceiptKeyConfig.class);

    @Bean
    public ReceiptSigningKey receiptSigningKey(ReceiptProperties props) {
        if (props.signingKey() == null) {
            ReceiptSigningKey ephemeral = ReceiptSigningKey.generate(props.keyId() == null ? "ephemeral" : props.keyId());
            log.warn("receipt_signing_key_ephemeral keyId={} publicKey={} — no cryptobot.receipts.signing-key configured:"
                    + " receipts signed by this process cannot be verified after a restart", ephemeral.keyId(), ephemeral.publicKeyHex());
            return ephemeral;
        }
        if (props.keyId() == null) {
            throw new IllegalStateException("cryptobot.receipts.key-id is required when a signing key is configured");
        }
        ReceiptSigningKey key = ReceiptSigningKey.fromSecretKey(props.keyId(), decode(props.signingKey(), 64, "cryptobot.receipts.signing-key"));
        log.info("receipt_signing_key_loaded keyId={} publicKey={}", key.keyId(), key.publicKeyHex());
        return key;
    }

    @Bean
    public TenantSalts tenantSalts(ReceiptProperties props) {
        if (props.saltSecret() == null) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            log.warn("receipt_salt_secret_ephemeral — no cryptobot.receipts.salt-secret configured: prompt commitments made by this"
                    + " process cannot be re-opened after a restart");
            return new TenantSalts(secret);
        }
        return new TenantSalts(props.saltSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** base64 or hex, whichever parses to {@code expectedLength} bytes. */
    static byte[] decode(String value, int expectedLength, String what) {
        String v = value.trim();
        byte[] bytes = null;
        if (v.length() == expectedLength * 2 && v.matches("[0-9a-fA-F]+")) {
            bytes = HexFormat.of().parseHex(v.toLowerCase(Locale.ROOT));
        } else {
            try {
                bytes = Base64.getDecoder().decode(v);
            } catch (IllegalArgumentException ignored) {
                // not base64 either
            }
        }
        if (bytes == null || bytes.length != expectedLength) {
            throw new IllegalStateException(what + " must be " + expectedLength + " bytes in base64 or hex");
        }
        return bytes;
    }
}
