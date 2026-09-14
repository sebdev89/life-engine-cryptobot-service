package io.lifeengine.cryptobot.adapters.solana.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyTransactionTest {

    private static final String FROM = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    private static final String TO = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String BLOCKHASH = "So11111111111111111111111111111111111111112";

    @Test
    void transferMessageHasTheDocumentedLayout() {
        LegacyTransaction tx = new LegacyTransaction(FROM, BLOCKHASH, List.of(SystemProgram.transfer(FROM, TO, 1_500_000L)));
        byte[] msg = tx.serializeMessage();

        // header: 1 signer, 0 readonly signed, 1 readonly unsigned (the System Program)
        assertThat(msg[0]).isEqualTo((byte) 1);
        assertThat(msg[1]).isEqualTo((byte) 0);
        assertThat(msg[2]).isEqualTo((byte) 1);
        // 3 account keys: fee payer, destination, program
        assertThat(msg[3]).isEqualTo((byte) 3);
        assertThat(tx.accountKeys()).containsExactly(FROM, TO, SystemProgram.PROGRAM_ID);
        // 3 + 1 + 96 (keys) + 32 (blockhash) + 1 (ix count) + 1 (program idx) + 1 (acct count) + 2 (idxs) + 1 (data len) + 12 (data)
        assertThat(msg).hasSize(3 + 1 + 96 + 32 + 1 + 1 + 1 + 2 + 1 + 12);
        int ixStart = 3 + 1 + 96 + 32 + 1;
        assertThat(msg[ixStart]).isEqualTo((byte) 2); // program id index
        assertThat(msg[ixStart + 1]).isEqualTo((byte) 2); // two accounts
        assertThat(msg[ixStart + 2]).isEqualTo((byte) 0); // from
        assertThat(msg[ixStart + 3]).isEqualTo((byte) 1); // to
        assertThat(msg[ixStart + 4]).isEqualTo((byte) 12); // data length
        byte[] data = java.util.Arrays.copyOfRange(msg, ixStart + 5, ixStart + 17);
        assertThat(SystemProgram.decodeTransferLamports(data)).isEqualTo(1_500_000L);
    }

    @Test
    void unsignedWireFormIsSignatureVectorPlusMessage() {
        LegacyTransaction tx = new LegacyTransaction(FROM, BLOCKHASH, List.of(SystemProgram.transfer(FROM, TO, 1L)));
        byte[] wire = Base64.getDecoder().decode(tx.unsignedBase64());
        assertThat(wire[0]).isEqualTo((byte) 1);
        assertThat(wire).hasSize(1 + 64 + tx.serializeMessage().length);
        assertThat(java.util.Arrays.copyOfRange(wire, 1, 65)).containsOnly((byte) 0);
    }

    @Test
    void signedTransactionVerifiesAgainstTheFeePayerKey() {
        SolanaKeypair kp = SolanaKeypair.generate();
        LegacyTransaction tx = new LegacyTransaction(kp.publicKeyBase58(), BLOCKHASH, List.of(SystemProgram.transfer(kp.publicKeyBase58(), TO, 42L)));
        byte[] sig = kp.sign(tx.serializeMessage());
        byte[] wire = tx.serialize(List.of(sig));
        assertThat(SolanaKeypair.verify(kp.publicKeyBytes(), tx.serializeMessage(), sig)).isTrue();
        assertThat(SolanaKeypair.verify(Base58.decode(TO), tx.serializeMessage(), sig)).isFalse();
        assertThat(wire).hasSize(1 + 64 + tx.serializeMessage().length);
    }

    @Test
    void rejectsWrongSignatureCount() {
        LegacyTransaction tx = new LegacyTransaction(FROM, BLOCKHASH, List.of(SystemProgram.transfer(FROM, TO, 1L)));
        assertThatThrownBy(() -> tx.serialize(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemProgram.transfer(FROM, TO, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keypairRoundTripsSolanaKeygenLayout() {
        SolanaKeypair kp = SolanaKeypair.generate();
        SolanaKeypair again = SolanaKeypair.fromSecretKey(kp.secretKey());
        assertThat(again.publicKeyBase58()).isEqualTo(kp.publicKeyBase58());
        byte[] msg = "hello".getBytes();
        assertThat(SolanaKeypair.verify(kp.publicKeyBytes(), msg, again.sign(msg))).isTrue();
    }

    @Test
    void compactU16EncodesLikeShortvec() {
        assertThat(CompactU16.encode(0)).containsExactly(0);
        assertThat(CompactU16.encode(127)).containsExactly(127);
        assertThat(CompactU16.encode(128)).containsExactly(0x80, 0x01);
        assertThat(CompactU16.encode(16383)).containsExactly(0xFF, 0x7F);
        assertThat(CompactU16.encode(16384)).containsExactly(0x80, 0x80, 0x01);
    }
}
