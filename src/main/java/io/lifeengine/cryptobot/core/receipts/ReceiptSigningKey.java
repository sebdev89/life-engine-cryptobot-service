package io.lifeengine.cryptobot.core.receipts;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The service's receipt-signing key: Ed25519 (JDK built-in, JEP 339), identified by a
 * {@code keyId} so it can be rotated without invalidating old receipts. It is <b>not</b> a wallet
 * key and never signs a transaction: the only message it signs is
 * {@link ReceiptCanonicalizer#signingMessage}.
 *
 * <p>Layout: the 64-byte {@code seed ‖ publicKey} form of {@code solana-keygen} (the JDK offers
 * no seed→public-key derivation, so the public half travels with the seed and the pair is
 * checked at construction by signing and verifying a fixed message). The secret lives in
 * configuration ({@code cryptobot.receipts.signing-key}, from {@code secrets/}); the public key is
 * published by the API so anyone can verify a receipt offline. Without a configured key the
 * service generates an ephemeral one at startup and says so in the log — receipts signed by it die
 * with the process, which is fine for a dev box and wrong for anything that anchors.
 */
public final class ReceiptSigningKey {

    private static final byte[] SELF_CHECK = "life-engine.cryptobot.receipt.keycheck".getBytes(StandardCharsets.UTF_8);

    private final String keyId;
    private final byte[] seed;
    private final byte[] publicKey;

    private ReceiptSigningKey(String keyId, byte[] seed, byte[] publicKey) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId is required");
        }
        this.keyId = keyId.trim();
        this.seed = seed.clone();
        this.publicKey = publicKey.clone();
        if (!verify(this.publicKey, SELF_CHECK, sign(SELF_CHECK))) {
            throw new IllegalArgumentException("receipt signing key " + this.keyId + ": public key does not match the seed");
        }
    }

    /** 64 bytes: {@code seed(32) ‖ publicKey(32)}, as written by {@code solana-keygen}. */
    public static ReceiptSigningKey fromSecretKey(String keyId, byte[] secretKey64) {
        if (secretKey64 == null || secretKey64.length != 64) {
            throw new IllegalArgumentException("Ed25519 secret key must be 64 bytes (seed ‖ public key)");
        }
        return new ReceiptSigningKey(keyId, Arrays.copyOfRange(secretKey64, 0, 32), Arrays.copyOfRange(secretKey64, 32, 64));
    }

    public static ReceiptSigningKey generate(String keyId) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = gen.generateKeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] x509 = pair.getPublic().getEncoded();
            // PKCS#8 for Ed25519 is a fixed 48-byte structure whose last 32 bytes are the seed;
            // X.509 SubjectPublicKeyInfo is 44 bytes whose last 32 are the raw point.
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
            byte[] pub = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
            return new ReceiptSigningKey(keyId, seed, pub);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    public String keyId() {
        return keyId;
    }

    public byte[] publicKeyBytes() {
        return publicKey.clone();
    }

    public String publicKeyHex() {
        return HexFormat.of().formatHex(publicKey);
    }

    /** The 64-byte secret, for tests that need to round-trip a key through configuration. Never logged. */
    public byte[] secretKey() {
        byte[] out = new byte[64];
        System.arraycopy(seed, 0, out, 0, 32);
        System.arraycopy(publicKey, 0, out, 32, 32);
        return out;
    }

    public byte[] sign(byte[] message) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey priv = kf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public boolean verify(byte[] message, byte[] signature64) {
        return verify(publicKey, message, signature64);
    }

    public static boolean verify(byte[] publicKey32, byte[] message, byte[] signature64) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, decodePoint(publicKey32)));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature64);
        } catch (Exception e) {
            return false;
        }
    }

    /** RFC 8032 §5.1.3: little-endian y with the sign of x in the top bit of the last byte. */
    private static EdECPoint decodePoint(byte[] encoded) {
        if (encoded == null || encoded.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        byte[] le = encoded.clone();
        boolean xOdd = (le[31] & 0x80) != 0;
        le[31] &= 0x7F;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = le[31 - i];
        }
        return new EdECPoint(xOdd, new BigInteger(1, be));
    }
}
