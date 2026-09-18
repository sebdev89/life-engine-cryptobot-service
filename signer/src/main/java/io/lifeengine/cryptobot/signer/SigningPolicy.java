package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What this signer is willing to sign, decided from the transaction bytes themselves:
 *
 * <ol>
 *   <li>exactly one required signature, and it is ours (fee payer == our public key);
 *   <li>exactly one instruction, and it is {@code SystemProgram.transfer};
 *   <li>the transfer is from us to an allow-listed destination;
 *   <li>lamports ≤ the cap.
 * </ol>
 *
 * Anything else is refused with a reason — including a perfectly valid transaction that merely
 * does something we did not agree to sign.
 */
@Component
public class SigningPolicy {

    public record Verdict(boolean allowed, String reason, long lamports, String destination, LegacyMessageDecoder.Decoded decoded) {}

    /** SPL Memo v2 — the one program besides System the signer knows, and only for a receipt-batch memo (KAN-394). */
    public static final String MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    /** Same regular expression as {@code AnchorMemo} in the service — the signer does not trust the caller's description. */
    static final Pattern ANCHOR_MEMO = Pattern.compile(
            "^ir/1 root=(sha256:[0-9a-f]{64}) n=([1-9][0-9]{0,8}) ts=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");

    private final SignerProperties props;
    private final SignerKeyStore keys;

    public SigningPolicy(SignerProperties props, SignerKeyStore keys) {
        this.props = props;
        this.keys = keys;
    }

    /**
     * The second and last shape this signer signs: a devnet transaction whose only instruction is
     * an SPL Memo with no accounts (nothing moves, nothing else signs) and whose text is exactly the
     * anchor memo {@code ir/1 root=… n=… ts=…} for the root and count the caller claims. Policy:
     * anchoring is devnet-only, so a signer configured for any other cluster refuses.
     */
    public Verdict evaluateAnchor(String unsignedBase64, String expectedFeePayer, String root, int receiptCount) {
        if (!props.enabled()) {
            return refuse("signer_disabled");
        }
        if (!"devnet".equalsIgnoreCase(props.cluster())) {
            return refuse("anchor_cluster_not_devnet");
        }
        LegacyMessageDecoder.Decoded tx;
        try {
            tx = LegacyMessageDecoder.decodeTransaction(Base64.getDecoder().decode(unsignedBase64));
        } catch (Exception e) {
            return refuse("undecodable_transaction: " + e.getMessage());
        }
        if (tx.numRequiredSignatures() != 1 || tx.signatureCount() != 1) {
            return refuse("multiple_signers");
        }
        String feePayer = tx.accountKeys().get(0);
        if (!feePayer.equals(keys.publicKey())) {
            return refuse("fee_payer_mismatch");
        }
        if (expectedFeePayer != null && !expectedFeePayer.isBlank() && !expectedFeePayer.equals(feePayer)) {
            return refuse("expected_fee_payer_mismatch");
        }
        if (tx.instructions().size() != 1) {
            return refuse("instruction_count");
        }
        LegacyMessageDecoder.Instruction ix = tx.instructions().get(0);
        if (!MEMO_PROGRAM_ID.equals(ix.programId())) {
            return refuse("program_not_allowed");
        }
        if (!ix.accounts().isEmpty()) {
            return refuse("memo_accounts_not_allowed");
        }
        String memo = new String(ix.data(), StandardCharsets.UTF_8);
        Matcher m = ANCHOR_MEMO.matcher(memo);
        if (!m.matches()) {
            return refuse("memo_format");
        }
        if (root == null || !m.group(1).equals(root.trim().toLowerCase(Locale.ROOT)) || Integer.parseInt(m.group(2)) != receiptCount) {
            return refuse("memo_mismatch");
        }
        return new Verdict(true, null, 0, null, tx);
    }

    public Verdict evaluate(String unsignedBase64, String expectedFeePayer) {
        if (!props.enabled()) {
            return refuse("signer_disabled");
        }
        LegacyMessageDecoder.Decoded tx;
        try {
            tx = LegacyMessageDecoder.decodeTransaction(Base64.getDecoder().decode(unsignedBase64));
        } catch (Exception e) {
            return refuse("undecodable_transaction: " + e.getMessage());
        }
        if (tx.numRequiredSignatures() != 1 || tx.signatureCount() != 1) {
            return refuse("multiple_signers");
        }
        String feePayer = tx.accountKeys().get(0);
        if (!feePayer.equals(keys.publicKey())) {
            return refuse("fee_payer_mismatch");
        }
        if (expectedFeePayer != null && !expectedFeePayer.isBlank() && !expectedFeePayer.equals(feePayer)) {
            return refuse("expected_fee_payer_mismatch");
        }
        if (tx.instructions().size() != 1) {
            return refuse("instruction_count");
        }
        LegacyMessageDecoder.Instruction ix = tx.instructions().get(0);
        if (!SystemProgram.PROGRAM_ID.equals(ix.programId())) {
            return refuse("program_not_allowed");
        }
        long lamports = SystemProgram.decodeTransferLamports(ix.data());
        if (lamports < 0) {
            return refuse("not_a_transfer");
        }
        if (ix.accounts().size() != 2 || !ix.accounts().get(0).equals(feePayer)) {
            return refuse("transfer_source_mismatch");
        }
        String destination = ix.accounts().get(1);
        if (props.allowedDestinations().isEmpty() || !props.allowedDestinations().contains(destination)) {
            return refuse("destination_not_allowed");
        }
        if (lamports > props.maxLamports()) {
            return refuse("amount_over_cap");
        }
        return new Verdict(true, null, lamports, destination, tx);
    }

    private static Verdict refuse(String reason) {
        return new Verdict(false, reason, -1, null, null);
    }
}
