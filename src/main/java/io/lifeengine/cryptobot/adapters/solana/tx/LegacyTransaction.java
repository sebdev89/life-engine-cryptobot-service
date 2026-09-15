package io.lifeengine.cryptobot.adapters.solana.tx;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy (pre-v0) Solana transaction: header + compact account list + recent blockhash + compact
 * instruction list, then the signature vector in front. Enough for {@code SystemProgram.transfer}
 * and small SPL instructions; no address lookup tables.
 *
 * <p>Wire format reference: solana-labs/solana {@code sdk/program/src/message/legacy.rs}.
 */
public final class LegacyTransaction {

    public record AccountMeta(String pubkey, boolean signer, boolean writable) {}

    public record Instruction(String programId, List<AccountMeta> accounts, byte[] data) {
        public Instruction {
            accounts = List.copyOf(accounts);
            data = data.clone();
        }
    }

    private final String feePayer;
    private final String recentBlockhash;
    private final List<Instruction> instructions;
    private final List<String> accountKeys;
    private final int numRequiredSignatures;
    private final int numReadonlySigned;
    private final int numReadonlyUnsigned;

    public LegacyTransaction(String feePayer, String recentBlockhash, List<Instruction> instructions) {
        this.feePayer = feePayer;
        this.recentBlockhash = recentBlockhash;
        this.instructions = List.copyOf(instructions);

        // Merge account metas: fee payer first, then signers (writable, readonly), then non-signers.
        Map<String, AccountMeta> merged = new LinkedHashMap<>();
        merged.put(feePayer, new AccountMeta(feePayer, true, true));
        for (Instruction ix : instructions) {
            for (AccountMeta meta : ix.accounts()) {
                merged.merge(
                        meta.pubkey(),
                        meta,
                        (a, b) -> new AccountMeta(a.pubkey(), a.signer() || b.signer(), a.writable() || b.writable()));
            }
            merged.putIfAbsent(ix.programId(), new AccountMeta(ix.programId(), false, false));
        }
        List<AccountMeta> ordered = new ArrayList<>();
        ordered.add(merged.get(feePayer));
        for (boolean signer : new boolean[] {true, false}) {
            for (boolean writable : new boolean[] {true, false}) {
                for (AccountMeta m : merged.values()) {
                    if (m.pubkey().equals(feePayer)) {
                        continue;
                    }
                    if (m.signer() == signer && m.writable() == writable) {
                        ordered.add(m);
                    }
                }
            }
        }
        this.accountKeys = ordered.stream().map(AccountMeta::pubkey).toList();
        this.numRequiredSignatures = (int) ordered.stream().filter(AccountMeta::signer).count();
        this.numReadonlySigned = (int) ordered.stream().filter(m -> m.signer() && !m.writable()).count();
        this.numReadonlyUnsigned = (int) ordered.stream().filter(m -> !m.signer() && !m.writable()).count();
    }

    public String feePayer() {
        return feePayer;
    }

    public String recentBlockhash() {
        return recentBlockhash;
    }

    public List<String> accountKeys() {
        return accountKeys;
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    public int numRequiredSignatures() {
        return numRequiredSignatures;
    }

    /** The bytes that get signed. */
    public byte[] serializeMessage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(numRequiredSignatures);
        out.write(numReadonlySigned);
        out.write(numReadonlyUnsigned);
        CompactU16.write(out, accountKeys.size());
        for (String key : accountKeys) {
            out.writeBytes(Base58.decode(key));
        }
        out.writeBytes(Base58.decode(recentBlockhash));
        CompactU16.write(out, instructions.size());
        for (Instruction ix : instructions) {
            out.write(accountKeys.indexOf(ix.programId()));
            CompactU16.write(out, ix.accounts().size());
            for (AccountMeta meta : ix.accounts()) {
                out.write(accountKeys.indexOf(meta.pubkey()));
            }
            CompactU16.write(out, ix.data().length);
            out.writeBytes(ix.data());
        }
        return out.toByteArray();
    }

    /** Full wire transaction with the given signatures (one per required signer, in account order). */
    public byte[] serialize(List<byte[]> signatures) {
        if (signatures.size() != numRequiredSignatures) {
            throw new IllegalArgumentException(
                    "Expected " + numRequiredSignatures + " signatures, got " + signatures.size());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompactU16.write(out, signatures.size());
        for (byte[] sig : signatures) {
            if (sig.length != 64) {
                throw new IllegalArgumentException("Ed25519 signature must be 64 bytes");
            }
            out.writeBytes(sig);
        }
        out.writeBytes(serializeMessage());
        return out.toByteArray();
    }

    /** Unsigned wire form (all-zero signatures) — what gets stored on the proposal and simulated. */
    public String unsignedBase64() {
        List<byte[]> zeros = new ArrayList<>();
        for (int i = 0; i < numRequiredSignatures; i++) {
            zeros.add(new byte[64]);
        }
        return Base64.getEncoder().encodeToString(serialize(zeros));
    }

    public String messageBase64() {
        return Base64.getEncoder().encodeToString(serializeMessage());
    }
}
