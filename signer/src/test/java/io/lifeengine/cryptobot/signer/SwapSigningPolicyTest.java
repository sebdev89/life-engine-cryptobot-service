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
    /** Floors at about half the market (SOL ~103 USD on 2026-10-22): 50 USDC per SOL, 0.0047 SOL per USDC. */
    /** An intent minimum that never binds, so each test isolates the check it is about (the gross floor still applies). */
    static final long ANY_MIN = 1L;
    /** floor(quoted × (10000 − slippage) / 10000) of each real fixture, decoded by hand from its bytes. */
    static final long LEAST_OUT_SOL_USDC = 1_027_446L;
    static final long LEAST_OUT_USDC_SOL = 9_635_411L;
    static final SwapSigningPolicy.Limits LIMITS = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 4_700_000L);

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
        return SwapSigningPolicy.evaluate(wire, OWNER, LIMITS, ANY_MIN).reason();
    }

    // ---- the real transactions ---------------------------------------------------------------

    @Test
    void theRealSolToUsdcSwapIsAllowedAndItsSolSpendIsCounted() {
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS, ANY_MIN);

        assertThat(v.allowed()).as(v.reason()).isTrue();
        assertThat(v.inputMint()).isEqualTo(SOL);
        assertThat(v.outputMint()).isEqualTo(USDC);
        assertThat(v.inAmount()).isEqualTo(10_000_000L);
        // the swap amount plus base fee plus the priority fee at least: what the daily cap reserves
        assertThat(v.lamportsOut()).isGreaterThan(10_000_000L + SwapSigningPolicy.BASE_FEE_LAMPORTS);
    }

    @Test
    void theRealUsdcToSolSwapIsAllowedAndSpendsOnlyFeesInSol() {
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, LIMITS, ANY_MIN);

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
        assertThat(SwapSigningPolicy.evaluate(rebuild(solToUsdc(), UnaryOperator.identity()), OWNER, LIMITS, ANY_MIN).allowed()).isTrue();
    }

    // ---- refused on its own ------------------------------------------------------------------

    @Test
    void aSwapForAnotherFeePayerIsRefused() {
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), STRANGER, LIMITS, ANY_MIN).reason()).isEqualTo("fee_payer_mismatch");
    }

    @Test
    void aVersionedTransactionIsRefusedNoLookupTableIsEverAssumed() throws Exception {
        // a real v0 Jupiter transaction (with lookup tables) from the validator's fixtures
        Path v0 = Path.of("..", "validator", "src", "test", "resources", "jupiter", "swap-sol-usdc-route.json");
        byte[] wire = Base64.getDecoder().decode(new ObjectMapper().readTree(Files.readString(v0)).path("swapTransaction").asText());
        assertThat(SwapSigningPolicy.evaluate(wire, "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa", LIMITS, ANY_MIN).reason())
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
        SwapSigningPolicy.Limits tight = new SwapSigningPolicy.Limits(5_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, tight, ANY_MIN).reason()).isEqualTo("swap_amount_over_cap");
        SwapSigningPolicy.Limits noUsdc = new SwapSigningPolicy.Limits(100_000_000L, 999_999L, 200_000L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, noUsdc, ANY_MIN).reason()).isEqualTo("swap_amount_over_cap");
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

    @Test
    void theGrossPriceFloorStopsASwapAtAnyPriceAndAMissingFloorIsRefused() {
        // the real swap returns ~103 USDC per SOL: a floor of 150 USDC per SOL is above it
        SwapSigningPolicy.Limits high = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 150_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, high, ANY_MIN).reason()).isEqualTo("price_below_floor");
        SwapSigningPolicy.Limits noFloor = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 0L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, noFloor, ANY_MIN).reason()).isEqualTo("swap_floor_not_configured");
        // each direction has its own floor
        SwapSigningPolicy.Limits noReverse = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 0L);
        assertThat(SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, noReverse, ANY_MIN).reason()).isEqualTo("swap_floor_not_configured");
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, noReverse, ANY_MIN).allowed()).isTrue();
    }

    @Test
    void aRouteThatWouldReturnAlmostNothingIsRefusedByTheFloorEvenIfEverythingElseIsFine() {
        // quoted_out rewritten to 1 000 USDC units (0.001 USDC for 0.01 SOL): every account is ours, every cap holds,
        // the intent's minimum is trivially low, and only the signer's own gross floor stands in the way
        byte[] mutant = rebuild(solToUsdc(), ixs -> mapJupiterData(ixs, d -> {
            ByteBuffer.wrap(d, d.length - 11, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1_000L);
            return d;
        }));
        assertThat(reason(mutant)).isEqualTo("price_below_floor");
        assertThat(SwapSigningPolicy.evaluate(mutant, OWNER, LIMITS, LEAST_OUT_SOL_USDC).reason()).isEqualTo("minimum_received_too_low");
    }

    // ---- review of #88: the intent minimum, exact accounting, and every branch killed -------------

    @Test
    void theIntentMinimumIsEnforcedExactlyAtTheLimit() {
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS, LEAST_OUT_SOL_USDC).allowed()).isTrue();
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS, LEAST_OUT_SOL_USDC + 1).reason()).isEqualTo("minimum_received_too_low");
        byte[] back = fixture("legacy-usdc-sol-binance2.json");
        assertThat(SwapSigningPolicy.evaluate(back, OWNER, LIMITS, LEAST_OUT_USDC_SOL).allowed()).isTrue();
        assertThat(SwapSigningPolicy.evaluate(back, OWNER, LIMITS, LEAST_OUT_USDC_SOL + 1).reason()).isEqualTo("minimum_received_too_low");
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS, 0).reason()).isEqualTo("min_out_missing");
    }

    @Test
    void theGrossFloorIsAlsoExactAtTheLimit() {
        // 0.01 SOL in at 102 744.6 USDC-units per SOL ⇒ floor = ceil(1 027 446) = the least the route returns
        SwapSigningPolicy.Limits atLimit = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 102_744_600L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, atLimit, ANY_MIN).allowed()).isTrue();
        SwapSigningPolicy.Limits above = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 102_744_601L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, above, ANY_MIN).reason()).isEqualTo("price_below_floor");
    }

    @Test
    void lamportsOutIsExactlyInputPlusBaseFeePlusRoundedUpPriorityPlusRent() {
        // SOL→USDC: 10 000 000 in + 5 000 base + ceil(112 901 × 200 002 / 10⁶) = 22 581 priority, no account created
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, LIMITS, ANY_MIN).lamportsOut()).isEqualTo(10_027_581L);
        // USDC→SOL: no SOL in; 5 000 + ceil(91 731 × 297 446 / 10⁶) = 27 286
        assertThat(SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, LIMITS, ANY_MIN).lamportsOut()).isEqualTo(32_286L);
        // creating our own USDC account adds its rent
        byte[] withAta = rebuild(solToUsdc(), ixs -> insert(ixs, createAta(OWNER, OWNER, USDC, TokenAccounts.associated(OWNER, USDC))));
        assertThat(SwapSigningPolicy.evaluate(withAta, OWNER, LIMITS, ANY_MIN).lamportsOut())
                .isEqualTo(10_027_581L + SwapSigningPolicy.TOKEN_ACCOUNT_RENT_LAMPORTS);
    }

    @Test
    void aPriorityFeeThatOverflowsOrIsNegativeIsRefused() {
        byte[] negative = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 3).putLong(-1L).array();
        assertThat(reason(rebuild(solToUsdc(), ixs -> replacePrice(ixs, negative)))).isEqualTo("priority_fee_too_high");
        byte[] huge = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 3).putLong(Long.MAX_VALUE / 2).array();
        assertThat(reason(rebuild(solToUsdc(), ixs -> replacePrice(ixs, huge)))).isEqualTo("priority_fee_too_high");
        byte[] other = new byte[] {1, 0, 0, 0, 0};
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, new LegacyTransaction.Instruction(TokenAccounts.COMPUTE_BUDGET_PROGRAM, List.of(), other)))))
                .isEqualTo("compute_budget_not_allowed");
    }

    @Test
    void aNegativeWrapOrAWrapWhenTheInputIsNotSolIsRefused() {
        byte[] negative = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(-5L).array();
        LegacyTransaction.Instruction bad = new LegacyTransaction.Instruction(TokenAccounts.SYSTEM_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(OWNER, true, true), new LegacyTransaction.AccountMeta(TokenAccounts.associated(OWNER, SOL), false, true)),
                negative);
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, bad)))).isEqualTo("system_instruction_not_allowed");
        byte[] wrapOnUsdc = rebuild(fixture("legacy-usdc-sol-binance2.json"), ixs -> insert(ixs, transfer(OWNER, TokenAccounts.associated(OWNER, SOL), 1_000L)));
        assertThat(reason(wrapOnUsdc)).isEqualTo("system_transfer_not_allowed");
    }

    @Test
    void moreThanOneSignerIsRefused() {
        LegacyTransaction.Instruction cosigned = new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(TokenAccounts.associated(OWNER, SOL), false, true),
                new LegacyTransaction.AccountMeta(STRANGER, true, false)), new byte[] {17});
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, cosigned)))).isEqualTo("multiple_signers");
    }

    @Test
    void trailingBytesAfterTheMessageAreRefused() {
        byte[] wire = solToUsdc();
        assertThat(reason(java.util.Arrays.copyOf(wire, wire.length + 1))).isEqualTo("undecodable_transaction");
    }

    @Test
    void ataCreationIsOnlyOursForAnAllowedMintAndItsRealAddress() {
        String ours = TokenAccounts.associated(OWNER, USDC);
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, createAta(STRANGER, OWNER, USDC, ours))))).isEqualTo("ata_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, createAta(OWNER, STRANGER, USDC, TokenAccounts.associated(STRANGER, USDC))))))
                .isEqualTo("ata_instruction_not_allowed");
        String bonk = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, createAta(OWNER, OWNER, bonk, TokenAccounts.associated(OWNER, bonk))))))
                .isEqualTo("ata_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, createAta(OWNER, OWNER, USDC, TokenAccounts.associated(OWNER, SOL))))))
                .isEqualTo("ata_instruction_not_allowed");
    }

    @Test
    void syncOrCloseOfAnAccountThatIsNotOurWrappedSolIsRefused() {
        String usdcAccount = TokenAccounts.associated(OWNER, USDC);
        LegacyTransaction.Instruction sync = new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM,
                List.of(new LegacyTransaction.AccountMeta(usdcAccount, false, true)), new byte[] {17});
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, sync)))).isEqualTo("token_instruction_not_allowed");
        LegacyTransaction.Instruction close = new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(usdcAccount, false, true), new LegacyTransaction.AccountMeta(OWNER, true, true),
                new LegacyTransaction.AccountMeta(OWNER, true, true)), new byte[] {9});
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, close)))).isEqualTo("token_instruction_not_allowed");
    }

    @Test
    void theRouteMustUseTheTokenProgramOurSourceAndNoForeignOverride() {
        assertThat(reason(rebuild(solToUsdc(), ixs -> mapJupiterAccount(ixs, 0, STRANGER)))).isEqualTo("route_authority_mismatch");
        assertThat(reason(rebuild(solToUsdc(), ixs -> mapJupiterAccount(ixs, 2, TokenAccounts.associated(STRANGER, SOL))))).isEqualTo("route_source_mismatch");
        assertThat(reason(rebuild(solToUsdc(), ixs -> mapJupiterAccount(ixs, 4, STRANGER)))).isEqualTo("route_destination_mismatch");
    }

    @Test
    void anythingThatThrowsIsARefusalNeverAnError() {
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), "not base58 0OIl", LIMITS, ANY_MIN).allowed()).isFalse();
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), null, LIMITS, ANY_MIN).allowed()).isFalse();
        // one signature slot and a header claiming one signer, but no account keys at all
        byte[] noKeys = new byte[1 + 64 + 3 + 1 + 32 + 1];
        noKeys[0] = 1;
        noKeys[65] = 1;
        assertThat(reason(noKeys)).isEqualTo("undecodable_transaction");
        assertThat(reason(null)).isEqualTo("undecodable_transaction");
    }

    static LegacyTransaction.Instruction createAta(String payer, String wallet, String mint, String ata) {
        return new LegacyTransaction.Instruction(TokenAccounts.ATA_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(payer, payer.equals(OWNER), true), new LegacyTransaction.AccountMeta(ata, false, true),
                new LegacyTransaction.AccountMeta(wallet, false, false), new LegacyTransaction.AccountMeta(mint, false, false),
                new LegacyTransaction.AccountMeta(TokenAccounts.SYSTEM_PROGRAM, false, false),
                new LegacyTransaction.AccountMeta(TokenAccounts.TOKEN_PROGRAM, false, false)), new byte[] {1});
    }

    static List<LegacyTransaction.Instruction> replacePrice(List<LegacyTransaction.Instruction> ixs, byte[] data) {
        return ixs.stream().map(ix -> ix.programId().equals(TokenAccounts.COMPUTE_BUDGET_PROGRAM) && ix.data()[0] == 3
                ? new LegacyTransaction.Instruction(ix.programId(), List.of(), data) : ix).toList();
    }

    static List<LegacyTransaction.Instruction> mapJupiterAccount(List<LegacyTransaction.Instruction> ixs, int index, String key) {
        return ixs.stream().map(ix -> {
            if (!ix.programId().equals(SwapSigningPolicy.JUPITER_V6)) {
                return ix;
            }
            List<LegacyTransaction.AccountMeta> a = new ArrayList<>(ix.accounts());
            a.set(index, new LegacyTransaction.AccountMeta(key, false, a.get(index).writable()));
            return new LegacyTransaction.Instruction(ix.programId(), a, ix.data());
        }).toList();
    }

    // ---- PIT survivors: every sub-condition violated on its own, with its exact reason --------------

    static final String TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

    @Test
    void signatureCountAndRequiredSignersMustBothBeOne() {
        byte[] wire = solToUsdc();
        // two signature slots, header still asks for one
        assertThat(reason(withSigSlots(wire, 2))).isEqualTo("multiple_signers");
        // one signature slot, header asks for two
        LegacyTransaction.Instruction cosigned = new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(TokenAccounts.associated(OWNER, SOL), false, true),
                new LegacyTransaction.AccountMeta(STRANGER, true, false)), new byte[] {17});
        assertThat(reason(withSigSlots(rebuild(wire, ixs -> insert(ixs, cosigned)), 1))).isEqualTo("multiple_signers");
        // the decoder itself refuses 0 or more than 8 slots
        assertThat(reason(withSigSlots(wire, 0))).isEqualTo("undecodable_transaction");
        assertThat(reason(withSigSlots(wire, 9))).isEqualTo("undecodable_transaction");
    }

    @Test
    void computeBudgetOnlyLimitAndPriceWithExactShapes() {
        LegacyTransaction.Instruction withAccount = new LegacyTransaction.Instruction(TokenAccounts.COMPUTE_BUDGET_PROGRAM,
                List.of(new LegacyTransaction.AccountMeta(STRANGER, false, false)), new byte[] {2, 0, 0, 1, 0});
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, withAccount)))).isEqualTo("compute_budget_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, cb(new byte[] {2, 0, 0, 1, 0, 0, 0, 0, 0}))))).isEqualTo("compute_budget_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, cb(new byte[] {3, 0, 0, 1, 0}))))).isEqualTo("compute_budget_not_allowed");
        // a zero unit price is allowed and costs nothing
        byte[] zero = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 3).putLong(0L).array();
        SwapSigningPolicy.Verdict free = SwapSigningPolicy.evaluate(rebuild(solToUsdc(), ixs -> replacePrice(ixs, zero)), OWNER, LIMITS, ANY_MIN);
        assertThat(free.allowed()).isTrue();
        assertThat(free.lamportsOut()).isEqualTo(10_005_000L);
        // the priority fee may equal its cap, not exceed it (22 581 lamports in the fixture)
        SwapSigningPolicy.Limits atCap = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 22_581L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, atCap, ANY_MIN).allowed()).isTrue();
        SwapSigningPolicy.Limits below = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 22_580L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, below, ANY_MIN).reason()).isEqualTo("priority_fee_too_high");
    }

    @Test
    void ataCreationShapes() {
        String ours = TokenAccounts.associated(OWNER, USDC);
        // the legacy "create" (no data) and create (0) are as good as create-idempotent (1)
        assertThat(SwapSigningPolicy.evaluate(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[0], OWNER, OWNER, USDC, ours,
                TokenAccounts.SYSTEM_PROGRAM, TokenAccounts.TOKEN_PROGRAM))), OWNER, LIMITS, ANY_MIN).allowed()).isTrue();
        assertThat(SwapSigningPolicy.evaluate(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {0}, OWNER, OWNER, USDC, ours,
                TokenAccounts.SYSTEM_PROGRAM, TokenAccounts.TOKEN_PROGRAM))), OWNER, LIMITS, ANY_MIN).allowed()).isTrue();
        // any other instruction of the program, other data, other system/token program, other account count
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {2}, OWNER, OWNER, USDC, ours, TokenAccounts.SYSTEM_PROGRAM,
                TokenAccounts.TOKEN_PROGRAM))))).isEqualTo("ata_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {1, 0}, OWNER, OWNER, USDC, ours, TokenAccounts.SYSTEM_PROGRAM,
                TokenAccounts.TOKEN_PROGRAM))))).isEqualTo("ata_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {1}, OWNER, OWNER, USDC, ours, STRANGER,
                TokenAccounts.TOKEN_PROGRAM))))).isEqualTo("ata_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {1}, OWNER, OWNER, USDC, ours, TokenAccounts.SYSTEM_PROGRAM,
                TOKEN_2022))))).isEqualTo("ata_instruction_not_allowed");
        LegacyTransaction.Instruction five = new LegacyTransaction.Instruction(TokenAccounts.ATA_PROGRAM, createAta(OWNER, OWNER, USDC, ours)
                .accounts().subList(0, 5), new byte[] {1});
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, five)))).isEqualTo("ata_instruction_not_allowed");
    }

    @Test
    void systemTransferShapes() {
        String wsol = TokenAccounts.associated(OWNER, SOL);
        byte[] thirteen = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1L).array();
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, sys(thirteen, OWNER, wsol))))).isEqualTo("system_instruction_not_allowed");
        byte[] createAccount = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putLong(1L).array();
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, sys(createAccount, OWNER, wsol))))).isEqualTo("system_instruction_not_allowed");
        LegacyTransaction.Instruction three = new LegacyTransaction.Instruction(TokenAccounts.SYSTEM_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(OWNER, true, true), new LegacyTransaction.AccountMeta(wsol, false, true),
                new LegacyTransaction.AccountMeta(STRANGER, false, true)), transfer(OWNER, wsol, 1L).data());
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, three)))).isEqualTo("system_instruction_not_allowed");
        // from an account that is not ours (and not a signer)
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, sys(transfer(OWNER, wsol, 1L).data(), TokenAccounts.associated(OWNER, USDC), wsol)))))
                .isEqualTo("system_instruction_not_allowed");
        // a zero-lamport wrap is harmless
        assertThat(SwapSigningPolicy.evaluate(rebuild(solToUsdc(), ixs -> insert(ixs, transfer(OWNER, wsol, 0L))), OWNER, LIMITS, ANY_MIN).allowed()).isTrue();
    }

    @Test
    void syncAndCloseShapes() {
        String wsol = TokenAccounts.associated(OWNER, SOL);
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {17, 0}, wsol))))).isEqualTo("token_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {17}, wsol, OWNER))))).isEqualTo("token_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {9, 0}, wsol, OWNER, OWNER))))).isEqualTo("token_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {9}, wsol, OWNER, OWNER, STRANGER))))).isEqualTo("token_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {9}, wsol, OWNER, STRANGER))))).isEqualTo("token_instruction_not_allowed");
    }

    @Test
    void theSharedAccountsRouteIsReadFromItsOwnAccountPositions() {
        byte[] shared = fixture("legacy-sol-usdc-shared-binance2.json");
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(shared, OWNER, LIMITS, 1_026_997L);
        assertThat(v.allowed()).as(v.reason()).isTrue();
        assertThat(SwapSigningPolicy.evaluate(shared, OWNER, LIMITS, 1_026_998L).reason()).isEqualTo("minimum_received_too_low");
        assertThat(reason(rebuild(shared, ixs -> mapJupiterAccount(ixs, 2, STRANGER)))).isEqualTo("route_authority_mismatch");
        assertThat(reason(rebuild(shared, ixs -> mapJupiterAccount(ixs, 3, TokenAccounts.associated(STRANGER, SOL))))).isEqualTo("route_source_mismatch");
        assertThat(reason(rebuild(shared, ixs -> mapJupiterAccount(ixs, 6, TokenAccounts.associated(STRANGER, USDC))))).isEqualTo("route_destination_mismatch");
        assertThat(reason(rebuild(shared, ixs -> mapJupiterAccount(ixs, 9, STRANGER)))).isEqualTo("platform_fee");
        assertThat(reason(rebuild(shared, ixs -> truncateJupiterAccounts(ixs, 12)))).isEqualTo("jupiter_instruction_not_allowed");
    }

    @Test
    void routeShapesAndAmountsAtTheirBoundaries() {
        byte[] wire = solToUsdc();
        assertThat(reason(rebuild(wire, ixs -> truncateJupiterAccounts(ixs, 8)))).isEqualTo("jupiter_instruction_not_allowed");
        assertThat(reason(rebuild(wire, ixs -> mapJupiterData(ixs, d -> java.util.Arrays.copyOf(d, 26))))).isEqualTo("jupiter_instruction_not_allowed");
        // the destination override may be our own destination account itself
        String dest = TokenAccounts.associated(OWNER, USDC);
        assertThat(SwapSigningPolicy.evaluate(rebuild(wire, ixs -> mapJupiterAccount(ixs, 4, dest)), OWNER, LIMITS, ANY_MIN).allowed()).isTrue();
        // source and destination for the same mint
        assertThat(reason(rebuild(wire, ixs -> mapJupiterAccount(ixs, 3, TokenAccounts.associated(OWNER, SOL))))).isEqualTo("route_destination_mismatch");
        // a platform fee account
        assertThat(reason(rebuild(wire, ixs -> mapJupiterAccount(ixs, 6, STRANGER)))).isEqualTo("platform_fee");
        // in_amount exactly at the cap passes; zero is refused
        SwapSigningPolicy.Limits atCap = new SwapSigningPolicy.Limits(10_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(wire, OWNER, atCap, ANY_MIN).allowed()).isTrue();
        assertThat(reason(rebuild(wire, ixs -> mapJupiterData(ixs, d -> {
            ByteBuffer.wrap(d, d.length - 19, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L);
            return d;
        })))).isEqualTo("swap_amount_over_cap");
        // the USDC cap applies to a USDC input, the SOL cap does not
        SwapSigningPolicy.Limits solCapOnly = new SwapSigningPolicy.Limits(1L, 10_000_000L, 200_000L, 50, 50_000_000L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(fixture("legacy-usdc-sol-binance2.json"), OWNER, solCapOnly, ANY_MIN).allowed()).isTrue();
    }

    @Test
    void theReverseFloorIsExactAndAnOverflowingFloorRefuses() {
        byte[] back = fixture("legacy-usdc-sol-binance2.json");
        // 1 USDC in: floor = ceil(10⁶ × rate / 10⁶) = rate; the route returns at least 9 635 411 lamports
        SwapSigningPolicy.Limits atLimit = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 9_635_411L);
        assertThat(SwapSigningPolicy.evaluate(back, OWNER, atLimit, ANY_MIN).allowed()).isTrue();
        SwapSigningPolicy.Limits above = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 50_000_000L, 9_635_412L);
        assertThat(SwapSigningPolicy.evaluate(back, OWNER, above, ANY_MIN).reason()).isEqualTo("price_below_floor");
        // a SOL→USDC floor never applies to USDC→SOL, and vice versa
        SwapSigningPolicy.Limits hugeSolFloor = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, 999_999_999_999L, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(back, OWNER, hugeSolFloor, ANY_MIN).allowed()).isTrue();
        // in × rate beyond a long saturates instead of wrapping
        SwapSigningPolicy.Limits overflow = new SwapSigningPolicy.Limits(100_000_000L, 10_000_000L, 200_000L, 50, Long.MAX_VALUE, 4_700_000L);
        assertThat(SwapSigningPolicy.evaluate(solToUsdc(), OWNER, overflow, ANY_MIN).reason()).isEqualTo("price_below_floor");
    }

    @Test
    void aLongInstructionIsDecodedPastAMultiByteLength() {
        // 200 bytes of data: its length is a two-byte compact-u16; the reason proves everything was read correctly
        LegacyTransaction.Instruction memo = new LegacyTransaction.Instruction(SigningPolicy.MEMO_PROGRAM_ID, List.of(), new byte[200]);
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, memo)))).isEqualTo("program_not_allowed");
    }

    @Test
    void theLastBranchesEachOnItsOwn() {
        String ours = TokenAccounts.associated(OWNER, USDC);
        String wsol = TokenAccounts.associated(OWNER, SOL);
        // ATA for our own account but naming someone else as wallet: only the wallet check stops it
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, ata(new byte[] {1}, OWNER, STRANGER, USDC, ours, TokenAccounts.SYSTEM_PROGRAM,
                TokenAccounts.TOKEN_PROGRAM))))).isEqualTo("ata_instruction_not_allowed");
        // the right shape and accounts but another token opcode
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {18}, wsol))))).isEqualTo("token_instruction_not_allowed");
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(ixs, tok(new byte[] {10}, wsol, OWNER, OWNER))))).isEqualTo("token_instruction_not_allowed");
        // two wraps whose sum overflows a long
        long half = Long.MAX_VALUE / 2 + 1;
        assertThat(reason(rebuild(solToUsdc(), ixs -> insert(insert(ixs, transfer(OWNER, wsol, half)), transfer(OWNER, wsol, half)))))
                .isEqualTo("wrap_exceeds_amount");
        // eight signature slots is still decodable — and refused for having more than one signer
        assertThat(reason(withSigSlots(solToUsdc(), 8))).isEqualTo("multiple_signers");
    }

    @Test
    void anOverlongCompactLengthIsRefusedNotReadOn() {
        // the key count (one byte here, after 1 + 64 + 3 header bytes) rewritten as a 4-byte compact-u16 of the same value:
        // a decoder that kept reading would see the same transaction; this one stops at 3 bytes and refuses
        byte[] wire = solToUsdc();
        int at = 1 + 64 + 3;
        int count = wire[at] & 0xff;
        assertThat(count).isLessThan(128);
        byte[] out = new byte[wire.length + 3];
        System.arraycopy(wire, 0, out, 0, at);
        out[at] = (byte) (count | 0x80);
        out[at + 1] = (byte) 0x80;
        out[at + 2] = (byte) 0x80;
        out[at + 3] = 0;
        System.arraycopy(wire, at + 1, out, at + 4, wire.length - at - 1);
        assertThat(reason(out)).isEqualTo("undecodable_transaction");
    }

    static byte[] withSigSlots(byte[] wire, int slots) {
        int current = wire[0];
        byte[] message = java.util.Arrays.copyOfRange(wire, 1 + 64 * current, wire.length);
        byte[] out = new byte[1 + 64 * slots + message.length];
        out[0] = (byte) slots;
        System.arraycopy(message, 0, out, 1 + 64 * slots, message.length);
        return out;
    }

    static LegacyTransaction.Instruction cb(byte[] data) {
        return new LegacyTransaction.Instruction(TokenAccounts.COMPUTE_BUDGET_PROGRAM, List.of(), data);
    }

    static LegacyTransaction.Instruction ata(byte[] data, String payer, String wallet, String mint, String account, String system, String token) {
        return new LegacyTransaction.Instruction(TokenAccounts.ATA_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(payer, payer.equals(OWNER), true), new LegacyTransaction.AccountMeta(account, false, true),
                new LegacyTransaction.AccountMeta(wallet, false, false), new LegacyTransaction.AccountMeta(mint, false, false),
                new LegacyTransaction.AccountMeta(system, false, false), new LegacyTransaction.AccountMeta(token, false, false)), data);
    }

    static LegacyTransaction.Instruction sys(byte[] data, String from, String to) {
        return new LegacyTransaction.Instruction(TokenAccounts.SYSTEM_PROGRAM, List.of(
                new LegacyTransaction.AccountMeta(from, from.equals(OWNER), true), new LegacyTransaction.AccountMeta(to, false, true)), data);
    }

    static LegacyTransaction.Instruction tok(byte[] data, String... accounts) {
        List<LegacyTransaction.AccountMeta> metas = new ArrayList<>();
        for (String a : accounts) {
            metas.add(new LegacyTransaction.AccountMeta(a, a.equals(OWNER), true));
        }
        return new LegacyTransaction.Instruction(TokenAccounts.TOKEN_PROGRAM, metas, data);
    }

    static List<LegacyTransaction.Instruction> truncateJupiterAccounts(List<LegacyTransaction.Instruction> ixs, int keep) {
        return ixs.stream().map(ix -> ix.programId().equals(SwapSigningPolicy.JUPITER_V6)
                ? new LegacyTransaction.Instruction(ix.programId(), ix.accounts().subList(0, keep), ix.data()) : ix).toList();
    }

    // ---- condition (2): the allow-list is the validator's, not a drifting copy ----------------

    @Test
    void theAllowedProgramsAreExactlyTheValidatorsSwapPrograms() throws Exception {
        Path validator = Path.of("..", "validator", "src", "main", "java", "io", "lifeengine", "cryptobot", "validator", "tx");
        assertThat(validator.resolve("SwapRules.java")).as("validator source not found from %s: run from signer/ inside the repo",
                Path.of("").toAbsolutePath()).exists();
        String tokenAccounts = Files.readString(validator.resolve("TokenAccounts.java"));
        String swapRules = Files.readString(validator.resolve("SwapRules.java"));
        Set<String> theirs = new TreeSet<>();
        Matcher m = Pattern.compile("(\\w+_PROGRAM|JUPITER_V6) = \"([1-9A-HJ-NP-Za-km-z]{32,44})\"").matcher(tokenAccounts + swapRules);
        while (m.find()) {
            theirs.add(m.group(2));
        }
        assertThat(theirs).as("no program ids parsed from the validator source").hasSize(5);
        assertThat(new TreeSet<>(SwapSigningPolicy.ALLOWED_PROGRAMS)).isEqualTo(theirs);
    }

    // ---- mutation helpers ----------------------------------------------------------------------

    /** Decodes the wire transaction into the signer's own builder types, applies {@code edit}, and re-serializes. */
    static byte[] rebuild(byte[] wire, UnaryOperator<List<LegacyTransaction.Instruction>> edit) {
        return rebuild(wire, edit, null, null);
    }

    /** Same, with another fee payer and/or recent blockhash ({@code null} keeps the original). */
    static byte[] rebuild(byte[] wire, UnaryOperator<List<LegacyTransaction.Instruction>> edit, String feePayer, String blockhash) {
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
        String unsigned = new LegacyTransaction(feePayer == null ? keys.get(0) : feePayer, blockhash == null ? d.recentBlockhash() : blockhash,
                edit.apply(ixs)).unsignedBase64();
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
