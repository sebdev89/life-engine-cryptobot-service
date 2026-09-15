package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.Base58;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses the wire form of a legacy transaction so the signer can look at what it is being asked
 * to sign instead of trusting the caller's description. Rejects anything it does not fully
 * understand (versioned messages, address tables): if we cannot read it, we do not sign it.
 */
final class LegacyMessageDecoder {

    record Instruction(String programId, List<String> accounts, byte[] data) {}

    record Decoded(
            int numRequiredSignatures,
            int numReadonlySigned,
            int numReadonlyUnsigned,
            List<String> accountKeys,
            String recentBlockhash,
            List<Instruction> instructions,
            byte[] message,
            int signatureCount) {}

    private LegacyMessageDecoder() {}

    static Decoded decodeTransaction(byte[] wire) {
        ByteBuffer buf = ByteBuffer.wrap(wire);
        int sigCount = readCompactU16(buf);
        if (sigCount < 1 || sigCount > 8) {
            throw new IllegalArgumentException("unexpected signature count " + sigCount);
        }
        buf.position(buf.position() + sigCount * 64);
        int messageStart = buf.position();
        byte[] message = Arrays.copyOfRange(wire, messageStart, wire.length);

        int header0 = buf.get() & 0xFF;
        if ((header0 & 0x80) != 0) {
            throw new IllegalArgumentException("versioned messages are not supported");
        }
        int numRequired = header0;
        int numRoSigned = buf.get() & 0xFF;
        int numRoUnsigned = buf.get() & 0xFF;
        int keyCount = readCompactU16(buf);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            byte[] k = new byte[32];
            buf.get(k);
            keys.add(Base58.encode(k));
        }
        byte[] bh = new byte[32];
        buf.get(bh);
        int ixCount = readCompactU16(buf);
        List<Instruction> ixs = new ArrayList<>();
        for (int i = 0; i < ixCount; i++) {
            int programIdx = buf.get() & 0xFF;
            int acctCount = readCompactU16(buf);
            List<String> accounts = new ArrayList<>();
            for (int a = 0; a < acctCount; a++) {
                accounts.add(keys.get(buf.get() & 0xFF));
            }
            int dataLen = readCompactU16(buf);
            byte[] data = new byte[dataLen];
            buf.get(data);
            ixs.add(new Instruction(keys.get(programIdx), accounts, data));
        }
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes after message");
        }
        return new Decoded(numRequired, numRoSigned, numRoUnsigned, keys, Base58.encode(bh), ixs, message, sigCount);
    }

    private static int readCompactU16(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 3; i++) {
            int b = buf.get() & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("compact-u16 too long");
    }
}
