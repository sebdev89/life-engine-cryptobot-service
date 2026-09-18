package io.lifeengine.cryptobot.domain.receipt;

import io.lifeengine.cryptobot.domain.intent.JsonCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Canonical bytes, receipt id and signing message of a receipt — the three functions a verifier
 * outside this codebase has to reimplement, so they are small and fixed here:
 *
 * <pre>
 *   canonical = JCS(body)                                   RFC 8785, via {@link JsonCanonicalizer}
 *   receiptHash = SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ canonical)
 *   signature = Ed25519(serviceKey, "life-engine.cryptobot.receipt.sig" ‖ 0x00 ‖ receiptHashBytes)
 * </pre>
 *
 * The body of a receipt includes the hashes of its parents, so a receipt's id depends on its
 * parents' ids: a cycle would need a parent whose hash depends on a child that does not exist yet.
 * The vectors in {@code src/test/resources/receipt/vectors-v1.json} pin all three functions.
 */
public final class ReceiptCanonicalizer {

    /** Domain tag of the receipt id. Part of the contract: changing it changes every hash. */
    public static final String HASH_DOMAIN = "life-engine.cryptobot.receipt";
    /** Domain tag of the signature: the service key never signs a bare 32-byte digest. */
    public static final String SIGNATURE_DOMAIN = "life-engine.cryptobot.receipt.sig";

    private ReceiptCanonicalizer() {}

    public static byte[] canonicalBytes(ReceiptBody body) {
        return canonicalBytes(body.toMap());
    }

    /** For verification from the stored JSON tree, without going through the typed record. */
    public static byte[] canonicalBytes(Map<String, Object> body) {
        return JsonCanonicalizer.canonicalBytes(body);
    }

    public static String canonicalJson(ReceiptBody body) {
        return new String(canonicalBytes(body), StandardCharsets.UTF_8);
    }

    public static String receiptHash(byte[] canonicalBytes) {
        return Digests.domainSeparated(HASH_DOMAIN, canonicalBytes);
    }

    /** The message the service key signs for the receipt with this id. */
    public static byte[] signingMessage(String receiptHash) {
        byte[] tag = SIGNATURE_DOMAIN.getBytes(StandardCharsets.UTF_8);
        byte[] hash = Digests.bytes(receiptHash);
        byte[] message = new byte[tag.length + 1 + hash.length];
        System.arraycopy(tag, 0, message, 0, tag.length);
        message[tag.length] = 0x00;
        System.arraycopy(hash, 0, message, tag.length + 1, hash.length);
        return message;
    }
}
