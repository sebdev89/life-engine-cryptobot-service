package io.lifeengine.cryptobot.validator;

import io.lifeengine.cryptobot.validator.crypto.SolanaKeypair;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Ed25519 key the validator attests with. It is <b>not</b> a wallet key and can move nothing:
 * its only power is that the signer is configured to trust it. Loaded once, kept in memory, never
 * logged. Without a configured key an ephemeral one is generated and logged as a WARN (dev only —
 * the signer would have to be re-pinned on every restart).
 */
@Component
public class AttestationKeyStore {

    private static final Logger log = LoggerFactory.getLogger(AttestationKeyStore.class);

    private final SolanaKeypair keypair;

    public AttestationKeyStore(ValidatorProperties props) {
        this.keypair = load(props);
    }

    public String publicKey() {
        return keypair.publicKeyBase58();
    }

    public byte[] publicKeyBytes() {
        return keypair.publicKeyBytes();
    }

    byte[] sign(byte[] payload) {
        return keypair.sign(payload);
    }

    static SolanaKeypair load(ValidatorProperties props) {
        String json;
        if (!props.keypairJson().isEmpty()) {
            json = props.keypairJson();
        } else if (!props.keypairPath().isEmpty()) {
            try {
                json = Files.readString(Path.of(props.keypairPath()));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read validator keypair file " + props.keypairPath(), e);
            }
        } else {
            SolanaKeypair ephemeral = SolanaKeypair.generate();
            log.warn("validator_key_ephemeral publicKey={} — no VALIDATOR_KEYPAIR_PATH/JSON; the signer must pin this key and it changes on restart",
                    ephemeral.publicKeyBase58());
            return ephemeral;
        }
        SolanaKeypair k = parse(json);
        log.info("validator_key_loaded publicKey={}", k.publicKeyBase58());
        return k;
    }

    /** solana-keygen format: a JSON array of 64 unsigned bytes. */
    static SolanaKeypair parse(String json) {
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalStateException("Validator keypair must be a JSON array of 64 bytes");
        }
        String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",");
        if (parts.length != 64) {
            throw new IllegalStateException("Validator keypair must have 64 bytes, got " + parts.length);
        }
        byte[] secret = new byte[64];
        for (int i = 0; i < 64; i++) {
            int v = Integer.parseInt(parts[i].trim());
            if (v < 0 || v > 255) {
                throw new IllegalStateException("Validator keypair byte out of range at index " + i);
            }
            secret[i] = (byte) v;
        }
        return SolanaKeypair.fromSecretKey(secret);
    }
}
