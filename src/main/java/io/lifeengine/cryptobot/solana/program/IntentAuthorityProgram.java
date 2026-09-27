package io.lifeengine.cryptobot.solana.program;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction.AccountMeta;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction.Instruction;
import io.lifeengine.cryptobot.solana.tx.SystemProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Client of the {@code intent-authority} program ({@code programs/intent-authority}, KAN-437,
 * paper level 4): derives the three PDAs, encodes the three instructions and decodes the three
 * account layouts. Byte-exact with the Rust side — both assert the same
 * {@code src/test/resources/authority/vectors-v1.json}, whose reference values come from the
 * Solana SDK, not from this class.
 *
 * <p>Nothing here signs or sends. The agent's signature over the transaction is the I1 proof and
 * belongs to the isolated signer; this class only builds what gets signed.
 *
 * <pre>
 *   policy  = PDA["policy",  agent(32), policy_version(u32 LE)]   commits H_R (immutable, revocable)
 *   nonce   = PDA["nonce",   agent(32), nonce(u64 LE)]            exists ⇔ consumed
 *   receipt = PDA["receipt", intent_hash(32)]                     exists ⇔ this H_I executed
 * </pre>
 */
public final class IntentAuthorityProgram {

    public static final byte[] POLICY_SEED = "policy".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] NONCE_SEED = "nonce".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] RECEIPT_SEED = "receipt".getBytes(StandardCharsets.US_ASCII);

    public static final int POLICY_DISCRIMINATOR = 1;
    public static final int NONCE_DISCRIMINATOR = 2;
    public static final int RECEIPT_DISCRIMINATOR = 3;
    public static final int POLICY_LEN = 111;
    public static final int NONCE_LEN = 82;
    public static final int RECEIPT_LEN = 126;

    private static final int IX_REGISTER_POLICY = 0;
    private static final int IX_REVOKE_POLICY = 1;
    private static final int IX_EXECUTE = 2;

    /** The refusals of the program, by {@code Custom(n)} code — same order as {@code error.rs}. */
    public enum AuthorityError {
        AGENT_SIGNATURE_MISSING,
        POLICY_ACCOUNT_MISMATCH,
        POLICY_NOT_REGISTERED,
        POLICY_REVOKED,
        POLICY_HASH_MISMATCH,
        INTENT_EXPIRED,
        NONCE_ALREADY_USED,
        INTENT_ALREADY_EXECUTED,
        INVALID_PDA,
        NOT_POLICY_AUTHORITY,
        POLICY_ALREADY_REGISTERED,
        ZERO_INTENT_HASH,
        INVALID_ACCOUNT_DATA;

        public int code() {
            return ordinal();
        }

        public static AuthorityError fromCode(int code) {
            AuthorityError[] all = values();
            if (code < 0 || code >= all.length) {
                throw new IllegalArgumentException("unknown intent-authority error code " + code);
            }
            return all[code];
        }

        /** Which of the paper's invariants (I1–I4) this refusal enforces; empty for malformed input. */
        public String invariant() {
            return switch (this) {
                case AGENT_SIGNATURE_MISSING, POLICY_ACCOUNT_MISMATCH, POLICY_NOT_REGISTERED, POLICY_REVOKED -> "I1";
                case INTENT_EXPIRED -> "I2";
                case NONCE_ALREADY_USED, INTENT_ALREADY_EXECUTED -> "I3";
                case POLICY_HASH_MISMATCH -> "I4";
                default -> "";
            };
        }
    }

    public record PolicyAccount(
            String authority, String agent, long policyVersion, byte[] policyHash, boolean active, long registeredSlot, int bump) {
        public PolicyAccount {
            policyHash = policyHash.clone();
        }

        public String policyHashHex() {
            return HexFormat.of().formatHex(policyHash);
        }
    }

    public record NonceAccount(String agent, long nonce, byte[] intentHash, long usedSlot, int bump) {
        public NonceAccount {
            intentHash = intentHash.clone();
        }
    }

    public record ReceiptAccount(
            byte[] intentHash,
            String agent,
            long policyVersion,
            byte[] policyHash,
            long nonce,
            long validUntilSlot,
            long executedSlot,
            int bump) {
        public ReceiptAccount {
            intentHash = intentHash.clone();
            policyHash = policyHash.clone();
        }

        public String intentHashHex() {
            return HexFormat.of().formatHex(intentHash);
        }

        public String policyHashHex() {
            return HexFormat.of().formatHex(policyHash);
        }
    }

    private final byte[] programId;
    private final String programIdBase58;

    public IntentAuthorityProgram(String programIdBase58) {
        if (!Base58.isPublicKey(programIdBase58)) {
            throw new IllegalArgumentException("program id must be a base58 public key");
        }
        this.programIdBase58 = programIdBase58;
        this.programId = Base58.decode(programIdBase58);
    }

    public String programId() {
        return programIdBase58;
    }

    // ---- PDAs ---------------------------------------------------------------------------------

    public ProgramDerivedAddress.Derived policyAddress(String agent, long policyVersion) {
        return ProgramDerivedAddress.find(List.of(POLICY_SEED, key(agent), u32(policyVersion)), programId);
    }

    public ProgramDerivedAddress.Derived nonceAddress(String agent, long nonce) {
        return ProgramDerivedAddress.find(List.of(NONCE_SEED, key(agent), u64(nonce)), programId);
    }

    public ProgramDerivedAddress.Derived receiptAddress(byte[] intentHash32) {
        return ProgramDerivedAddress.find(List.of(RECEIPT_SEED, hash32(intentHash32, "intent hash")), programId);
    }

    // ---- instructions -------------------------------------------------------------------------

    /** {@code RegisterPolicy}: authority pays and becomes the only key able to revoke. */
    public Instruction registerPolicy(String authority, String agent, long policyVersion, byte[] policyHash32) {
        return new Instruction(
                programIdBase58,
                List.of(
                        new AccountMeta(authority, true, true),
                        new AccountMeta(policyAddress(agent, policyVersion).base58(), false, true),
                        new AccountMeta(SystemProgram.PROGRAM_ID, false, false)),
                encodeRegisterPolicy(agent, policyVersion, policyHash32));
    }

    /** {@code RevokePolicy}: irreversible; a new version is a new PDA. */
    public Instruction revokePolicy(String authority, String agent, long policyVersion) {
        return new Instruction(
                programIdBase58,
                List.of(
                        new AccountMeta(authority, true, false),
                        new AccountMeta(policyAddress(agent, policyVersion).base58(), false, true)),
                encodeRevokePolicy());
    }

    /**
     * {@code Execute}: the agent signs (I1), the payer funds the nonce and receipt accounts (may be
     * the agent). The program refuses with one of {@link AuthorityError} when I1–I4 do not hold.
     */
    public Instruction execute(
            String agent,
            String payer,
            long policyVersion,
            byte[] policyHash32,
            byte[] intentHash32,
            long validUntilSlot,
            long nonce) {
        boolean samePayer = agent.equals(payer);
        return new Instruction(
                programIdBase58,
                List.of(
                        new AccountMeta(agent, true, samePayer),
                        new AccountMeta(payer, true, true),
                        new AccountMeta(policyAddress(agent, policyVersion).base58(), false, false),
                        new AccountMeta(nonceAddress(agent, nonce).base58(), false, true),
                        new AccountMeta(receiptAddress(intentHash32).base58(), false, true),
                        new AccountMeta(SystemProgram.PROGRAM_ID, false, false)),
                encodeExecute(intentHash32, policyVersion, policyHash32, validUntilSlot, nonce));
    }

    // ---- borsh encoding (variant index u8, then fields, little-endian) -----------------------

    public static byte[] encodeRegisterPolicy(String agent, long policyVersion, byte[] policyHash32) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 32 + 4 + 32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) IX_REGISTER_POLICY);
        buf.put(key(agent));
        buf.put(u32(policyVersion));
        buf.put(hash32(policyHash32, "policy hash"));
        return buf.array();
    }

    public static byte[] encodeRevokePolicy() {
        return new byte[] {(byte) IX_REVOKE_POLICY};
    }

    public static byte[] encodeExecute(
            byte[] intentHash32, long policyVersion, byte[] policyHash32, long validUntilSlot, long nonce) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 32 + 4 + 32 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) IX_EXECUTE);
        buf.put(hash32(intentHash32, "intent hash"));
        buf.put(u32(policyVersion));
        buf.put(hash32(policyHash32, "policy hash"));
        buf.put(u64(validUntilSlot));
        buf.put(u64(nonce));
        return buf.array();
    }

    // ---- account decoding ---------------------------------------------------------------------

    public static PolicyAccount decodePolicy(byte[] data) {
        ByteBuffer buf = checked(data, POLICY_LEN, POLICY_DISCRIMINATOR, "policy");
        String authority = readKey(buf);
        String agent = readKey(buf);
        long version = Integer.toUnsignedLong(buf.getInt());
        byte[] hash = read32(buf);
        boolean active = readBool(buf);
        long registeredSlot = buf.getLong();
        int bump = Byte.toUnsignedInt(buf.get());
        return new PolicyAccount(authority, agent, version, hash, active, registeredSlot, bump);
    }

    public static NonceAccount decodeNonce(byte[] data) {
        ByteBuffer buf = checked(data, NONCE_LEN, NONCE_DISCRIMINATOR, "nonce");
        String agent = readKey(buf);
        long nonce = buf.getLong();
        byte[] intentHash = read32(buf);
        long usedSlot = buf.getLong();
        int bump = Byte.toUnsignedInt(buf.get());
        return new NonceAccount(agent, nonce, intentHash, usedSlot, bump);
    }

    public static ReceiptAccount decodeReceipt(byte[] data) {
        ByteBuffer buf = checked(data, RECEIPT_LEN, RECEIPT_DISCRIMINATOR, "receipt");
        byte[] intentHash = read32(buf);
        String agent = readKey(buf);
        long version = Integer.toUnsignedLong(buf.getInt());
        byte[] policyHash = read32(buf);
        long nonce = buf.getLong();
        long validUntil = buf.getLong();
        long executed = buf.getLong();
        int bump = Byte.toUnsignedInt(buf.get());
        return new ReceiptAccount(intentHash, agent, version, policyHash, nonce, validUntil, executed, bump);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static ByteBuffer checked(byte[] data, int len, int discriminator, String what) {
        if (data == null || data.length != len) {
            throw new IllegalArgumentException(what + " account must be " + len + " bytes, got "
                    + (data == null ? "null" : data.length));
        }
        if (Byte.toUnsignedInt(data[0]) != discriminator) {
            throw new IllegalArgumentException(what + " account discriminator must be " + discriminator + ", got "
                    + Byte.toUnsignedInt(data[0]));
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); // discriminator
        return buf;
    }

    private static String readKey(ByteBuffer buf) {
        return Base58.encode(read32(buf));
    }

    private static byte[] read32(ByteBuffer buf) {
        byte[] out = new byte[32];
        buf.get(out);
        return out;
    }

    private static boolean readBool(ByteBuffer buf) {
        int b = Byte.toUnsignedInt(buf.get());
        if (b > 1) {
            throw new IllegalArgumentException("borsh bool must be 0 or 1, got " + b);
        }
        return b == 1;
    }

    private static byte[] key(String base58) {
        if (!Base58.isPublicKey(base58)) {
            throw new IllegalArgumentException("not a base58 public key: " + base58);
        }
        return Base58.decode(base58);
    }

    private static byte[] hash32(byte[] hash, String what) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException(what + " must be 32 bytes");
        }
        return Arrays.copyOf(hash, 32);
    }

    private static byte[] u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("u32 out of range: " + value);
        }
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
    }

    private static byte[] u64(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("u64 must be non-negative here: " + value);
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }
}
