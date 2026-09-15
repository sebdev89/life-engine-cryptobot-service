package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.util.Base64;
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

    private final SignerProperties props;
    private final SignerKeyStore keys;

    public SigningPolicy(SignerProperties props, SignerKeyStore keys) {
        this.props = props;
        this.keys = keys;
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
