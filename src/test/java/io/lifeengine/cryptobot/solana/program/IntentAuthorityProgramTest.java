package io.lifeengine.cryptobot.solana.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.program.IntentAuthorityProgram.AuthorityError;
import io.lifeengine.cryptobot.solana.program.IntentAuthorityProgram.NonceAccount;
import io.lifeengine.cryptobot.solana.program.IntentAuthorityProgram.PolicyAccount;
import io.lifeengine.cryptobot.solana.program.IntentAuthorityProgram.ReceiptAccount;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction.Instruction;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.solana.tx.SystemProgram;
import io.lifeengine.cryptobot.core.intent.IntentHash;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact agreement with the Rust program over {@code vectors-v1.json}: instruction data
 * (borsh, as the program deserializes it), account layouts (as the program writes them) and the
 * account lists each instruction expects, in order and with the right signer/writable flags.
 */
class IntentAuthorityProgramTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private static JsonNode v;
    private static IntentAuthorityProgram program;
    private static String agent;
    private static String authority;
    private static long version;
    private static byte[] policyHash;
    private static byte[] intentHash;
    private static long nonce;
    private static long validUntil;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = IntentAuthorityProgramTest.class.getResourceAsStream("/authority/vectors-v1.json")) {
            v = JSON.readTree(in);
        }
        assertThat(v.path("schema_version").asInt()).isEqualTo(1);
        program = new IntentAuthorityProgram(v.path("program_id").asText());
        agent = v.path("agent").asText();
        authority = v.path("authority").asText();
        version = v.path("policy_version").asLong();
        policyHash = HEX.parseHex(v.path("policy_hash_hex").asText());
        intentHash = HEX.parseHex(v.path("intent_hash_hex").asText());
        nonce = v.path("nonce").asLong();
        validUntil = v.path("valid_until_slot").asLong();
    }

    @Test
    void pdasMatchTheProgram() {
        assertThat(program.policyAddress(agent, version).base58()).isEqualTo(v.path("policy_pda").path("address").asText());
        assertThat(program.policyAddress(agent, version).bump()).isEqualTo(v.path("policy_pda").path("bump").asInt());
        assertThat(program.nonceAddress(agent, nonce).base58()).isEqualTo(v.path("nonce_pda").path("address").asText());
        assertThat(program.nonceAddress(agent, nonce).bump()).isEqualTo(v.path("nonce_pda").path("bump").asInt());
        assertThat(program.receiptAddress(intentHash).base58()).isEqualTo(v.path("receipt_pda").path("address").asText());
        assertThat(program.receiptAddress(intentHash).bump()).isEqualTo(v.path("receipt_pda").path("bump").asInt());
    }

    @Test
    void instructionBytesAreBorshAsTheProgramDeserializes() {
        assertThat(HEX.formatHex(IntentAuthorityProgram.encodeRegisterPolicy(agent, version, policyHash)))
                .isEqualTo(v.path("register_policy_ix_hex").asText());
        assertThat(HEX.formatHex(IntentAuthorityProgram.encodeRevokePolicy()))
                .isEqualTo(v.path("revoke_policy_ix_hex").asText());
        assertThat(HEX.formatHex(IntentAuthorityProgram.encodeExecute(intentHash, version, policyHash, validUntil, nonce)))
                .isEqualTo(v.path("execute_ix_hex").asText());
    }

    @Test
    void accountLayoutsDecodeWhatTheProgramWrites() {
        PolicyAccount policy = IntentAuthorityProgram.decodePolicy(HEX.parseHex(v.path("policy_account_hex").asText()));
        assertThat(policy.authority()).isEqualTo(authority);
        assertThat(policy.agent()).isEqualTo(agent);
        assertThat(policy.policyVersion()).isEqualTo(version);
        assertThat(policy.policyHashHex()).isEqualTo(v.path("policy_hash_hex").asText());
        assertThat(policy.active()).isTrue();
        assertThat(policy.registeredSlot()).isEqualTo(v.path("registered_slot").asLong());
        assertThat(policy.bump()).isEqualTo(v.path("policy_pda").path("bump").asInt());

        NonceAccount n = IntentAuthorityProgram.decodeNonce(HEX.parseHex(v.path("nonce_account_hex").asText()));
        assertThat(n.agent()).isEqualTo(agent);
        assertThat(n.nonce()).isEqualTo(nonce);
        assertThat(HEX.formatHex(n.intentHash())).isEqualTo(v.path("intent_hash_hex").asText());
        assertThat(n.usedSlot()).isEqualTo(v.path("executed_slot").asLong());
        assertThat(n.bump()).isEqualTo(v.path("nonce_pda").path("bump").asInt());

        ReceiptAccount r = IntentAuthorityProgram.decodeReceipt(HEX.parseHex(v.path("receipt_account_hex").asText()));
        assertThat(r.intentHashHex()).isEqualTo(v.path("intent_hash_hex").asText());
        assertThat(r.agent()).isEqualTo(agent);
        assertThat(r.policyVersion()).isEqualTo(version);
        assertThat(r.policyHashHex()).isEqualTo(v.path("policy_hash_hex").asText());
        assertThat(r.nonce()).isEqualTo(nonce);
        assertThat(r.validUntilSlot()).isEqualTo(validUntil);
        assertThat(r.executedSlot()).isEqualTo(v.path("executed_slot").asLong());
        assertThat(r.bump()).isEqualTo(v.path("receipt_pda").path("bump").asInt());
    }

    @Test
    void decodersRefuseWrongLengthOrDiscriminator() {
        byte[] policy = HEX.parseHex(v.path("policy_account_hex").asText());
        byte[] receipt = HEX.parseHex(v.path("receipt_account_hex").asText());
        assertThatThrownBy(() -> IntentAuthorityProgram.decodePolicy(receipt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("111 bytes");
        assertThatThrownBy(() -> IntentAuthorityProgram.decodeReceipt(java.util.Arrays.copyOf(policy, 126)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("discriminator must be 3");
        byte[] badBool = policy.clone();
        badBool[1 + 32 + 32 + 4 + 32] = 2;
        assertThatThrownBy(() -> IntentAuthorityProgram.decodePolicy(badBool))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bool");
        assertThatThrownBy(() -> IntentAuthorityProgram.decodeNonce(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeInstructionListsTheAccountsTheProgramExpects() {
        String payer = SolanaKeypair.generate().publicKeyBase58();
        Instruction ix = program.execute(agent, payer, version, policyHash, intentHash, validUntil, nonce);
        assertThat(ix.programId()).isEqualTo(program.programId());
        assertThat(ix.accounts()).containsExactly(
                new LegacyTransaction.AccountMeta(agent, true, false),
                new LegacyTransaction.AccountMeta(payer, true, true),
                new LegacyTransaction.AccountMeta(v.path("policy_pda").path("address").asText(), false, false),
                new LegacyTransaction.AccountMeta(v.path("nonce_pda").path("address").asText(), false, true),
                new LegacyTransaction.AccountMeta(v.path("receipt_pda").path("address").asText(), false, true),
                new LegacyTransaction.AccountMeta(SystemProgram.PROGRAM_ID, false, false));
        assertThat(HEX.formatHex(ix.data())).isEqualTo(v.path("execute_ix_hex").asText());

        // Agent paying for itself: one signer, writable.
        Instruction self = program.execute(agent, agent, version, policyHash, intentHash, validUntil, nonce);
        assertThat(self.accounts().get(0)).isEqualTo(new LegacyTransaction.AccountMeta(agent, true, true));
        LegacyTransaction tx = new LegacyTransaction(agent, "11111111111111111111111111111111", List.of(self));
        assertThat(tx.numRequiredSignatures()).isEqualTo(1);
        assertThat(tx.accountKeys().get(0)).isEqualTo(agent);

        // Separate payer: two signatures, payer first as fee payer, agent second.
        LegacyTransaction two = new LegacyTransaction(payer, "11111111111111111111111111111111", List.of(ix));
        assertThat(two.numRequiredSignatures()).isEqualTo(2);
        assertThat(two.accountKeys().subList(0, 2)).containsExactly(payer, agent);
    }

    @Test
    void registerAndRevokeListTheirAccounts() {
        Instruction reg = program.registerPolicy(authority, agent, version, policyHash);
        assertThat(reg.accounts()).containsExactly(
                new LegacyTransaction.AccountMeta(authority, true, true),
                new LegacyTransaction.AccountMeta(v.path("policy_pda").path("address").asText(), false, true),
                new LegacyTransaction.AccountMeta(SystemProgram.PROGRAM_ID, false, false));
        assertThat(HEX.formatHex(reg.data())).isEqualTo(v.path("register_policy_ix_hex").asText());

        Instruction rev = program.revokePolicy(authority, agent, version);
        assertThat(rev.accounts()).containsExactly(
                new LegacyTransaction.AccountMeta(authority, true, false),
                new LegacyTransaction.AccountMeta(v.path("policy_pda").path("address").asText(), false, true));
        assertThat(rev.data()).containsExactly(1);
    }

    @Test
    void intentHashOfKan435IsTheReceiptSeed() {
        // The seed is the raw digest of `sha256:<hex>` — the same H_I that travels through the service.
        IntentHash h = IntentHash.parse("sha256:" + v.path("intent_hash_hex").asText());
        assertThat(program.receiptAddress(h.bytes()).base58()).isEqualTo(v.path("receipt_pda").path("address").asText());
    }

    @Test
    void errorCodesAreTheProgramsAndNameTheirInvariant() {
        assertThat(AuthorityError.values()).hasSize(13);
        assertThat(AuthorityError.fromCode(0)).isEqualTo(AuthorityError.AGENT_SIGNATURE_MISSING);
        assertThat(AuthorityError.fromCode(4)).isEqualTo(AuthorityError.POLICY_HASH_MISMATCH);
        assertThat(AuthorityError.fromCode(5)).isEqualTo(AuthorityError.INTENT_EXPIRED);
        assertThat(AuthorityError.fromCode(6)).isEqualTo(AuthorityError.NONCE_ALREADY_USED);
        assertThat(AuthorityError.fromCode(7)).isEqualTo(AuthorityError.INTENT_ALREADY_EXECUTED);
        assertThat(AuthorityError.fromCode(12)).isEqualTo(AuthorityError.INVALID_ACCOUNT_DATA);
        assertThatThrownBy(() -> AuthorityError.fromCode(13)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuthorityError.POLICY_REVOKED.invariant()).isEqualTo("I1");
        assertThat(AuthorityError.INTENT_EXPIRED.invariant()).isEqualTo("I2");
        assertThat(AuthorityError.NONCE_ALREADY_USED.invariant()).isEqualTo("I3");
        assertThat(AuthorityError.INTENT_ALREADY_EXECUTED.invariant()).isEqualTo("I3");
        assertThat(AuthorityError.POLICY_HASH_MISMATCH.invariant()).isEqualTo("I4");
        assertThat(AuthorityError.INVALID_PDA.invariant()).isEmpty();
    }

    @Test
    void inputValidation() {
        assertThatThrownBy(() -> new IntentAuthorityProgram("not-a-key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntentAuthorityProgram.encodeExecute(new byte[31], 1, policyHash, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("intent hash");
        assertThatThrownBy(() -> IntentAuthorityProgram.encodeRegisterPolicy(agent, 1L << 32, policyHash))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("u32");
        assertThatThrownBy(() -> IntentAuthorityProgram.encodeExecute(intentHash, 1, policyHash, -1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("u64");
        assertThat(Base58.isPublicKey(program.programId())).isTrue();
    }
}
