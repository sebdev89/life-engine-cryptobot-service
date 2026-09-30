package io.lifeengine.cryptobot.solana.tx;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SPL Memo v2: the instruction data is the UTF-8 text, logged by the program; with no accounts
 * nothing but the fee payer signs. It is how a receipt batch's Merkle root reaches devnet without
 * a program of our own (Endgame §11).
 */
public final class MemoProgram {

    public static final String PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    /** The program refuses longer memos with a compute error; ours are ~110 bytes. */
    public static final int MAX_MEMO_BYTES = 566;

    private MemoProgram() {}

    public static LegacyTransaction.Instruction memo(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_MEMO_BYTES) {
            throw new IllegalArgumentException("memo must be 1.." + MAX_MEMO_BYTES + " bytes, got " + data.length);
        }
        return new LegacyTransaction.Instruction(PROGRAM_ID, List.of(), data);
    }
}
