package io.lifeengine.cryptobot.solana.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The PDA derivation against the Solana SDK's answers ({@code vectors-v1.json}, written by
 * {@code programs/intent-authority/tests/vectors.rs} with {@code Pubkey::find_program_address}
 * and {@code Pubkey::is_on_curve}). The curve test is the part that is easy to get subtly wrong,
 * so it is pinned case by case.
 */
class ProgramDerivedAddressTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode vectors() throws Exception {
        try (InputStream in = ProgramDerivedAddressTest.class.getResourceAsStream("/authority/vectors-v1.json")) {
            return JSON.readTree(in);
        }
    }

    @TestFactory
    List<DynamicTest> isOnCurveAgreesWithTheSdk() throws Exception {
        JsonNode root = vectors();
        List<DynamicTest> tests = new ArrayList<>();
        int on = 0;
        int off = 0;
        for (JsonNode c : root.path("curve_cases")) {
            String pubkey = c.path("pubkey").asText();
            boolean expected = c.path("on_curve").asBoolean();
            if (expected) {
                on++;
            } else {
                off++;
            }
            tests.add(DynamicTest.dynamicTest((expected ? "on  " : "off ") + pubkey, () ->
                    assertThat(ProgramDerivedAddress.isOnCurve(Base58.decode(pubkey))).isEqualTo(expected)));
        }
        assertThat(on).isGreaterThanOrEqualTo(5);
        assertThat(off).isGreaterThanOrEqualTo(5);
        return tests;
    }

    @Test
    void everyRealEd25519PublicKeyIsOnCurve() {
        for (int i = 0; i < 64; i++) {
            assertThat(ProgramDerivedAddress.isOnCurve(SolanaKeypair.generate().publicKeyBytes())).isTrue();
        }
    }

    @Test
    void findMatchesTheSdkForTheThreeProgramPdas() throws Exception {
        JsonNode v = vectors();
        byte[] programId = Base58.decode(v.path("program_id").asText());
        byte[] agent = Base58.decode(v.path("agent").asText());
        long version = v.path("policy_version").asLong();
        long nonce = v.path("nonce").asLong();
        byte[] intent = java.util.HexFormat.of().parseHex(v.path("intent_hash_hex").asText());

        ProgramDerivedAddress.Derived policy = ProgramDerivedAddress.find(
                List.of("policy".getBytes(StandardCharsets.US_ASCII), agent, le32(version)), programId);
        assertThat(policy.base58()).isEqualTo(v.path("policy_pda").path("address").asText());
        assertThat(policy.bump()).isEqualTo(v.path("policy_pda").path("bump").asInt());

        ProgramDerivedAddress.Derived nonceAddr = ProgramDerivedAddress.find(
                List.of("nonce".getBytes(StandardCharsets.US_ASCII), agent, le64(nonce)), programId);
        assertThat(nonceAddr.base58()).isEqualTo(v.path("nonce_pda").path("address").asText());
        assertThat(nonceAddr.bump()).isEqualTo(v.path("nonce_pda").path("bump").asInt());

        ProgramDerivedAddress.Derived receipt = ProgramDerivedAddress.find(
                List.of("receipt".getBytes(StandardCharsets.US_ASCII), intent), programId);
        assertThat(receipt.base58()).isEqualTo(v.path("receipt_pda").path("address").asText());
        assertThat(receipt.bump()).isEqualTo(v.path("receipt_pda").path("bump").asInt());

        // The bump the SDK chose is the first off-curve one: every higher bump is on-curve.
        for (int bump = 255; bump > policy.bump(); bump--) {
            int b = bump;
            assertThatThrownBy(() -> ProgramDerivedAddress.create(
                            List.of("policy".getBytes(StandardCharsets.US_ASCII), agent, le32(version), new byte[] {(byte) b}),
                            programId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("on the Ed25519 curve");
        }
        byte[] created = ProgramDerivedAddress.create(
                List.of("policy".getBytes(StandardCharsets.US_ASCII), agent, le32(version), new byte[] {(byte) policy.bump()}),
                programId);
        assertThat(created).isEqualTo(policy.address());
    }

    @Test
    void derivedAddressesAreNeverOnCurve() {
        byte[] programId = Base58.decode("2yqAF5YSNgtLcmFsvmmMTbFnAuXCMQMGX4YxfqK2PhmZ");
        for (int i = 0; i < 200; i++) {
            ProgramDerivedAddress.Derived d = ProgramDerivedAddress.find(
                    List.of(("seed-" + i).getBytes(StandardCharsets.US_ASCII)), programId);
            assertThat(ProgramDerivedAddress.isOnCurve(d.address())).isFalse();
            assertThat(d.bump()).isBetween(0, 255);
        }
    }

    @Test
    void seedLimitsAreTheSdks() {
        byte[] programId = new byte[32];
        assertThatThrownBy(() -> ProgramDerivedAddress.find(List.of(new byte[33]), programId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        List<byte[]> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(new byte[] {(byte) i});
        }
        assertThatThrownBy(() -> ProgramDerivedAddress.find(sixteen, programId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15 seeds");
        assertThatThrownBy(() -> ProgramDerivedAddress.find(List.of(new byte[1]), new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program id");
        assertThatThrownBy(() -> ProgramDerivedAddress.isOnCurve(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] le32(long v) {
        return java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt((int) v).array();
    }

    private static byte[] le64(long v) {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }
}
