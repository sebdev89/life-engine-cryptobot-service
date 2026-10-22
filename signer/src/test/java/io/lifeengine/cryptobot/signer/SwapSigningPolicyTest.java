package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.swap.TokenAccounts;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The pilot's swap signing policy over real Jupiter legacy transactions (built for the public Binance hot wallet,
 * never signed): both directions pass, and every mutant — the attacks the signer must refuse on its own, whatever
 * the validator said — is refused with its reason. Mutants are rebuilt with the signer's own LegacyTransaction, so
 * what is checked is always wire bytes.
 */
class SwapSigningPolicyTest {

    static final String OWNER = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9";
    static final String STRANGER = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    static final String SOL = TokenAccounts.WSOL_MINT;
    static final String USDC = SwapSigningPolicy.USDC_MINT;
    static final SwapSigningPolicy.Limits LIMITS = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50);

    static byte[] fixture(String name) {
        try (InputStream in = SwapSigningPolicyTest.class.getResourceAsStream("/jupiter/" + name)) {
            return Base64.getDecoder().decode(new ObjectMapper().readTree(in).path("swapTransaction").asText());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static byte[] solToUsdc() {
        return fixture("legacy-sol-usdc-binance2.json");
    }

    static String reason(byte[] wire) {
        return SwapSigningPolicy.evaluate(wire, OWNER, LIMITS).reason();
    }

    // ---- the real transactions ---------------------------------------------------------------

    @Test
    void theRealSolToUsdcSwapIsAllowedAndItsSolSpendIsCounted() {
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS);

        assertThat(v.allowed()).as(v.reason()).isTrue();
        assertThat(v.inputMint()).isEqualTo(SOL);
        assertThat(v.outputMint()).isEqualTo(USDC);
        assertThat(v.inAmount()).isEqualTo(10_000_000L);
        // the swap amount plus base fee plus the priority fee at least: what the daily cap reserves
        assertThat(v.lamportsOut()).isGreaterThan(10_000_000L + SwapSigningPolicy.BASE_FEE_LAMPORTS);
    }

    @Test
    void theRealUsdcToSolSwapIsAllowedAndSpendsOnlyFeesInSol() {
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, LIMITS);

        assertThat(v.allowed()).as(v.reason()).isTrue();
        assertThat(v.inputMint()).isEqualTo(USDC);
        assertThat(v.inAmount()).isEqualTo(1_000_000L);
        assertThat(v.lamportsOut()).isLessThan(5_000_000L);
    }

    @Test
    void ownTokenAccountsAreTheOnesJupiterWroteInTheTransaction() {
        LegacyMessageDecoder.Decoded d = LegacyMessageDecoder.decodeTransaction(solToUsdc());
        assertThat(d.accountKeys()).contains(TokenAccounts.associated(OWNER, SOL), TokenAccounts.associated(OWNER, USDC));
    }

    @Test
    void theRebuildHelperAloneChangesNothing() {
        assertThat(SwapSigningPolicy.evaluate(rebuild(solToUsdc(), UnaryOperator.identity()), OWNER, LIMITS).allowed()).isTrue();
    }

    // ---- refused on its own ------------------------------------------------------------------

    @Test
    void aSwapForAnotherFeePayerIsRefused() {
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), STRANGER, LIMITS).reason()).isEqualTo("fee_payer_mismatch");
    }

    @Test
    void aVersionedTransactionIsRefusedNoLookupTableIsEverAssumed() throws Exception {
        // a real v0 Jupiter transaction (with lookup tables) from the validator's fixtures
        Path v0 = Path.of("..", "validator", "src", "test", "resources", "jupiter", "swap-sol-usdc-route.json");
        byte[] wire = Base64.getDecoder().decode(new ObjectMapper().readTree(Files.readString(v0)).path("swapTransaction").asText());
        assertThat(SwapSigningPolicy.evaluate(wire, "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa", LIMITS).reason())
                .isEqualTo("undecodable_transaction");
    }

    @Test
    void aHiddenTransferToAThirdPartyIsRefused() {
        byte[] mutant = rebuild(solToUsdc(), ixs -> insert(ixs, transfer(OWNER, STRANGER, 1_000_000L)));
        assertThat(reason(mutant)).isEqualTo("system_instruction_not_allowed");
    }

    @Test
    void aWrapLargerThanTheSwapIsRefused() {
        byte[] mutant = rebuild(solToUsdc(), ixs -> insert(ixs, transfer(OWNER, TokenAccounts.associated(OWNER, SOL), 50_000_000L)));
        assertThat(reason(mutant)).isEqualTo("wrap_exceeds_amount");
    }

    @Test
    void someoneElsesTokenAccountAsDestinationIsRefused() {
        String mine = TokenAccounts.associated(OWNER, USDC);
        String theirs = TokenAccounts.associated(STRANGER, USDC);
        byte[] mutant = rebuild(solToUsdc(), ixs -> ixs.stream().map(ix -> ix.programId().equals(SwapSigningPolicy.JUPITER_V6)
                ? new LegacyTransaction.Instruction(ix.programId(), ix.accounts().stream()
                        .map(m -> m.pubkey().equals(mine) ? new LegacyTransaction.AccountMeta(theirs, m.signer(), m.writable()) : m).toList(), ix.data())
                : ix).toList());
        assertThat(reason(mutant)).isEqualTo("route_destination_mismatch");
    }

    @Test
    void aTokenApproveToAThirdPartyIsRefused() {
        byte[] approve = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 4).putLong(Long.MAX_VALUE).array();
        LegacyTransaction.Instruction ix = new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(TokenAccounts.associated(OWNER, USDC), false, true),
                new LegacyTransaction.AccountMeta(STRANGER, false, false),
                new LegacyTransaction.AccountMeta(OWNER, true, false)), approve);
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ix)))).isEqualTo("token_instruction_not_allowed");
    }

    @Test
    void closingTheWrappedSolToAThirdPartyIsRefused() {
        byte[] mutant = rebuild(solToUsdc(), ixs -> ixs.stream().map(ix -> ix.programId().equals(TokenAccounts.TOKEN_PROGRAM) && ix.data().length == 1
                && ix.data()[0] == 9
                ? new LegacyTransaction.Instruction(ix.programId(), List.of(ix.accounts().get(0), new LegacyTransaction.AccountMeta(STRANGER, false, true),
                        ix.accounts().get(2)), ix.data())
                : ix).toList());
        assertThat(reason(mutant)).isEqualTo("token_instruction_not_allowed");
    }

    @Test
    void aHugePriorityFeeIsRefused() {
        byte[] price = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 3).putLong(1_000_000_000L).array();
        byte[] mutant = rebuild(solToUsdc(), ixs -> {
            List<LegacyTransaction.Instruction> out = new ArrayList<>(ixs.stream()
                    .filter(ix -> !(ix.programId().equals(TokenAccounts.COMPUTE_BUDGET_PROGRAM) && ix.data()[0] == 3)).toList());
            out.add(0, new LegacyTransaction.Instruction(TokenAccounts.COMPUTE_BUDGET_PROGRAM, List.of(), price));
            return out;
        });
        assertThat(reason(mutant)).isEqualTo("priority_fee_too_high");
    }

    @Test
    void aSwapAboveThePerSwapCapIsRefused() {
        SwapSigningPolicy.Limits tight = new SwapSigningPolicy.Limits(5_000_000L, 10_000_000L, 200_000L, 50);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, tight).reason()).isEqualTo("swap_amount_over_cap");
        SwapSigningPolicy.Limits noUsdc = new SwapSigningPolicy.Limits(100_000_000L, 999_999L, 200_000L, 50);
        assertThat(SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, noUsdc).reason()).isEqualTo("swap_amount_over_cap");
    }

    @Test
    void aWiderSlippageOrAPlatformFeeIsRefused() {
        byte[] wide = rebuild(solToUsdc(), ixs -> mapJupiterData(ixs, d -> {
            ByteBuffer.wrap(d, d.length - 3, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 500);
            return d;
        }));
        assertThat(reason(wide)).isEqualTo("slippage_too_high");
        byte[] fee = rebuild(solToUsdc(), ixs -> mapJupiterData(ixs, d -> {
            d[d.length - 1] = 10;
            return d;
        }));
        assertThat(reason(fee)).isEqualTo("platform_fee");
    }

    @Test
    void anUnknownProgramOrASecondRouteIsRefused() {
        LegacyTransaction.Instruction memo = new LegacyTransaction.Instruction(SigningPolicy.MEMO_PROGRAM_ID, List.of(), "x".getBytes());
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, memo)))).isEqualTo("program_not_allowed");
        byte[] twice = rebuild(solToUsdc(), ixs -> {
            LegacyTransaction.Instruction route = ixs.stream().filter(ix -> ix.programId().equals(SwapSigningPolicy.JUPITER_V6)).findFirst().orElseThrow();
            return insert(ixs, route);
        });
        assertThat(reason(twice)).isEqualTo("jupiter_instruction_count");
    }

    @Test
    void anAuthorityThatIsNotUsIsRefused() {
        byte[] mutant = rebuild(solToUsdc(), ixs -> ixs.stream().map(ix -> {
            if (!ix.programId().equals(SwapSigningPolicy.JUPITER_V6)) {
                return ix;
            }
            List<LegacyTransaction.AccountMeta> a = new ArrayList<>(ix.accounts());
            a.set(1, new LegacyTransaction.AccountMeta(STRANGER, false, false));
            return new LegacyTransaction.Instruction(ix.programId(), a, ix.data());
        }).toList());
        assertThat(reason(mutant)).isEqualTo("route_authority_mismatch");
    }

    @Test
    void anAlreadySignedOrGarbageTransactionIsRefused() {
        byte[] signed = solToUsdc();
        signed[1] = 7;
        assertThat(reason(signed)).isEqualTo("already_signed");
        assertThat(reason(new byte[] {1, 2, 3})).isEqualTo("undecodable_transaction");
    }

    // ---- condition (2): the allow-list is the validator's, not a drifting copy ----------------

    @Test
    void theAllowedProgramsAreExactlyTheValidatorsSwapPrograms() throws Exception {
        Path validator = Path.of("..", "validator", "src", "main", "java", "io", "lifeengine", "cryptobot", "validator", "tx");
        String tokenAccounts = Files.readString(validator.resolve("TokenAccounts.java"));
        String swapRules = Files.readString(validator.resolve("SwapRules.java"));
        Set<String> theirs = new TreeSet<>();
        Matcher m = Pattern.compile("(\\w+_PROGRAM|JUPITER_V6) = \"([1-9A-HJ-NP-Za-km-z]{32,44})\"").matcher(tokenAccounts + swapRules);
        while (m.find()) {
            theirs.add(m.group(2));
        }
        assertThat(new TreeSet<>(SwapSigningPolicy.ALLOWED_PROGRAMS)).isEqualTo(theirs);
    }

    // ---- mutation helpers ----------------------------------------------------------------------

    /** Decodes the wire transaction into the signer's own builder types, applies {@code edit}, and re-serializes. */
    static byte[] rebuild(byte[] wire, UnaryOperator<List<LegacyTransaction.Instruction>> edit) {
        LegacyMessageDecoder.Decoded d = LegacyMessageDecoder.decodeTransaction(wire);
        List<String> keys = d.accountKeys();
        int n = keys.size();
        List<LegacyTransaction.Instruction> ixs = new ArrayList<>();
        for (LegacyMessageDecoder.Instruction ix : d.instructions()) {
            List<LegacyTransaction.AccountMeta> metas = new ArrayList<>();
            for (String k : ix.accounts()) {
                int i = keys.indexOf(k);
                boolean signer = i < d.numRequiredSignatures();
                boolean writable = signer ? i < d.numRequiredSignatures() - d.numReadonlySigned() : i < n - d.numReadonlyUnsigned();
                metas.add(new LegacyTransaction.AccountMeta(k, signer, writable));
            }
            ixs.add(new LegacyTransaction.Instruction(ix.programId(), metas, ix.data()));
        }
        String unsigned = new LegacyTransaction(keys.get(0), d.recentBlockhash(), edit.apply(ixs)).unsignedBase64();
        return Base64.getDecoder().decode(unsigned);
    }

    /** Inserts {@code extra} before the last instruction, where it is least visible. */
    static List<LegacyTransaction.Instruction> insert(List<LegacyTransaction.Instruction> ixs, LegacyTransaction.Instruction extra) {
        List<LegacyTransaction.Instruction> out = new ArrayList<>(ixs);
        out.add(out.size() - 1, extra);
        return out;
    }

    static LegacyTransaction.Instruction transfer(String from, String to, long lamports) {
        byte[] data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array();
        return new LegacyTransaction.Instruction(TokenAccounts.SYSTEM_PROGRAM,
                List.of(new LegacyTransaction.AccountMeta(from, true, true), new LegacyTransaction.AccountMeta(to, false, true)), data);
    }

    static List<LegacyTransaction.Instruction> mapJupiterData(List<LegacyTransaction.Instruction> ixs, UnaryOperator<byte[]> f) {
        return ixs.stream().map(ix -> ix.programId().equals(SwapSigningPolicy.JUPITER_V6)
                ? new LegacyTransaction.Instruction(ix.programId(), ix.accounts(), f.apply(ix.data().clone())) : ix).toList();
    }
}
