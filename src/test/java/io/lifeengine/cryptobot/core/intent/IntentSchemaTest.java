package io.lifeengine.cryptobot.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fail-closed behaviour of the schema: what is refused, and why two equal intents hash equal. */
class IntentSchemaTest {

    private static final String SWAP = """
            {"schema_version":"1","agent_id":"crypto-agent-42","action":"SWAP","input_asset":"USDC","output_asset":"SOL",
             "input_amount":"250000000","max_slippage_bps":50,"strategy_id":"momentum-v3","policy_version":"7",
             "valid_until_slot":421872223,"nonce":829147}
            """;

    private static Map<String, Object> swap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", "1");
        m.put("agent_id", "crypto-agent-42");
        m.put("action", "SWAP");
        m.put("input_asset", "USDC");
        m.put("output_asset", "SOL");
        m.put("input_amount", "250000000");
        m.put("max_slippage_bps", 50);
        m.put("strategy_id", "momentum-v3");
        m.put("policy_version", "7");
        m.put("valid_until_slot", 421872223L);
        m.put("nonce", 829147L);
        return m;
    }

    private static Map<String, Object> swapWith(String field, Object value) {
        Map<String, Object> m = swap();
        m.put(field, value);
        return m;
    }

    private static Map<String, Object> swapWithout(String field) {
        Map<String, Object> m = swap();
        m.remove(field);
        return m;
    }

    private static void refused(Map<String, Object> input, String field, String fragment) {
        assertThatThrownBy(() -> IntentSchema.parse(input))
                .isInstanceOf(IntentSchemaViolation.class)
                .satisfies(e -> assertThat(((IntentSchemaViolation) e).field()).isEqualTo(field))
                .hasMessageContaining(fragment);
    }

    // ---- identity ---------------------------------------------------------------------------

    @Test
    void jsonAndMapParseToTheSameIntent() {
        assertThat(IntentSchema.parse(SWAP)).isEqualTo(IntentSchema.parse(swap()));
    }

    @Test
    void anyChangeInMeaningChangesTheHash() {
        IntentHash base = IntentSchema.parse(swap()).hash();
        assertThat(IntentSchema.parse(swapWith("nonce", 829148)).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("input_amount", "250000001")).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("action", "BUY")).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("policy_version", "8")).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("agent_id", "crypto-agent-43")).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("valid_until_slot", 421872224)).hash()).isNotEqualTo(base);
    }

    @Test
    void identifiersAreCaseSensitive_assetsAreNot() {
        IntentHash base = IntentSchema.parse(swap()).hash();
        assertThat(IntentSchema.parse(swapWith("strategy_id", "Momentum-v3")).hash()).isNotEqualTo(base);
        assertThat(IntentSchema.parse(swapWith("input_asset", "usdc")).hash()).isEqualTo(base);
    }

    @Test
    void nfcNormalizationMakesComposedAndDecomposedIdentifiersTheSameIntent() {
        IntentHash composed = IntentSchema.parse(swapWith("strategy_id", "estratégia-1")).hash();
        IntentHash decomposed = IntentSchema.parse(swapWith("strategy_id", "estratégia-1")).hash();
        assertThat(composed).isEqualTo(decomposed);
    }

    @Test
    void operationIdIsTheFirst128BitsOfTheHash() {
        IntentHash h = IntentSchema.parse(swap()).hash();
        ByteBuffer buf = ByteBuffer.wrap(h.bytes(), 0, 16);
        UUID expected = new UUID(buf.getLong(), buf.getLong());
        assertThat(h.toOperationId()).isEqualTo(expected);
        assertThat(h.bytes()).hasSize(32);
        assertThat(h.hex()).hasSize(64);
        assertThat(IntentHash.isValid(h.value())).isTrue();
        assertThat(IntentHash.isValid("sha256:xyz")).isFalse();
        assertThat(IntentHash.parse(" " + h.value().toUpperCase() + " ")).isEqualTo(h);
    }

    // ---- schema, vocabulary, fields ---------------------------------------------------------

    @Test
    void refusesUnknownSchemaVersion() {
        refused(swapWith("schema_version", "2"), "schema_version", "unsupported schema version 2");
        refused(swapWith("schema_version", 1), "schema_version", "must be a string");
        refused(swapWithout("schema_version"), "schema_version", "missing");
    }

    @Test
    void refusesActionsOutsideTheVocabulary_acceptsAnyCase() {
        refused(swapWith("action", "MAXIMIZE_PROFIT"), "action", "not in the vocabulary");
        refused(swapWith("action", "buy now"), "action", "not in the vocabulary");
        assertThat(IntentSchema.parse(swapWith("action", "sell")).action()).isEqualTo(IntentAction.SELL);
        assertThat(IntentSchema.parse(swapWith("action", " Buy ")).action()).isEqualTo(IntentAction.BUY);
    }

    @Test
    void refusesUnknownFields_evenWhenNull() {
        refused(swapWith("reason", "SOL momentum looks strong"), "reason", "unknown field");
        refused(swapWith("memo", null), "memo", "unknown field");
    }

    @Test
    void refusesDuplicateKeysInJson() {
        String dup = "{\"schema_version\":\"1\",\"agent_id\":\"a\",\"agent_id\":\"b\",\"action\":\"HOLD\",\"strategy_id\":\"s\",\"policy_version\":\"1\",\"valid_until_slot\":1,\"nonce\":0}";
        assertThatThrownBy(() -> IntentSchema.parse(dup)).isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("Duplicate");
    }

    @Test
    void refusesNonObjectsAndEmptyDocuments() {
        assertThatThrownBy(() -> IntentSchema.parse("[1,2]")).isInstanceOf(IntentSchemaViolation.class);
        assertThatThrownBy(() -> IntentSchema.parse("\"SWAP\"")).isInstanceOf(IntentSchemaViolation.class);
        assertThatThrownBy(() -> IntentSchema.parse("   ")).isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> IntentSchema.parse((String) null)).isInstanceOf(IntentSchemaViolation.class);
        assertThatThrownBy(() -> IntentSchema.parse((Map<String, ?>) null)).isInstanceOf(IntentSchemaViolation.class);
    }

    @Test
    void refusesMissingRequiredFields() {
        for (String f : new String[] {"agent_id", "action", "strategy_id", "policy_version", "valid_until_slot", "nonce",
                "input_asset", "output_asset", "input_amount", "max_slippage_bps"}) {
            refused(swapWithout(f), f, "missing");
        }
    }

    @Test
    void explicitNullIsAbsent() {
        refused(swapWith("input_amount", null), "input_amount", "missing");
        Map<String, Object> hold = swap();
        hold.put("action", "HOLD");
        hold.put("input_asset", null);
        hold.put("output_asset", null);
        hold.put("input_amount", null);
        hold.put("max_slippage_bps", null);
        assertThat(IntentSchema.parse(hold).action()).isEqualTo(IntentAction.HOLD);
    }

    @Test
    void identifiersMustBeCleanStrings() {
        refused(swapWith("agent_id", "   "), "agent_id", "blank");
        refused(swapWith("agent_id", "a\u0000b"), "agent_id", "control characters");
        refused(swapWith("agent_id", "x".repeat(129)), "agent_id", "longer than 128");
        refused(swapWith("agent_id", 42), "agent_id", "must be a string");
    }

    // ---- numbers ----------------------------------------------------------------------------

    @Test
    void amountIsAPositiveU64_asNumberOrDigitString_neverAFloat() {
        assertThat(IntentSchema.parse(swapWith("input_amount", 250000000L)).inputAmount()).isEqualTo(BigInteger.valueOf(250000000L));
        assertThat(IntentSchema.parse(swapWith("input_amount", new BigInteger("18446744073709551615"))).inputAmount())
                .isEqualTo(TradingIntent.MAX_AMOUNT);
        assertThat(IntentSchema.parse(swapWith("input_amount", "18446744073709551615")).canonicalJson())
                .contains("\"input_amount\":\"18446744073709551615\"");
        refused(swapWith("input_amount", "18446744073709551616"), "input_amount", "exceeds u64");
        refused(swapWith("input_amount", "0"), "input_amount", "positive");
        refused(swapWith("input_amount", "-5"), "input_amount", "decimal digits");
        refused(swapWith("input_amount", "007"), "input_amount", "decimal digits");
        refused(swapWith("input_amount", "1e9"), "input_amount", "decimal digits");
        refused(swapWith("input_amount", "250000000.0"), "input_amount", "decimal digits");
        refused(swapWith("input_amount", 2.5e8), "input_amount", "floating-point");
        refused(swapWith("input_amount", true), "input_amount", "must be an integer");
    }

    @Test
    void jsonFloatsAreRefusedEvenWhenIntegral() {
        assertThatThrownBy(() -> IntentSchema.parse(SWAP.replace("\"250000000\"", "250000000.0")))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("input_amount");
        assertThatThrownBy(() -> IntentSchema.parse(SWAP.replace("\"250000000\"", "2.5e8")))
                .isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("input_amount");
    }

    @Test
    void slippageSlotAndNonceAreRangeChecked() {
        refused(swapWith("max_slippage_bps", 10001), "max_slippage_bps", "0..10000");
        refused(swapWith("max_slippage_bps", -1), "max_slippage_bps", "0..10000");
        refused(swapWith("max_slippage_bps", new BigInteger("99999999999999999999")), "max_slippage_bps", "0..10000");
        assertThat(IntentSchema.parse(swapWith("max_slippage_bps", 0)).maxSlippageBps()).isZero();
        assertThat(IntentSchema.parse(swapWith("max_slippage_bps", 10000)).maxSlippageBps()).isEqualTo(10000);
        refused(swapWith("valid_until_slot", 0), "valid_until_slot", "positive slot");
        refused(swapWith("valid_until_slot", JsonCanonicalizer.MAX_SAFE_INTEGER + 1), "valid_until_slot", "safe integer range");
        refused(swapWith("nonce", "-1"), "nonce", "decimal digits");
        refused(swapWith("nonce", new BigInteger("9007199254740992")), "nonce", "safe integer range");
        assertThat(IntentSchema.parse(swapWith("nonce", 0)).nonce()).isZero();
    }

    // ---- assets -----------------------------------------------------------------------------

    @Test
    void assetsAreUpperCaseSymbolsOrVerbatimMints() {
        String mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"; // USDC mint, base58, case-sensitive
        TradingIntent i = IntentSchema.parse(swapWith("input_asset", " " + mint + " "));
        assertThat(i.inputAsset().value()).isEqualTo(mint);
        assertThat(i.inputAsset().isMint()).isTrue();
        assertThat(i.outputAsset().isMint()).isFalse();
        refused(swapWith("input_asset", "this-is-too-long-for-a-symbol"), "input_asset", "not a symbol nor a base58 mint");
        refused(swapWith("input_asset", "US DC"), "input_asset", "not a symbol nor a base58 mint");
        refused(swapWith("input_asset", "0OIl0OIl0OIl0OIl0OIl0OIl0OIl0OIl0OIl"), "input_asset", "not a symbol nor a base58 mint");
        refused(swapWith("input_asset", ""), "input_asset", "not a symbol nor a base58 mint");
        refused(swapWith("input_asset", 5), "input_asset", "must be a string");
        assertThatThrownBy(() -> new AssetId("usdc")).isInstanceOf(IntentSchemaViolation.class).hasMessageContaining("upper case");
    }

    @Test
    void inputAndOutputMustDiffer() {
        refused(swapWith("output_asset", "usdc"), "output_asset", "must differ from input_asset");
    }

    // ---- per-action field matrix ------------------------------------------------------------

    @Test
    void holdCarriesNothingElse() {
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("schema_version", "1");
        hold.put("agent_id", "a");
        hold.put("action", "HOLD");
        hold.put("strategy_id", "s");
        hold.put("policy_version", "1");
        hold.put("valid_until_slot", 10);
        hold.put("nonce", 1);
        assertThat(IntentSchema.parse(hold).canonicalJson())
                .isEqualTo("{\"action\":\"HOLD\",\"agent_id\":\"a\",\"nonce\":1,\"policy_version\":\"1\",\"schema_version\":\"1\",\"strategy_id\":\"s\",\"valid_until_slot\":10}");
        hold.put("input_amount", "5");
        refused(hold, "input_amount", "not allowed for HOLD");
        hold.remove("input_amount");
        hold.put("max_slippage_bps", 5);
        refused(hold, "max_slippage_bps", "not allowed for HOLD");
    }

    @Test
    void tradesCannotCarryCancelOrRebalanceFields() {
        refused(swapWith("target_intent_hash", "sha256:" + "0".repeat(64)), "target_intent_hash", "not allowed for SWAP");
        refused(swapWith("counter_asset", "USDC"), "counter_asset", "not allowed for SWAP");
        refused(swapWith("target_weights_bps", Map.of("SOL", 10000)), "target_weights_bps", "not allowed for SWAP");
    }

    @Test
    void cancelNeedsAValidTargetHashAndNothingElse() {
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("schema_version", "1");
        cancel.put("agent_id", "a");
        cancel.put("action", "CANCEL");
        cancel.put("strategy_id", "s");
        cancel.put("policy_version", "1");
        cancel.put("valid_until_slot", 10);
        cancel.put("nonce", 2);
        refused(cancel, "target_intent_hash", "missing");
        cancel.put("target_intent_hash", "not-a-hash");
        refused(cancel, "target_intent_hash", "sha256:<64 hex>");
        cancel.put("target_intent_hash", "sha256:" + "ab".repeat(32));
        assertThat(IntentSchema.parse(cancel).targetIntentHash().hex()).isEqualTo("ab".repeat(32));
        cancel.put("input_asset", "SOL");
        refused(cancel, "input_asset", "not allowed for CANCEL");
    }

    @Test
    void rebalanceWeightsMustBeBpsThatSumTo10000_keyedByCanonicalAssets() {
        Map<String, Object> rb = new LinkedHashMap<>();
        rb.put("schema_version", "1");
        rb.put("agent_id", "a");
        rb.put("action", "REBALANCE");
        rb.put("strategy_id", "s");
        rb.put("policy_version", "1");
        rb.put("valid_until_slot", 10);
        rb.put("nonce", 3);
        rb.put("counter_asset", "usdc");
        rb.put("max_slippage_bps", 10);
        refused(rb, "target_weights_bps", "missing");
        rb.put("target_weights_bps", Map.of());
        refused(rb, "target_weights_bps", "must not be empty");
        rb.put("target_weights_bps", Map.of("SOL", 6000, "USDC", 3000));
        refused(rb, "target_weights_bps", "must sum to 10000 bps, got 9000");
        rb.put("target_weights_bps", Map.of("SOL", 10001));
        refused(rb, "target_weights_bps.SOL", "0..10000");
        rb.put("target_weights_bps", Map.of("SOL", "abc"));
        refused(rb, "target_weights_bps.SOL", "decimal digits");
        Map<String, Object> twice = new LinkedHashMap<>();
        twice.put("SOL", 5000);
        twice.put("sol", 5000);
        rb.put("target_weights_bps", twice);
        refused(rb, "target_weights_bps.sol", "listed twice");
        rb.put("target_weights_bps", "SOL=100%");
        refused(rb, "target_weights_bps", "must be an object");
        rb.put("target_weights_bps", Map.of("SOL", 5000, "usdc", 5000));
        TradingIntent ok = IntentSchema.parse(rb);
        assertThat(ok.targetWeightsBps()).containsEntry(new AssetId("SOL"), 5000).containsEntry(new AssetId("USDC"), 5000);
        assertThat(ok.canonicalJson()).contains("\"target_weights_bps\":{\"SOL\":5000,\"USDC\":5000}");
        rb.remove("counter_asset");
        refused(rb, "counter_asset", "missing");
    }

    @Test
    void factoriesAndParserAgree() {
        TradingIntent built = TradingIntent.trade(IntentAction.SWAP, "crypto-agent-42", "momentum-v3", "7", 421872223L, 829147L,
                AssetId.of("usdc"), AssetId.of("SOL"), new BigInteger("250000000"), 50);
        assertThat(built).isEqualTo(IntentSchema.parse(SWAP));
        assertThat(TradingIntent.hold("a", "s", "1", 10, 1).canonicalJson()).contains("\"action\":\"HOLD\"");
        assertThat(TradingIntent.cancel("a", "s", "1", 10, 2, built.hash()).targetIntentHash()).isEqualTo(built.hash());
        assertThat(TradingIntent.rebalance("a", "s", "1", 10, 3, Map.of(AssetId.of("SOL"), 10000), AssetId.of("USDC"), 10).action())
                .isEqualTo(IntentAction.REBALANCE);
        assertThat(IntentAction.SWAP.isTrade()).isTrue();
        assertThat(IntentAction.HOLD.isTrade()).isFalse();
    }

    // ---- found by the adversarial benchmark ---------------------------------------

    @Test
    void trailingTokensAfterTheDocumentAreRefused_notSilentlyDropped() {
        // "{…}{}" is two documents; a parser that keeps the first and drops the rest disagrees with one that does not.
        String twoDocuments = SWAP.trim() + "{}";
        assertThatThrownBy(() -> IntentSchema.parse(twoDocuments)).isInstanceOf(IntentSchemaViolation.class);
        assertThatThrownBy(() -> IntentSchema.parse(SWAP.trim() + " x")).isInstanceOf(IntentSchemaViolation.class);
        assertThat(IntentSchema.parse(SWAP.trim() + "\n")).isEqualTo(IntentSchema.parse(SWAP)); // whitespace is not a token
    }

    @Test
    void leadingOrTrailingControlCharactersAreRefused_notTrimmedAway() {
        // String.trim() drops every code point below U+0021, so "7\u0000" used to bind to policy "7".
        refused(swapWith("policy_version", "7\u0000"), "policy_version", "control characters");
        refused(swapWith("agent_id", "\u0001crypto-agent-42"), "agent_id", "control characters");
        refused(swapWith("strategy_id", "momentum-v3\t"), "strategy_id", "control characters");
        refused(swapWith("action", "SWAP\n"), "action", "control characters");
        refused(swapWith("input_asset", "\u001fUSDC"), "input_asset", "control characters");
        refused(swapWith("input_amount", "250000000\u0000"), "input_amount", "control characters");
        // plain spaces around a value are still just spaces
        assertThat(IntentSchema.parse(swapWith("policy_version", " 7 ")).hash()).isEqualTo(IntentSchema.parse(swap()).hash());
        assertThat(IntentSchema.parse(swapWith("input_amount", " 250000000 ")).hash()).isEqualTo(IntentSchema.parse(swap()).hash());
    }
}
