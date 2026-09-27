package io.lifeengine.cryptobot.solana.tx;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.math.BigInteger;
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

/**
 * Ed25519 keypair in Solana's 64-byte layout ({@code seed || publicKey}, the format of
 * {@code solana-keygen}'s {@code id.json}). Uses the JDK's built-in Ed25519 (JEP 339) — no
 * external crypto dependency.
 *
 * <p>Inside {@code cryptobot-service} this class is used for <b>verification and tests only</b>.
 * Signing with a real key happens in {@code cryptobot-signer}; the service never holds a secret.
 */
public final class SolanaKeypair {

    private final byte[] seed;
    private final byte[] publicKey;

    private SolanaKeypair(byte[] seed, byte[] publicKey) {
        this.seed = seed.clone();
        this.publicKey = publicKey.clone();
    }

    public static SolanaKeypair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = gen.generateKeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] x509 = pair.getPublic().getEncoded();
            // PKCS#8 for Ed25519 is a fixed 48-byte structure whose last 32 bytes are the seed;
            // X.509 SubjectPublicKeyInfo is 44 bytes whose last 32 are the raw point.
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
            byte[] pub = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
            return new SolanaKeypair(seed, pub);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** 64-byte {@code seed || pubkey} as written by {@code solana-keygen}. */
    public static SolanaKeypair fromSecretKey(byte[] secretKey64) {
        if (secretKey64 == null || secretKey64.length != 64) {
            throw new IllegalArgumentException("Solana secret key must be 64 bytes");
        }
        return new SolanaKeypair(Arrays.copyOfRange(secretKey64, 0, 32), Arrays.copyOfRange(secretKey64, 32, 64));
    }

    public byte[] secretKey() {
        byte[] out = new byte[64];
        System.arraycopy(seed, 0, out, 0, 32);
        System.arraycopy(publicKey, 0, out, 32, 32);
        return out;
    }

    public byte[] publicKeyBytes() {
        return publicKey.clone();
    }

    public String publicKeyBase58() {
        return Base58.encode(publicKey);
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
