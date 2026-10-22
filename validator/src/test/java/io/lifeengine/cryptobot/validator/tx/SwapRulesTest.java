package io.lifeengine.cryptobot.validator.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.validator.crypto.Base58;
import io.lifeengine.cryptobot.validator.tx.CompiledTransaction.Instruction;
import io.lifeengine.cryptobot.validator.tx.SwapRules.SwapIntent;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * KAN-600 against real Jupiter transactions (saved under {@code /jupiter}, built for a fresh address that has no
 * funds and whose key was never kept): the three real ones pass, and each mutant — the attacks the validator exists
 * for — is refused with its own code.
 */
class SwapRulesTest {

    static final String OWNER = "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa";
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String SOL = TokenAccounts.WSOL_MINT;
    static final String STRANGER = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    static final long FEE_CAP = 200_000L;

    static JsonNode json(String name) {
        try (InputStream in = SwapRulesTest.class.getResourceAsStream("/jupiter/" + name)) {
            return new ObjectMapper().readTree(in);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static byte[] txBytes(String name) {
        return Base64.getDecoder().decode(json(name).path("swapTransaction").asText());
    }

    static CompiledTransaction tx(String name) {
        return CompiledTransaction.decode(txBytes(name));
    }

    /**
     * The intent the saved quote describes. Its minimum is floor(outAmount × (10000 − slippage) / 10000), the worst
     * rounding the chain could apply, not Jupiter's {@code otherAmountThreshold} (which rounds up; see below).
     */
    static SwapIntent intent(String quote) {
        JsonNode q = json(quote);
        int slippage = q.path("slippageBps").asInt();
        long min = q.path("outAmount").asLong() * (10_000 - slippage) / 10_000;
        return new SwapIntent(OWNER, q.path("inputMint").asText(), q.path("outputMint").asText(), q.path("inAmount").asLong(),
                min, slippage, FEE_CAP);
    }

    @Test
    void jupitersThresholdRoundsUpByAtMostOneUnitOverTheFloorTheValidatorUses() {
        JsonNode q = json("quote-sol-usdc.json");
        assertThat(q.path("otherAmountThreshold").asLong() - solToUsdc().minOutAmount()).isBetween(0L, 1L);
    }

    static SwapIntent solToUsdc() {
        return intent("quote-sol-usdc.json");
    }

    static List<String> codes(SwapRules.Result r) {
        return r.violations().stream().map(SwapRules.Violation::code).toList();
    }

    // ---- decoding ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"swap-sol-usdc-route.json", "swap-usdc-sol-route.json", "swap-sol-usdc-shared.json"})
    void decodingAndEncodingGiveBackTheSameBytes(String name) {
        byte[] raw = txBytes(name);
        CompiledTransaction t = CompiledTransaction.decode(raw);

        assertThat(t.versioned()).isTrue();
        assertThat(t.feePayer()).isEqualTo(OWNER);
        assertThat(t.signaturesEmpty()).isTrue();
        assertThat(t.encode()).isEqualTo(raw);
    }

    @Test
    void theOwnersTokenAccountsAreTheOnesJupiterDerived() {
        // both keys are written in the real transaction: an independent derivation must land on them
        assertThat(TokenAccounts.associated(OWNER, SOL)).isEqualTo("Cdk7KCntD8nNXo4YvgoeESJk9xecL6iAp4Q6qCmw1vnK");
        assertThat(TokenAccounts.associated(OWNER, USDC)).isEqualTo("GkkrbbHi2wz9PLTkhrqB1nbMZGYZhu7tpS7MrAB5MY1T");
        assertThat(tx("swap-sol-usdc-route.json").staticKeys()).contains(TokenAccounts.associated(OWNER, SOL), TokenAccounts.associated(OWNER, USDC));
    }

    @Test
    void truncatedOrPaddedBytesAreMalformed() {
        byte[] raw = txBytes("swap-sol-usdc-route.json");
        assertThatThrownBy(() -> CompiledTransaction.decode(Arrays.copyOf(raw, raw.length - 3))).isInstanceOf(CompiledTransaction.MalformedTransaction.class);
        assertThatThrownBy(() -> CompiledTransaction.decode(Arrays.copyOf(raw, raw.length + 1))).isInstanceOf(CompiledTransaction.MalformedTransaction.class);
    }

    // ---- the real transactions pass --------------------------------------------------------------

    @Test
    void theRealSolToUsdcRoutePasses() {
        SwapRules.Result r = SwapRules.check(tx("swap-sol-usdc-route.json"), solToUsdc());

        assertThat(r.violations()).isEmpty();
        assertThat(r.route().variant()).isEqualTo("route");
        assertThat(r.route().inAmount()).isEqualTo(10_000_000L);
        assertThat(r.route().quotedOutAmount()).isEqualTo(1_048_065L);
        assertThat(r.route().minimumReceived()).isEqualTo(1_042_824L); // floor(1048065 × 9950 / 10000)
        assertThat(r.priorityFeeLamports()).isEqualTo(100_000L);
    }

    @Test
    void theRealSharedAccountsRoutePasses() {
        SwapRules.Result r = SwapRules.check(tx("swap-sol-usdc-shared.json"), solToUsdc());
        assertThat(r.violations()).isEmpty();
        assertThat(r.route().variant()).isEqualTo("shared_accounts_route");
    }

    @Test
    void theRealUsdcToSolRouteWithUnwrapPasses() {
        SwapRules.Result r = SwapRules.check(tx("swap-usdc-sol-route.json"), intent("quote-usdc-sol.json"));
        assertThat(r.violations()).isEmpty();
    }

    // ---- mutants: each attack is refused ---------------------------------------------------------

    @Test
    void aHiddenTransferToAThirdPartyIsRefused() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), STRANGER);
        int stranger = t.staticKeys().indexOf(STRANGER);
        int system = t.staticKeys().indexOf(TokenAccounts.SYSTEM_PROGRAM);
        CompiledTransaction mutant = withInstruction(t, new Instruction(system, new int[] {0, stranger}, systemTransfer(1_000_000L)));

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("transfer_to_mismatch");
    }

    @Test
    void aWrapLargerThanTheSwapIsRefused() {
        CompiledTransaction t = tx("swap-sol-usdc-route.json");
        int system = t.staticKeys().indexOf(TokenAccounts.SYSTEM_PROGRAM);
        CompiledTransaction mutant = mapInstructions(t, ix -> ix.programIndex() == system
                ? new Instruction(system, ix.accounts(), systemTransfer(50_000_000L)) : ix);

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("wrap_exceeds_amount");
    }

    @Test
    void someoneElsesTokenAccountAsDestinationIsRefused() {
        CompiledTransaction t = tx("swap-sol-usdc-route.json");
        String theirs = TokenAccounts.associated(STRANGER, USDC);
        CompiledTransaction mutant = replaceStaticKey(t, TokenAccounts.associated(OWNER, USDC), theirs);

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("route_destination_mismatch");
    }

    @Test
    void aLowMinimumReceivedIsRefused() {
        // same route, but Jupiter would accept 10% less than the intent's minimum
        CompiledTransaction mutant = mapJupiter(tx("swap-sol-usdc-route.json"), d -> {
            ByteBuffer.wrap(d, d.length - 11, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(943_000L);
            return d;
        });

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("minimum_received_too_low");
    }

    @Test
    void aWiderSlippageThanTheIntentIsRefused() {
        CompiledTransaction mutant = mapJupiter(tx("swap-sol-usdc-route.json"), d -> {
            ByteBuffer.wrap(d, d.length - 3, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 500);
            return d;
        });

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("slippage_too_high", "minimum_received_too_low");
    }

    @Test
    void anotherAmountThanTheIntentIsRefused() {
        SwapIntent half = new SwapIntent(OWNER, SOL, USDC, 5_000_000L, 500_000L, 50, FEE_CAP);
        assertThat(codes(SwapRules.check(tx("swap-sol-usdc-route.json"), half))).contains("in_amount_mismatch", "wrap_exceeds_amount");
    }

    @Test
    void aTokenApproveToAThirdPartyIsRefused() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), STRANGER);
        int stranger = t.staticKeys().indexOf(STRANGER);
        int token = t.staticKeys().indexOf(TokenAccounts.TOKEN_PROGRAM);
        int source = t.staticKeys().indexOf(TokenAccounts.associated(OWNER, SOL));
        byte[] approve = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 4).putLong(Long.MAX_VALUE).array();
        CompiledTransaction mutant = withInstruction(t, new Instruction(token, new int[] {source, stranger, 0}, approve));

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("token_instruction_not_allowed");
    }

    @Test
    void closingTheWrappedSolToAThirdPartyIsRefused() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), STRANGER);
        int stranger = t.staticKeys().indexOf(STRANGER);
        int token = t.staticKeys().indexOf(TokenAccounts.TOKEN_PROGRAM);
        CompiledTransaction mutant = mapInstructions(t, ix -> ix.programIndex() == token && ix.data().length == 1 && ix.data()[0] == 9
                ? new Instruction(token, new int[] {ix.accounts()[0], stranger, 0}, ix.data()) : ix);

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("close_destination_mismatch");
    }

    @Test
    void aHugePriorityFeeIsRefused() {
        CompiledTransaction t = tx("swap-sol-usdc-route.json");
        int budget = t.staticKeys().indexOf(TokenAccounts.COMPUTE_BUDGET_PROGRAM);
        byte[] price = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte) 3).putLong(1_000_000_000L).array();
        CompiledTransaction mutant = mapInstructions(t, ix -> ix.programIndex() == budget && ix.data()[0] == 3 ? new Instruction(budget, new int[0], price) : ix);

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("priority_fee_too_high");
    }

    @Test
    void aPlatformFeeAccountIsRefused() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), STRANGER);
        int stranger = t.staticKeys().indexOf(STRANGER);
        CompiledTransaction mutant = mapJupiterAccounts(t, a -> {
            a[6] = stranger;
            return a;
        });

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("route_platform_fee_account_mismatch");
    }

    @Test
    void anAuthorityLoadedFromALookupTableIsRefusedNotAssumed() {
        CompiledTransaction t = tx("swap-sol-usdc-route.json");
        int fromTable = t.staticKeys().size(); // first lookup-table account
        CompiledTransaction mutant = mapJupiterAccounts(t, a -> {
            a[1] = fromTable;
            return a;
        });

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("account_from_lookup_table");
    }

    @Test
    void anUnknownProgramIsRefused() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr");
        int memo = t.staticKeys().indexOf("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr");
        CompiledTransaction mutant = withInstruction(t, new Instruction(memo, new int[0], "hi".getBytes()));

        assertThat(codes(SwapRules.check(mutant, solToUsdc()))).contains("program_not_allowed");
    }

    @Test
    void aSignedOrForeignPaidTransactionIsRefused() {
        CompiledTransaction t = tx("swap-sol-usdc-route.json");
        byte[] sig = new byte[64];
        sig[0] = 1;
        CompiledTransaction signed = new CompiledTransaction(List.of(sig), t.versioned(), t.requiredSignatures(), t.readonlySigned(),
                t.readonlyUnsigned(), t.staticKeys(), t.recentBlockhash(), t.instructions(), t.lookupTables());
        assertThat(codes(SwapRules.check(signed, solToUsdc()))).contains("already_signed");

        SwapIntent forStranger = new SwapIntent(STRANGER, SOL, USDC, 10_000_000L, 1_042_824L, 50, FEE_CAP);
        assertThat(codes(SwapRules.check(t, forStranger))).contains("fee_payer_mismatch");
    }

    // ---- mutation helpers: they edit the decoded message and re-encode, like an attacker would edit bytes ----

    static byte[] systemTransfer(long lamports) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array();
    }

    /** Adds a writable non-signer static key, shifting every index at or after it (lookup-table indexes included). */
    static CompiledTransaction withStaticKey(CompiledTransaction t, String key) {
        int pos = t.staticKeys().size() - t.readonlyUnsigned();
        List<String> keys = new ArrayList<>(t.staticKeys());
        keys.add(pos, key);
        UnaryOperator<Integer> shift = i -> i >= pos ? i + 1 : i;
        List<Instruction> ixs = t.instructions().stream()
                .map(ix -> new Instruction(shift.apply(ix.programIndex()), Arrays.stream(ix.accounts()).map(shift::apply).toArray(), ix.data()))
                .toList();
        return round(new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                keys, t.recentBlockhash(), ixs, t.lookupTables()));
    }

    static CompiledTransaction replaceStaticKey(CompiledTransaction t, String from, String to) {
        List<String> keys = t.staticKeys().stream().map(k -> k.equals(from) ? to : k).toList();
        assertThat(keys).contains(to);
        return round(new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                keys, t.recentBlockhash(), t.instructions(), t.lookupTables()));
    }

    static CompiledTransaction withInstruction(CompiledTransaction t, Instruction extra) {
        List<Instruction> ixs = new ArrayList<>(t.instructions());
        ixs.add(ixs.size() - 1, extra); // before the final close, where it is least visible
        return round(new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                t.staticKeys(), t.recentBlockhash(), ixs, t.lookupTables()));
    }

    static CompiledTransaction mapInstructions(CompiledTransaction t, UnaryOperator<Instruction> f) {
        return round(new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                t.staticKeys(), t.recentBlockhash(), t.instructions().stream().map(f).toList(), t.lookupTables()));
    }

    static CompiledTransaction mapJupiter(CompiledTransaction t, UnaryOperator<byte[]> f) {
        int jup = t.staticKeys().indexOf(SwapRules.JUPITER_V6);
        return mapInstructions(t, ix -> ix.programIndex() == jup ? new Instruction(jup, ix.accounts(), f.apply(ix.data())) : ix);
    }

    static CompiledTransaction mapJupiterAccounts(CompiledTransaction t, UnaryOperator<int[]> f) {
        int jup = t.staticKeys().indexOf(SwapRules.JUPITER_V6);
        return mapInstructions(t, ix -> ix.programIndex() == jup ? new Instruction(jup, f.apply(ix.accounts()), ix.data()) : ix);
    }

    /** Every mutant goes through the wire format: what the validator checks is always decoded bytes. */
    static CompiledTransaction round(CompiledTransaction t) {
        return CompiledTransaction.decode(t.encode());
    }

    @Test
    void theMutationHelpersThemselvesKeepAValidTransaction() {
        CompiledTransaction t = withStaticKey(tx("swap-sol-usdc-route.json"), STRANGER);
        assertThat(SwapRules.check(t, solToUsdc()).violations()).isEmpty(); // an unused extra key changes nothing
        assertThat(Base58.decode(STRANGER)).hasSize(32);
    }
}
