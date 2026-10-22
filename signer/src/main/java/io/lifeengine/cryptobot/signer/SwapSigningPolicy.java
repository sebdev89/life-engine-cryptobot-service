package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.swap.TokenAccounts;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What this signer is willing to sign as a Jupiter swap (CryptoBot pilot), decided from the bytes alone and
 * independently of the validator — the validator attests, this decides again with its own code. Fail-closed at
 * every step: anything not listed here is a refusal.
 *
 * <ul>
 *   <li>a <b>legacy</b> transaction (Jupiter's {@code asLegacyTransaction=true}): no address lookup tables, so every
 *       account is written in the message and nothing is assumed. A versioned message is refused by the decoder;
 *   <li>exactly one required signature, ours (the fee payer), and nothing else signs;
 *   <li>every instruction calls a program on {@link #ALLOWED_PROGRAMS}, and only these shapes:
 *       compute budget unit limit / unit price (priority fee capped); associated-token-account creation paid by
 *       and for us under the classic token program; one system transfer from us to our own wrapped-SOL account
 *       (when the input is SOL, never above the swap amount); SPL token sync-native and close of our wrapped-SOL
 *       account back to us; exactly one Jupiter v6 {@code route} or {@code shared_accounts_route};
 *   <li>the route: we are the authority, the source is our token account for one allowed mint and the destination
 *       our token account for the other, no platform fee account and fee 0, slippage within the cap, and the input
 *       amount within the per-swap cap of the input mint.
 * </ul>
 *
 * <p>{@link Verdict#lamportsOut()} is what the swap can take from our SOL: the input when it is SOL, plus base and
 * priority fees and the rent of every account it creates — what the daily cap reserves. The accounts Jupiter's route
 * passes on to its pools are Jupiter's; what bounds them is the minimum received the Jupiter program enforces on
 * chain, which the validator attested against the intent.
 */
public final class SwapSigningPolicy {

    public static final String JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
    public static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    /** Frozen; SwapSigningPolicyTest compares it with the validator's own list. */
    public static final Set<String> ALLOWED_PROGRAMS = Set.of(JUPITER_V6, TokenAccounts.COMPUTE_BUDGET_PROGRAM, TokenAccounts.SYSTEM_PROGRAM,
            TokenAccounts.TOKEN_PROGRAM, TokenAccounts.ATA_PROGRAM);
    public static final Set<String> ALLOWED_MINTS = Set.of(TokenAccounts.WSOL_MINT, USDC_MINT);
    static final long BASE_FEE_LAMPORTS = 5_000L;
    /** Rent-exempt minimum of a 165-byte SPL token account. */
    static final long TOKEN_ACCOUNT_RENT_LAMPORTS = 2_039_280L;
    static final long DEFAULT_UNIT_LIMIT = 1_400_000L;
    static final byte[] ROUTE = discriminator("route");
    static final byte[] SHARED_ACCOUNTS_ROUTE = discriminator("shared_accounts_route");

    /** Per-swap caps. {@code maxInputByMint}: the largest input amount, in base units, per allowed input mint. */
    public record Limits(long maxLamportsIn, long maxUsdcIn, long maxPriorityFeeLamports, int maxSlippageBps) {
        long maxIn(String mint) {
            return TokenAccounts.WSOL_MINT.equals(mint) ? maxLamportsIn : USDC_MINT.equals(mint) ? maxUsdcIn : -1;
        }
    }

    public record Verdict(boolean allowed, String reason, String inputMint, String outputMint, long inAmount, long lamportsOut,
            LegacyMessageDecoder.Decoded decoded) {
        static Verdict refuse(String reason) {
            return new Verdict(false, reason, null, null, 0, 0, null);
        }
    }

    private SwapSigningPolicy() {}

    public static Verdict evaluate(byte[] wire, String owner, Limits limits) {
        LegacyMessageDecoder.Decoded tx;
        try {
            tx = LegacyMessageDecoder.decodeTransaction(wire);
        } catch (RuntimeException e) {
            return Verdict.refuse("undecodable_transaction");
        }
        if (tx.signatureCount() != 1 || tx.numRequiredSignatures() != 1) {
            return Verdict.refuse("multiple_signers");
        }
        if (!Arrays.equals(Arrays.copyOfRange(wire, 1, 65), new byte[64])) {
            return Verdict.refuse("already_signed");
        }
        if (!tx.accountKeys().get(0).equals(owner)) {
            return Verdict.refuse("fee_payer_mismatch");
        }
        String wsolAccount = TokenAccounts.associated(owner, TokenAccounts.WSOL_MINT);
        long unitLimit = DEFAULT_UNIT_LIMIT;
        long microLamports = 0;
        long wrapped = 0;
        int created = 0;
        int jupiter = 0;
        String inputMint = null;
        String outputMint = null;
        long inAmount = 0;
        for (LegacyMessageDecoder.Instruction ix : tx.instructions()) {
            String program = ix.programId();
            List<String> a = ix.accounts();
            byte[] d = ix.data();
            if (!ALLOWED_PROGRAMS.contains(program)) {
                return Verdict.refuse("program_not_allowed");
            }
            switch (program) {
                case TokenAccounts.COMPUTE_BUDGET_PROGRAM -> {
                    if (!a.isEmpty()) {
                        return Verdict.refuse("compute_budget_not_allowed");
                    }
                    if (d.length == 5 && d[0] == 2) {
                        unitLimit = Integer.toUnsignedLong(le(d, 1, 4).getInt());
                    } else if (d.length == 9 && d[0] == 3) {
                        microLamports = le(d, 1, 8).getLong();
                        if (microLamports < 0) {
                            return Verdict.refuse("priority_fee_too_high");
                        }
                    } else {
                        return Verdict.refuse("compute_budget_not_allowed");
                    }
                }
                case TokenAccounts.ATA_PROGRAM -> {
                    if (!(d.length == 0 || (d.length == 1 && (d[0] == 0 || d[0] == 1))) || a.size() != 6
                            || !a.get(0).equals(owner) || !a.get(2).equals(owner) || !a.get(4).equals(TokenAccounts.SYSTEM_PROGRAM)
                            || !a.get(5).equals(TokenAccounts.TOKEN_PROGRAM) || !ALLOWED_MINTS.contains(a.get(3))
                            || !a.get(1).equals(TokenAccounts.associated(owner, a.get(3)))) {
                        return Verdict.refuse("ata_instruction_not_allowed");
                    }
                    created++;
                }
                case TokenAccounts.SYSTEM_PROGRAM -> {
                    if (d.length != 12 || le(d, 0, 4).getInt() != 2 || a.size() != 2 || !a.get(0).equals(owner) || !a.get(1).equals(wsolAccount)) {
                        return Verdict.refuse("system_instruction_not_allowed");
                    }
                    long lamports = le(d, 4, 8).getLong();
                    if (lamports < 0) {
                        return Verdict.refuse("system_instruction_not_allowed");
                    }
                    try {
                        wrapped = Math.addExact(wrapped, lamports);
                    } catch (ArithmeticException overflow) {
                        return Verdict.refuse("wrap_exceeds_amount");
                    }
                }
                case TokenAccounts.TOKEN_PROGRAM -> {
                    boolean sync = d.length == 1 && d[0] == 17 && a.size() == 1 && a.get(0).equals(wsolAccount);
                    boolean close = d.length == 1 && d[0] == 9 && a.size() == 3 && a.get(0).equals(wsolAccount) && a.get(1).equals(owner)
                            && a.get(2).equals(owner);
                    if (!sync && !close) {
                        return Verdict.refuse("token_instruction_not_allowed");
                    }
                }
                default -> { // JUPITER_V6
                    jupiter++;
                    byte[] disc = d.length >= 8 ? Arrays.copyOf(d, 8) : new byte[0];
                    boolean plain = Arrays.equals(disc, ROUTE);
                    boolean shared = Arrays.equals(disc, SHARED_ACCOUNTS_ROUTE);
                    if (!(plain || shared) || d.length < 27 || a.size() < (plain ? 9 : 13)) {
                        return Verdict.refuse("jupiter_instruction_not_allowed");
                    }
                    String authority = plain ? a.get(1) : a.get(2);
                    String source = plain ? a.get(2) : a.get(3);
                    String destination = plain ? a.get(3) : a.get(6);
                    String feeAccount = plain ? a.get(6) : a.get(9);
                    if (!a.get(0).equals(TokenAccounts.TOKEN_PROGRAM) || !authority.equals(owner)) {
                        return Verdict.refuse("route_authority_mismatch");
                    }
                    if (plain && !a.get(4).equals(JUPITER_V6) && !a.get(4).equals(destination)) {
                        return Verdict.refuse("route_destination_mismatch");
                    }
                    inputMint = mintOf(owner, source);
                    outputMint = mintOf(owner, destination);
                    if (inputMint == null || outputMint == null || inputMint.equals(outputMint)) {
                        return Verdict.refuse(inputMint == null ? "route_source_mismatch" : "route_destination_mismatch");
                    }
                    if (!feeAccount.equals(JUPITER_V6)) {
                        return Verdict.refuse("platform_fee");
                    }
                    ByteBuffer tail = le(d, d.length - 19, 19);
                    inAmount = tail.getLong();
                    tail.getLong(); // quoted out: the minimum is the validator's to attest against the intent
                    int slippage = Short.toUnsignedInt(tail.getShort());
                    int fee = Byte.toUnsignedInt(tail.get());
                    if (fee != 0) {
                        return Verdict.refuse("platform_fee");
                    }
                    if (slippage > limits.maxSlippageBps()) {
                        return Verdict.refuse("slippage_too_high");
                    }
                    if (inAmount <= 0 || inAmount > limits.maxIn(inputMint)) {
                        return Verdict.refuse("swap_amount_over_cap");
                    }
                }
            }
        }
        if (jupiter != 1) {
            return Verdict.refuse("jupiter_instruction_count");
        }
        if (wrapped > 0 && !TokenAccounts.WSOL_MINT.equals(inputMint)) {
            return Verdict.refuse("system_transfer_not_allowed");
        }
        if (wrapped > inAmount) {
            return Verdict.refuse("wrap_exceeds_amount");
        }
        long priority;
        long lamportsOut;
        try {
            priority = Math.addExact(Math.multiplyExact(unitLimit, microLamports), 999_999) / 1_000_000;
            long spentSol = TokenAccounts.WSOL_MINT.equals(inputMint) ? inAmount : 0;
            lamportsOut = Math.addExact(Math.addExact(Math.addExact(spentSol, BASE_FEE_LAMPORTS), priority),
                    Math.multiplyExact(created, TOKEN_ACCOUNT_RENT_LAMPORTS));
        } catch (ArithmeticException overflow) {
            return Verdict.refuse("priority_fee_too_high");
        }
        if (priority > limits.maxPriorityFeeLamports()) {
            return Verdict.refuse("priority_fee_too_high");
        }
        return new Verdict(true, null, inputMint, outputMint, inAmount, lamportsOut, tx);
    }

    /** The allowed mint whose token account for {@code owner} is {@code account}, or null. */
    private static String mintOf(String owner, String account) {
        for (String mint : ALLOWED_MINTS) {
            if (TokenAccounts.associated(owner, mint).equals(account)) {
                return mint;
            }
        }
        return null;
    }

    private static ByteBuffer le(byte[] d, int from, int len) {
        return ByteBuffer.wrap(d, from, len).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] discriminator(String name) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(("global:" + name).getBytes(StandardCharsets.UTF_8)), 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
