package io.lifeengine.cryptobot.adapters.solana.authority;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Solana program-derived addresses, as in {@code Pubkey::create_program_address} /
 * {@code find_program_address} of the SDK: {@code SHA-256(seed₁ ‖ … ‖ seedₙ ‖ program_id ‖
 * "ProgramDerivedAddress")}, accepted only when the 32 bytes are <b>not</b> a valid Ed25519
 * point — so no private key can ever sign for the address and only the program can. {@code find}
 * tries bump {@code 255 → 0} and returns the first off-curve result, like the SDK.
 *
 * <p>The curve test is the one curve25519-dalek performs on decompression ({@code
 * CompressedEdwardsY::decompress().is_some()}): the top bit is masked, {@code y} is read
 * little-endian and reduced mod {@code p}, and the point exists iff {@code (y² − 1)/(d·y² + 1)}
 * is a square. Non-canonical encodings are <i>not</i> rejected, matching the SDK. Verified
 * against SDK answers in {@code src/test/resources/authority/vectors-v1.json}.
 */
public final class ProgramDerivedAddress {

    public static final int MAX_SEED_LENGTH = 32;
    public static final int MAX_SEEDS = 16;
    private static final byte[] MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);

    /** p = 2²⁵⁵ − 19 */
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    /** d = −121665 / 121666 mod p */
    private static final BigInteger D =
            BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger LEGENDRE_EXP = P.subtract(BigInteger.ONE).shiftRight(1);

    public record Derived(byte[] address, int bump) {
        public Derived {
            address = address.clone();
        }

        public String base58() {
            return Base58.encode(address);
        }
    }

    private ProgramDerivedAddress() {}

    /** {@code find_program_address}: seeds + {@code [bump]}, first bump from 255 down that is off-curve. */
    public static Derived find(List<byte[]> seeds, byte[] programId) {
        if (seeds.size() >= MAX_SEEDS) {
            throw new IllegalArgumentException("at most " + (MAX_SEEDS - 1) + " seeds before the bump");
        }
        for (int bump = 255; bump >= 0; bump--) {
            byte[][] withBump = new byte[seeds.size() + 1][];
            for (int i = 0; i < seeds.size(); i++) {
                withBump[i] = seeds.get(i);
            }
            withBump[seeds.size()] = new byte[] {(byte) bump};
            byte[] candidate = hash(List.of(withBump), programId);
            if (!isOnCurve(candidate)) {
                return new Derived(candidate, bump);
            }
        }
        throw new IllegalStateException("no viable bump for the given seeds"); // 2⁻²⁵⁶-ish
    }

    /** {@code create_program_address}: the exact seeds (bump included by the caller); throws if on-curve. */
    public static byte[] create(List<byte[]> seeds, byte[] programId) {
        byte[] candidate = hash(seeds, programId);
        if (isOnCurve(candidate)) {
            throw new IllegalArgumentException("derived address is on the Ed25519 curve; use another bump");
        }
        return candidate;
    }

    private static byte[] hash(List<byte[]> seeds, byte[] programId) {
        if (programId == null || programId.length != 32) {
            throw new IllegalArgumentException("program id must be 32 bytes");
        }
        if (seeds.size() > MAX_SEEDS) {
            throw new IllegalArgumentException("at most " + MAX_SEEDS + " seeds");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (byte[] seed : seeds) {
                if (seed == null || seed.length > MAX_SEED_LENGTH) {
                    throw new IllegalArgumentException("each seed must be at most 32 bytes");
                }
                sha.update(seed);
            }
            sha.update(programId);
            sha.update(MARKER);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True iff the 32 bytes decompress to a point of the Ed25519 curve (dalek semantics). */
    public static boolean isOnCurve(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        byte[] le = key.clone();
        le[31] &= 0x7F;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = le[31 - i];
        }
        BigInteger y = new BigInteger(1, be).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        if (v.signum() == 0) {
            // x² = u / 0: dalek's sqrt_ratio_i reports "not a square" unless u is also 0 (impossible here).
            return u.signum() == 0;
        }
        if (u.signum() == 0) {
            return true; // x = 0 is a valid solution
        }
        BigInteger ratio = u.multiply(v.modInverse(P)).mod(P);
        return ratio.modPow(LEGENDRE_EXP, P).equals(BigInteger.ONE);
    }
}
