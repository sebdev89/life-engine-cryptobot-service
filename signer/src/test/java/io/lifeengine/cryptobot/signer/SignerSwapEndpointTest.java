package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.swap.TokenAccounts;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code POST /api/signer/sign-swap} end to end (pilot): a real Jupiter legacy swap, re-owned to this test's key, is
 * signed only with an attestation for exactly its bytes that declares both checks, and only while the daily cap
 * holds — the reservation is taken before signing and never released.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SignerSwapEndpointTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();
    static final String BINANCE = SwapSigningPolicyTest.OWNER;
    static final List<String> BOTH = List.of(AttestationVerifier.CHECK_HASH_FROM_BYTES, AttestationVerifier.CHECK_JUPITER_SWAP);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
        // a swap of 0.01 SOL reserves ~10.03 M lamports: two fit in 25 M, a third does not
        r.add("signer.max-lamports-per-day", () -> "25000000");
        r.add("signer.swaps.enabled", () -> "true");
        r.add("signer.swaps.min-usdc-per-sol", () -> "50000000");
        r.add("signer.swaps.min-lamports-per-usdc", () -> "4700000");
        // 1.5 USDC a day: one 1-USDC swap fits, a second does not
        r.add("signer.swaps.max-usdc-per-day", () -> "1500000");
    }

    @Autowired private WebTestClient web;
    @Autowired private DailySpendLedger dailyCap;

    /** The real Binance-owned swap with the payer and its token accounts swapped for this test's key. */
    static byte[] ours() {
        return ours(SwapSigningPolicyTest.solToUsdc());
    }

    static byte[] ours(byte[] binanceSwap) {
        String me = KEY.publicKeyBase58();
        Map<String, String> remap = Map.of(BINANCE, me,
                TokenAccounts.associated(BINANCE, TokenAccounts.WSOL_MINT), TokenAccounts.associated(me, TokenAccounts.WSOL_MINT),
                TokenAccounts.associated(BINANCE, SwapSigningPolicy.USDC_MINT), TokenAccounts.associated(me, SwapSigningPolicy.USDC_MINT));
        return SwapSigningPolicyTest.rebuild(binanceSwap, ixs -> ixs.stream()
                .map(ix -> new LegacyTransaction.Instruction(ix.programId(), ix.accounts().stream()
                        .map(m -> new LegacyTransaction.AccountMeta(remap.getOrDefault(m.pubkey(), m.pubkey()), m.signer(), m.writable())).toList(), ix.data()))
                .toList(), me, null);
    }

    static byte[] message(byte[] wire) {
        return LegacyMessageDecoder.decodeTransaction(wire).message();
    }

    static AttestationVerifier.Attestation attest(String proposalId, byte[] message, List<String> checks) {
        return attest(proposalId, message, checks, swapJson(10_000_000L, 1_000_000L));
    }

    static AttestationVerifier.Attestation attest(String proposalId, byte[] message, List<String> checks, String swap) {
        long now = Instant.now().getEpochSecond();
        return Attestations.signed(VALIDATOR, Attestations.payload(proposalId, AttestationVerifier.sha256Hex(message), "ESCALATE",
                VALIDATOR.publicKeyBase58(), now, now + 90, "devnet", checks, swap));
    }

    /** The swap object as the validator signs it: SOL → USDC, the given input and minimum. */
    static String swapJson(long inAmount, long minOut) {
        return "{\"in_amount\":" + inAmount + ",\"input_mint\":\"" + TokenAccounts.WSOL_MINT + "\",\"min_out_amount\":" + minOut
                + ",\"output_mint\":\"" + SwapSigningPolicy.USDC_MINT + "\"}";
    }

    /** The least the re-owned real swap can return is 1 027 446 USDC units: an intent minimum of 1 000 000 is met. */
    static final long MIN_OUT = 1_000_000L;

    private WebTestClient.ResponseSpec signSwap(String proposalId, byte[] wire, String cluster, AttestationVerifier.Attestation att) {
        return signSwap(proposalId, wire, cluster, MIN_OUT, att);
    }

    private WebTestClient.ResponseSpec signSwap(String proposalId, byte[] wire, String cluster, long minOut, AttestationVerifier.Attestation att) {
        return web.post().uri("/api/signer/sign-swap").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", proposalId, "unsignedTransactionBase64", Base64.getEncoder().encodeToString(wire), "cluster", cluster,
                        "minOut", minOut, "attestation", Map.of("payload", att.payload(), "signature", att.signature())))
                .exchange();
    }

    @Test
    void signsTheAttestedSwapReservesItsSolAndStopsAtTheDailyCap() {
        byte[] wire = ours();
        byte[] msg = message(wire);
        long before = dailyCap.spentInWindow();

        byte[] body = signSwap("swap-1", wire, "devnet", attest("swap-1", msg, BOTH))
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        byte[] signed = Base64.getDecoder().decode(new String(body).replaceAll(".*\"signedTransactionBase64\":\"([^\"]+)\".*", "$1"));
        assertThat(Arrays.copyOfRange(signed, 65, signed.length)).isEqualTo(msg);
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), msg, Arrays.copyOfRange(signed, 1, 65))).isTrue();
        long reserved = dailyCap.spentInWindow() - before;
        assertThat(reserved).isEqualTo(SwapSigningPolicy.evaluate(wire, KEY.publicKeyBase58(),
                new SwapProperties(true, null, null, null, null, 50_000_000L, 4_700_000L, null).limits(), MIN_OUT).lamportsOut()).isGreaterThan(10_000_000L);

        signSwap("swap-2", wire, "devnet", attest("swap-2", msg, BOTH)).expectStatus().isOk();
        signSwap("swap-3", wire, "devnet", attest("swap-3", msg, BOTH)).expectStatus().isForbidden()
                .expectBody().jsonPath("$.reason").isEqualTo("daily_cap");
    }

    @Test
    void anAttestationWithoutTheSwapCheckIsRefusedBeforeAnyReservation() {
        long before = dailyCap.spentInWindow();
        byte[] wire = ours();
        signSwap("swap-a", wire, "devnet", attest("swap-a", message(wire), List.of(AttestationVerifier.CHECK_HASH_FROM_BYTES)))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_not_checked");
        assertThat(dailyCap.spentInWindow()).isEqualTo(before);
    }

    @Test
    void theIntentMinimumTravelsWithTheRequestAndIsEnforced() {
        byte[] wire = ours();
        long before = dailyCap.spentInWindow();
        signSwap("swap-m1", wire, "devnet", 1_027_447L, attest("swap-m1", message(wire), BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("minimum_received_too_low");
        signSwap("swap-m2", wire, "devnet", 0L, attest("swap-m2", message(wire), BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("min_out_missing");
        assertThat(dailyCap.spentInWindow()).isEqualTo(before);
    }

    @Test
    void theExactMinimumIsTheAttestedOneAndTheAttestedSwapIsTheOneInTheBytes() {
        byte[] wire = ours();
        byte[] msg = message(wire);
        long before = dailyCap.spentInWindow();
        // no swap in the attestation
        signSwap("swap-s1", wire, "devnet", attest("swap-s1", msg, BOTH, null))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_missing");
        // the validator attested a higher minimum than the one this request asks the policy to enforce
        signSwap("swap-s2", wire, "devnet", MIN_OUT, attest("swap-s2", msg, BOTH, swapJson(10_000_000L, 1_020_000L)))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_mismatch");
        // the validator attested another amount than the one in the bytes
        signSwap("swap-s3", wire, "devnet", attest("swap-s3", msg, BOTH, swapJson(5_000_000L, MIN_OUT)))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_mismatch");
        // the validator attested the other direction
        String reversed = "{\"in_amount\":10000000,\"input_mint\":\"" + SwapSigningPolicy.USDC_MINT + "\",\"min_out_amount\":1000000,"
                + "\"output_mint\":\"" + TokenAccounts.WSOL_MINT + "\"}";
        signSwap("swap-s4", wire, "devnet", attest("swap-s4", msg, BOTH, reversed))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_mismatch");
        assertThat(dailyCap.spentInWindow()).isEqualTo(before);
    }

    @Test
    void aRequestMinimumAboveTheAttestedOneIsAMismatchToo() {
        byte[] wire = ours();
        // 1 020 000 still passes the policy (the route returns ≥ 1 027 446), but the validator attested 1 000 000
        signSwap("swap-s5", wire, "devnet", 1_020_000L, attest("swap-s5", message(wire), BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_swap_mismatch");
    }

    @Test
    void aUsdcInputIsCappedPerDayInUsdcNotOnlyInFees() {
        byte[] wire = ours(SwapSigningPolicyTest.fixture("legacy-usdc-sol-binance2.json"));
        String reversed = "{\"in_amount\":1000000,\"input_mint\":\"" + SwapSigningPolicy.USDC_MINT + "\",\"min_out_amount\":9000000,"
                + "\"output_mint\":\"" + TokenAccounts.WSOL_MINT + "\"}";
        signSwap("swap-u1", wire, "devnet", 9_000_000L, attest("swap-u1", message(wire), BOTH, reversed)).expectStatus().isOk();
        // the second 1-USDC swap would take the day to 2 USDC > 1.5: refused on USDC even though its SOL fees fit
        long lamportsBefore = dailyCap.spentInWindow();
        signSwap("swap-u2", wire, "devnet", 9_000_000L, attest("swap-u2", message(wire), BOTH, reversed))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("daily_cap_usdc");
        assertThat(dailyCap.spentInWindow()).isEqualTo(lamportsBefore);
    }

    @Test
    void anAttestationForOtherBytesIsRefused() {
        byte[] wire = ours();
        byte[] other = message(SwapSigningPolicyTest.rebuild(wire, ixs -> ixs, null, "11111111111111111111111111111112"));
        signSwap("swap-b", wire, "devnet", attest("swap-b", other, BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_message_mismatch");
    }

    @Test
    void aSwapThatIsNotOursOrForMainnetIsRefused() {
        byte[] binance = SwapSigningPolicyTest.solToUsdc();
        signSwap("swap-c", binance, "devnet", attest("swap-c", message(binance), BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("fee_payer_mismatch");
        byte[] wire = ours();
        signSwap("swap-d", wire, "mainnet-beta", attest("swap-d", message(wire), BOTH))
                .expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("mainnet_disabled");
    }

    @Test
    void theGuardRefusesMainnetSwapsWithoutTheMainnetGate() {
        SignerProperties mainnet = new SignerProperties("", "", "t", "mainnet-beta", 1L, List.of(), true, "", true, false);
        SwapProperties on = new SwapProperties(true, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new SwapGuard(mainnet, on)).isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-mainnet");
        // with the gate, or with swaps off, it starts
        new SwapGuard(new SignerProperties("", "", "t", "mainnet-beta", 1L, List.of(), true, "", true, true), on);
        new SwapGuard(mainnet, new SwapProperties(false, null, null, null, null, null, null, null));
        // and swaps are off unless turned on
        assertThat(new SwapProperties(null, null, null, null, null, null, null, null).enabled()).isFalse();
    }
}
