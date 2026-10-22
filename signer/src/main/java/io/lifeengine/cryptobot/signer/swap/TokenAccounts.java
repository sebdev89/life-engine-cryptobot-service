package io.lifeengine.cryptobot.signer.swap;

import io.lifeengine.cryptobot.signer.solana.Base58;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The signer's own derivation of associated token accounts ({@code find_program_address([owner, token_program,
 * mint], ata_program)}), so it can tell its own token account for a mint from anyone else's without asking the chain
 * or the service. Deliberately a separate copy (the validator and the service have theirs): the signer decides with
 * its own code. Verified against the accounts Jupiter derived in the real fixtures.
 */
public final class TokenAccounts {

    public static final String SYSTEM_PROGRAM = "11111111111111111111111111111111";
    public static final String COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111";
    public static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    public static final String ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
    public static final String WSOL_MINT = "So11111111111111111111111111111111111111112";

    private static final byte[] MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger LEGENDRE_EXP = P.subtract(BigInteger.ONE).shiftRight(1);

    private TokenAccounts() {}

    /** The owner's associated token account for {@code mint} under the classic SPL token program. */
    public static String associated(String owner, String mint) {
        byte[][] seeds = {Base58.decode(owner), Base58.decode(TOKEN_PROGRAM), Base58.decode(mint)};
        byte[] program = Base58.decode(ATA_PROGRAM);
        for (int bump = 255; bump >= 0; bump--) {
            byte[] candidate = sha256(seeds, (byte) bump, program);
            if (!onCurve(candidate)) {
                return Base58.encode(candidate);
            }
        }
        throw new IllegalStateException("no viable bump");
    }

    private static byte[] sha256(byte[][] seeds, byte bump, byte[] program) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (byte[] s : seeds) {
                sha.update(s);
            }
            sha.update(bump);
            sha.update(program);
            sha.update(MARKER);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** curve25519-dalek decompression semantics: on the curve iff (y² − 1)/(d·y² + 1) is a square mod p. */
    static boolean onCurve(byte[] key) {
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = key[31 - i];
        }
        be[0] &= 0x7F;
        BigInteger y = new BigInteger(1, be).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        if (v.signum() == 0) {
            return u.signum() == 0;
        }
        if (u.signum() == 0) {
            return true;
        }
        return u.multiply(v.modInverse(P)).mod(P).modPow(LEGENDRE_EXP, P).equals(BigInteger.ONE);
    }
}
