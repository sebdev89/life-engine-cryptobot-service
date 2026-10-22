package io.lifeengine.cryptobot.adapters.jupiter;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * What we check in a transaction Jupiter built before doing anything with it, even simulating it: the aggregator
 * is a third party and its bytes are not trusted.
 *
 * <ul>
 *   <li>a v0 message with exactly one required signature, and that slot still empty (we never receive anything
 *       already signed);
 *   <li>the fee payer — the one signer — is the owner we asked for;
 *   <li>every instruction calls a program on {@link #ALLOWED_PROGRAMS} (Jupiter v6, compute budget, system, SPL
 *       token / token-2022, associated token account), named among the static keys, never through a lookup table.
 * </ul>
 *
 * <p>Not checked here: the accounts and data of each instruction (for example, that a system transfer only wraps
 * SOL into the owner's own account). That is a full decoder's job (validator v2) and must exist before anything
 * built here is signed; this class only gates a simulation.
 */
public final class UnsignedSwapCheck {

    public static final String JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
    public static final Set<String> ALLOWED_PROGRAMS = Set.of(
            JUPITER_V6,
            "ComputeBudget111111111111111111111111111111",
            "11111111111111111111111111111111",
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
            "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

    /** What the check read out of the message. */
    public record Checked(String feePayer, List<String> programs, int lookupTables) {}

    private UnsignedSwapCheck() {}

    public static Checked check(String transactionBase64, String expectedFeePayer) {
        byte[] tx;
        try {
            tx = Base64.getDecoder().decode(transactionBase64);
        } catch (IllegalArgumentException ex) {
            throw new SwapRejectedException("malformed_transaction", "not base64");
        }
        try {
            return read(tx, expectedFeePayer);
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new SwapRejectedException("malformed_transaction", "truncated");
        }
    }

    private static Checked read(byte[] tx, String expectedFeePayer) {
        int[] at = {0};
        int signatures = compactU16(tx, at);
        if (signatures != 1) {
            throw new SwapRejectedException("unexpected_signers", signatures + " signature slots, expected 1");
        }
        for (int i = 0; i < 64; i++) {
            if (tx[at[0] + i] != 0) {
                throw new SwapRejectedException("already_signed", "the signature slot is not empty");
            }
        }
        at[0] += 64;
        if ((tx[at[0]] & 0xff) != 0x80) {
            throw new SwapRejectedException("unexpected_version", "not a v0 message");
        }
        at[0]++;
        int requiredSignatures = tx[at[0]] & 0xff;
        at[0] += 3;
        if (requiredSignatures != 1) {
            throw new SwapRejectedException("unexpected_signers", "header requires " + requiredSignatures + " signatures");
        }
        int keyCount = compactU16(tx, at);
        List<String> keys = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) {
            keys.add(Base58.encode(slice(tx, at[0], 32)));
            at[0] += 32;
        }
        if (keys.isEmpty() || !keys.get(0).equals(expectedFeePayer)) {
            throw new SwapRejectedException("fee_payer_mismatch", "fee payer " + (keys.isEmpty() ? "none" : keys.get(0)) + ", expected " + expectedFeePayer);
        }
        at[0] += 32; // recent blockhash: the simulation replaces it
        int instructions = compactU16(tx, at);
        List<String> programs = new ArrayList<>(instructions);
        for (int i = 0; i < instructions; i++) {
            int programIndex = tx[at[0]++] & 0xff;
            if (programIndex >= keyCount) {
                throw new SwapRejectedException("program_not_allowed", "instruction " + i + " names its program through a lookup table");
            }
            String program = keys.get(programIndex);
            if (!ALLOWED_PROGRAMS.contains(program)) {
                throw new SwapRejectedException("program_not_allowed", "instruction " + i + " calls " + program);
            }
            programs.add(program);
            int accounts = compactU16(tx, at); // read first: compactU16 moves at[0]
            at[0] += accounts;
            int data = compactU16(tx, at);
            at[0] += data;
        }
        int lookupTables = compactU16(tx, at);
        if (!programs.contains(JUPITER_V6)) {
            throw new SwapRejectedException("program_not_allowed", "no Jupiter instruction in the transaction");
        }
        return new Checked(keys.get(0), List.copyOf(programs), lookupTables);
    }

    private static int compactU16(byte[] b, int[] at) {
        int value = 0;
        for (int shift = 0; shift < 21; shift += 7) {
            int x = b[at[0]++] & 0xff;
            value |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
        }
        throw new SwapRejectedException("malformed_transaction", "compact-u16 too long");
    }

    private static byte[] slice(byte[] b, int from, int len) {
        if (from + len > b.length) {
            throw new ArrayIndexOutOfBoundsException(from + len);
        }
        byte[] out = new byte[len];
        System.arraycopy(b, from, out, 0, len);
        return out;
    }
}
