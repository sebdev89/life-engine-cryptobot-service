package io.lifeengine.cryptobot.validator.tx;

import io.lifeengine.cryptobot.validator.crypto.Base58;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A Solana transaction (signatures + legacy or v0 message) decoded byte for byte, so the validator reads what will
 * be signed instead of trusting a description of it (KAN-600). {@link #encode()} writes the same bytes back; the
 * tests rely on that round trip to build mutated transactions.
 *
 * <p>Accounts are referenced by index. An index below {@code staticKeys.size()} is a key written in the message; a
 * higher one is loaded from an address lookup table, which this class cannot resolve without the chain: for those
 * {@link #staticKey(int)} is empty, and every rule that needs to know an account must fail closed on it.
 */
public record CompiledTransaction(
        List<byte[]> signatures,
        boolean versioned,
        int requiredSignatures,
        int readonlySigned,
        int readonlyUnsigned,
        List<String> staticKeys,
        String recentBlockhash,
        List<Instruction> instructions,
        List<LookupTable> lookupTables) {

    public record Instruction(int programIndex, int[] accounts, byte[] data) {
        public Instruction {
            accounts = accounts.clone();
            data = data.clone();
        }

        @Override
        public int[] accounts() {
            return accounts.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    public record LookupTable(String table, int[] writable, int[] readonly) {
        public LookupTable {
            writable = writable.clone();
            readonly = readonly.clone();
        }

        @Override
        public int[] writable() {
            return writable.clone();
        }

        @Override
        public int[] readonly() {
            return readonly.clone();
        }
    }

    /** The bytes do not form a transaction: truncated, trailing garbage, an index out of range, an unknown version. */
    public static class MalformedTransaction extends RuntimeException {
        public MalformedTransaction(String reason) {
            super(reason);
        }
    }

    public CompiledTransaction {
        signatures = signatures.stream().map(byte[]::clone).toList();
        staticKeys = List.copyOf(staticKeys);
        instructions = List.copyOf(instructions);
        lookupTables = List.copyOf(lookupTables);
    }

    /** Total accounts the message can address: static keys, then every lookup-table entry (writable before readonly). */
    public int accountCount() {
        int n = staticKeys.size();
        for (LookupTable t : lookupTables) {
            n += t.writable.length + t.readonly.length;
        }
        return n;
    }

    /** The key at {@code index} when it is written in the message; empty when it comes from a lookup table. */
    public Optional<String> staticKey(int index) {
        return index >= 0 && index < staticKeys.size() ? Optional.of(staticKeys.get(index)) : Optional.empty();
    }

    public String feePayer() {
        return staticKeys.get(0);
    }

    public boolean signaturesEmpty() {
        return signatures.stream().allMatch(s -> Arrays.equals(s, new byte[64]));
    }

    public static CompiledTransaction decode(byte[] tx) {
        try {
            Reader r = new Reader(tx);
            int sigCount = r.compactU16();
            List<byte[]> sigs = new ArrayList<>(sigCount);
            for (int i = 0; i < sigCount; i++) {
                sigs.add(r.bytes(64));
            }
            boolean versioned = (r.peek() & 0x80) != 0;
            if (versioned) {
                int version = r.u8() & 0x7f;
                if (version != 0) {
                    throw new MalformedTransaction("unsupported message version " + version);
                }
            }
            int required = r.u8();
            int roSigned = r.u8();
            int roUnsigned = r.u8();
            int keyCount = r.compactU16();
            List<String> keys = new ArrayList<>(keyCount);
            for (int i = 0; i < keyCount; i++) {
                keys.add(Base58.encode(r.bytes(32)));
            }
            String blockhash = Base58.encode(r.bytes(32));
            int ixCount = r.compactU16();
            List<Instruction> ixs = new ArrayList<>(ixCount);
            for (int i = 0; i < ixCount; i++) {
                int program = r.u8();
                int[] accounts = r.indexes();
                byte[] data = r.bytes(r.compactU16());
                ixs.add(new Instruction(program, accounts, data));
            }
            List<LookupTable> tables = new ArrayList<>();
            if (versioned) {
                int tableCount = r.compactU16();
                for (int i = 0; i < tableCount; i++) {
                    String table = Base58.encode(r.bytes(32));
                    tables.add(new LookupTable(table, r.indexes(), r.indexes()));
                }
            }
            if (!r.done()) {
                throw new MalformedTransaction("trailing bytes after the message");
            }
            if (sigCount != required || keyCount == 0 || required == 0 || required > keyCount || roSigned >= required
                    || roUnsigned > keyCount - required) {
                throw new MalformedTransaction("inconsistent message header");
            }
            CompiledTransaction ct = new CompiledTransaction(sigs, versioned, required, roSigned, roUnsigned, keys, blockhash, ixs, tables);
            int total = ct.accountCount();
            for (Instruction ix : ixs) {
                if (ix.programIndex >= keyCount) {
                    throw new MalformedTransaction("program index outside the static keys");
                }
                for (int a : ix.accounts) {
                    if (a >= total) {
                        throw new MalformedTransaction("account index " + a + " out of range");
                    }
                }
            }
            return ct;
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new MalformedTransaction("truncated");
        }
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        compactU16(out, signatures.size());
        signatures.forEach(out::writeBytes);
        if (versioned) {
            out.write(0x80);
        }
        out.write(requiredSignatures);
        out.write(readonlySigned);
        out.write(readonlyUnsigned);
        compactU16(out, staticKeys.size());
        staticKeys.forEach(k -> out.writeBytes(Base58.decode(k)));
        out.writeBytes(Base58.decode(recentBlockhash));
        compactU16(out, instructions.size());
        for (Instruction ix : instructions) {
            out.write(ix.programIndex);
            indexes(out, ix.accounts);
            compactU16(out, ix.data.length);
            out.writeBytes(ix.data);
        }
        if (versioned) {
            compactU16(out, lookupTables.size());
            for (LookupTable t : lookupTables) {
                out.writeBytes(Base58.decode(t.table));
                indexes(out, t.writable);
                indexes(out, t.readonly);
            }
        }
        return out.toByteArray();
    }

    private static void indexes(ByteArrayOutputStream out, int[] idx) {
        compactU16(out, idx.length);
        for (int i : idx) {
            out.write(i);
        }
    }

    private static void compactU16(ByteArrayOutputStream out, int value) {
        int v = value;
        while (true) {
            int b = v & 0x7f;
            v >>>= 7;
            if (v == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    private static final class Reader {
        private final byte[] b;
        private int at;

        Reader(byte[] b) {
            this.b = b;
        }

        int peek() {
            return b[at] & 0xff;
        }

        int u8() {
            return b[at++] & 0xff;
        }

        byte[] bytes(int n) {
            if (n < 0 || at + n > b.length) {
                throw new ArrayIndexOutOfBoundsException(at + n);
            }
            byte[] out = Arrays.copyOfRange(b, at, at + n);
            at += n;
            return out;
        }

        int compactU16() {
            int value = 0;
            for (int shift = 0; shift < 21; shift += 7) {
                int x = u8();
                value |= (x & 0x7f) << shift;
                if ((x & 0x80) == 0) {
                    return value;
                }
            }
            throw new MalformedTransaction("compact-u16 too long");
        }

        int[] indexes() {
            int n = compactU16();
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = u8();
            }
            return out;
        }

        boolean done() {
            return at == b.length;
        }
    }
}
