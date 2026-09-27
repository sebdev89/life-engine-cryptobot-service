package io.lifeengine.cryptobot.solana.tx;

import java.io.ByteArrayOutputStream;

/** Solana's "compact-u16" / shortvec length prefix (LEB128-style, max 3 bytes). */
public final class CompactU16 {

    private CompactU16() {}

    public static void write(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("compact-u16 out of range: " + value);
        }
        int rem = value;
        while (true) {
            int elem = rem & 0x7F;
            rem >>= 7;
            if (rem == 0) {
                out.write(elem);
                return;
            }
            out.write(elem | 0x80);
        }
    }

    public static byte[] encode(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }
}
