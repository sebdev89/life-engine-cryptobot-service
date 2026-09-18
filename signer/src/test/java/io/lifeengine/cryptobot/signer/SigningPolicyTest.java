package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.util.List;
import org.junit.jupiter.api.Test;

class SigningPolicyTest {

    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String OTHER = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";

    static final SolanaKeypair KEY = SolanaKeypair.generate();

    static SignerProperties props(long cap, List<String> allowed, boolean enabled) {
        return new SignerProperties("", keyJson(KEY), "t", "devnet", cap, allowed, enabled, "", false);
    }

    static String keyJson(SolanaKeypair kp) {
        byte[] sk = kp.secretKey();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sk.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(sk[i] & 0xff);
        }
        return sb.append(']').toString();
    }

    static SigningPolicy policy(SignerProperties p) {
        return new SigningPolicy(p, new SignerKeyStore(p));
    }

    static String transfer(String from, String to, long lamports) {
        return new LegacyTransaction(from, BLOCKHASH, List.of(SystemProgram.transfer(from, to, lamports))).unsignedBase64();
    }

    @Test
    void signsAnAllowlistedTransferUnderTheCap() {
        SigningPolicy.Verdict v = policy(props(1_000_000L, List.of(VAULT), true)).evaluate(transfer(KEY.publicKeyBase58(), VAULT, 500_000L), KEY.publicKeyBase58());
        assertThat(v.allowed()).isTrue();
        assertThat(v.lamports()).isEqualTo(500_000L);
        assertThat(v.destination()).isEqualTo(VAULT);
    }

    @Test
    void refusesOverCap() {
        SigningPolicy.Verdict v = policy(props(1_000_000L, List.of(VAULT), true)).evaluate(transfer(KEY.publicKeyBase58(), VAULT, 1_000_001L), null);
        assertThat(v.allowed()).isFalse();
        assertThat(v.reason()).isEqualTo("amount_over_cap");
    }

    @Test
    void refusesUnknownDestinationAndEmptyAllowlist() {
        assertThat(policy(props(1_000_000L, List.of(VAULT), true)).evaluate(transfer(KEY.publicKeyBase58(), OTHER, 1L), null).reason()).isEqualTo("destination_not_allowed");
        assertThat(policy(props(1_000_000L, List.of(), true)).evaluate(transfer(KEY.publicKeyBase58(), VAULT, 1L), null).reason()).isEqualTo("destination_not_allowed");
    }

    @Test
    void refusesWhenFeePayerIsNotOurKey() {
        SolanaKeypair someoneElse = SolanaKeypair.generate();
        SigningPolicy.Verdict v = policy(props(1_000_000L, List.of(VAULT), true)).evaluate(transfer(someoneElse.publicKeyBase58(), VAULT, 1L), null);
        assertThat(v.reason()).isEqualTo("fee_payer_mismatch");
    }

    @Test
    void refusesWhenCallerExpectsADifferentFeePayer() {
        SigningPolicy.Verdict v = policy(props(1_000_000L, List.of(VAULT), true)).evaluate(transfer(KEY.publicKeyBase58(), VAULT, 1L), OTHER);
        assertThat(v.reason()).isEqualTo("expected_fee_payer_mismatch");
    }

    @Test
    void refusesNonTransferProgramsAndMultipleInstructions() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction.Instruction memo = new LegacyTransaction.Instruction(
                "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", List.of(new LegacyTransaction.AccountMeta(me, true, false)), "hi".getBytes());
        String memoTx = new LegacyTransaction(me, BLOCKHASH, List.of(memo)).unsignedBase64();
        assertThat(policy(props(1_000_000L, List.of(VAULT), true)).evaluate(memoTx, null).reason()).isEqualTo("program_not_allowed");
        String two = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1L), SystemProgram.transfer(me, VAULT, 1L))).unsignedBase64();
        assertThat(policy(props(1_000_000L, List.of(VAULT), true)).evaluate(two, null).reason()).isEqualTo("instruction_count");
    }

    @Test
    void refusesGarbageAndDisabled() {
        assertThat(policy(props(1_000_000L, List.of(VAULT), true)).evaluate("bm90IGEgdHg=", null).reason()).startsWith("undecodable_transaction");
        assertThat(policy(props(1_000_000L, List.of(VAULT), false)).evaluate(transfer(KEY.publicKeyBase58(), VAULT, 1L), null).reason()).isEqualTo("signer_disabled");
    }

    // ---- KAN-394: the anchor memo, the only non-transfer this signer signs ----------------------

    static final String ROOT = "sha256:" + "ab".repeat(32);
    static final String MEMO = "ir/1 root=" + ROOT + " n=3 ts=2026-09-18T03:00:00Z";

    static LegacyTransaction.Instruction memo(String text, List<LegacyTransaction.AccountMeta> accounts) {
        return new LegacyTransaction.Instruction(SigningPolicy.MEMO_PROGRAM_ID, accounts, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String memoTx(String feePayer, String text) {
        return new LegacyTransaction(feePayer, BLOCKHASH, List.of(memo(text, List.of()))).unsignedBase64();
    }

    static SignerProperties propsOn(String cluster) {
        return new SignerProperties("", keyJson(KEY), "t", cluster, 1_000_000L, List.of(VAULT), true, "", false);
    }

    @Test
    void signsExactlyTheAnchorMemoItWasAskedForOnDevnet() {
        SigningPolicy.Verdict v = policy(propsOn("devnet")).evaluateAnchor(memoTx(KEY.publicKeyBase58(), MEMO), KEY.publicKeyBase58(), ROOT, 3);
        assertThat(v.allowed()).isTrue();
        assertThat(v.lamports()).isZero();
        assertThat(v.destination()).isNull();
        // Upper-case root from the caller is normalised; the memo itself must be lower-case.
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(KEY.publicKeyBase58(), MEMO), null, ROOT.toUpperCase(), 3).allowed()).isTrue();
    }

    @Test
    void refusesAnchorsOffDevnetOrWithAnotherRootCountFormatAccountsOrProgram() {
        String me = KEY.publicKeyBase58();
        assertThat(policy(propsOn("mainnet-beta")).evaluateAnchor(memoTx(me, MEMO), me, ROOT, 3).reason()).isEqualTo("anchor_cluster_not_devnet");
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(me, MEMO), me, "sha256:" + "cd".repeat(32), 3).reason()).isEqualTo("memo_mismatch");
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(me, MEMO), me, ROOT, 4).reason()).isEqualTo("memo_mismatch");
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(me, "hello " + MEMO), me, ROOT, 3).reason()).isEqualTo("memo_format");
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(me, MEMO + " and more"), me, ROOT, 3).reason()).isEqualTo("memo_format");
        String withAccounts = new LegacyTransaction(me, BLOCKHASH, List.of(memo(MEMO, List.of(new LegacyTransaction.AccountMeta(me, true, false))))).unsignedBase64();
        assertThat(policy(propsOn("devnet")).evaluateAnchor(withAccounts, me, ROOT, 3).reason()).isEqualTo("memo_accounts_not_allowed");
        String transfer = transfer(me, VAULT, 1L);
        assertThat(policy(propsOn("devnet")).evaluateAnchor(transfer, me, ROOT, 3).reason()).isEqualTo("program_not_allowed");
        String two = new LegacyTransaction(me, BLOCKHASH, List.of(memo(MEMO, List.of()), SystemProgram.transfer(me, VAULT, 1L))).unsignedBase64();
        assertThat(policy(propsOn("devnet")).evaluateAnchor(two, me, ROOT, 3).reason()).isEqualTo("instruction_count");
        SolanaKeypair someoneElse = SolanaKeypair.generate();
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(someoneElse.publicKeyBase58(), MEMO), null, ROOT, 3).reason()).isEqualTo("fee_payer_mismatch");
        assertThat(policy(propsOn("devnet")).evaluateAnchor(memoTx(me, MEMO), OTHER, ROOT, 3).reason()).isEqualTo("expected_fee_payer_mismatch");
        assertThat(policy(props(1L, List.of(VAULT), false)).evaluateAnchor(memoTx(me, MEMO), me, ROOT, 3).reason()).isEqualTo("signer_disabled");
        // The transfer policy is untouched by the memo path: a memo is still refused on /sign.
        assertThat(policy(propsOn("devnet")).evaluate(memoTx(me, MEMO), me).reason()).isEqualTo("program_not_allowed");
    }
}
