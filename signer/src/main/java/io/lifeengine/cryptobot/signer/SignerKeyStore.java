package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the keypair once at boot and keeps it in memory only. The secret never appears in a
 * log line, a response, or an exception message — only the public key does.
 */
@Component
public class SignerKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SignerKeyStore.class);

    private final SolanaKeypair keypair;

    public SignerKeyStore(SignerProperties props) {
        this.keypair = load(props);
        log.info("signer_key_loaded publicKey={} cluster={} maxLamports={} allowedDestinations={}",
                keypair.publicKeyBase58(), props.cluster(), props.maxLamports(), props.allowedDestinations().size());
    }

    public String publicKey() {
        return keypair.publicKeyBase58();
    }

    public byte[] publicKeyBytes() {
        return keypair.publicKeyBytes();
    }

    byte[] sign(byte[] message) {
        return keypair.sign(message);
    }

    static SolanaKeypair load(SignerProperties props) {
        String json;
        if (!props.keypairJson().isEmpty()) {
            json = props.keypairJson();
        } else if (!props.keypairPath().isEmpty()) {
            try {
                json = Files.readString(Path.of(props.keypairPath()));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read signer keypair file " + props.keypairPath(), e);
            }
        } else {
            throw new IllegalStateException("No signer key: set SIGNER_KEYPAIR_PATH or SIGNER_KEYPAIR_JSON");
        }
        return parse(json);
    }

    /** solana-keygen format: a JSON array of 64 unsigned bytes. */
    static SolanaKeypair parse(String json) {
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalStateException("Signer keypair must be a JSON array of 64 bytes");
        }
        String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",");
        if (parts.length != 64) {
            throw new IllegalStateException("Signer keypair must have 64 bytes, got " + parts.length);
        }
        byte[] secret = new byte[64];
        for (int i = 0; i < 64; i++) {
            int v = Integer.parseInt(parts[i].trim());
            if (v < 0 || v > 255) {
                throw new IllegalStateException("Signer keypair byte out of range at index " + i);
            }
            secret[i] = (byte) v;
        }
        return SolanaKeypair.fromSecretKey(secret);
    }
}
