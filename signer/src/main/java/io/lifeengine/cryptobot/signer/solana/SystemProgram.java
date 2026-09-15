package io.lifeengine.cryptobot.signer.solana;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** The one native instruction the MVP executes on devnet: {@code SystemProgram::Transfer}. */
public final class SystemProgram {

    public static final String PROGRAM_ID = "11111111111111111111111111111111";
    /** Instruction discriminator for {@code Transfer} in {@code system_instruction::SystemInstruction}. */
    public static final int TRANSFER_INDEX = 2;

    private SystemProgram() {}

    public static LegacyTransaction.Instruction transfer(String from, String to, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        ByteBuffer data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(TRANSFER_INDEX);
        data.putLong(lamports);
        return new LegacyTransaction.Instruction(
                PROGRAM_ID,
                List.of(
                        new LegacyTransaction.AccountMeta(from, true, true),
                        new LegacyTransaction.AccountMeta(to, false, true)),
                data.array());
    }

    /** Decodes a transfer instruction's data; returns -1 when it is not a transfer. */
    public static long decodeTransferLamports(byte[] data) {
        if (data == null || data.length != 12) {
            return -1;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != TRANSFER_INDEX) {
            return -1;
        }
        return buf.getLong();
    }
}
