package io.lifeengine.cryptobot.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.validator.tx.CompiledTransaction;
import io.lifeengine.cryptobot.validator.tx.TokenAccounts;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * KAN-600 (2): {@code /validate} with the transaction bytes. The hash the attestation carries is computed here from
 * the bytes, a claimed hash that differs is refused, and a swap intent is checked against those same bytes.
 * Fixture: a real Jupiter SOL→USDC transaction for an unfunded address with no key.
 */
class ValidationServiceBytesTest {

    static final String OWNER = "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa";
    static final String SOL = TokenAccounts.WSOL_MINT;
    static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final ObjectMapper JSON = new ObjectMapper();

    static byte[] jupiterTx() {
        try (InputStream in = ValidationServiceBytesTest.class.getResourceAsStream("/jupiter/swap-sol-usdc-route.json")) {
            return Base64.getDecoder().decode(JSON.readTree(in).path("swapTransaction").asText());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static Map<String, Object> swap() {
        Map<String, Object> s = new HashMap<>();
        s.put("owner", OWNER);
        s.put("input_mint", SOL);
        s.put("output_mint", USDC);
        s.put("in_amount", 10_000_000L);
        s.put("min_out_amount", 1_042_824L);
        s.put("max_slippage_bps", 50);
        s.put("max_priority_fee_lamports", 200_000L);
        return s;
    }

    /** The pilot policy: REBALANCE and PILOT_SWAP enabled, PILOT_SWAP capped at 0.1 SOL / 10 USDC / 100 bps per swap. */
    static ValidatorProperties pilotProps() {
        ValidatorProperties base = ValidationServiceTest.props(true, "");
        ValidatorProperties.Policy p = base.policy();
        return new ValidatorProperties(base.token(), base.keypairPath(), base.keypairJson(), base.attestationTtl(), true,
                new ValidatorProperties.Policy(p.version(), p.allowedAssets(), List.of("REBALANCE", "PILOT_SWAP"), p.maxTradeValueCents(),
                        p.dailyLimitCents(), p.maxAssetExposureBps(), p.maxSlippageBps(), p.maxOracleAgeSeconds(), p.autonomousUpToCents(),
                        p.secondAgentUpToCents(), "", 100_000_000L, 10_000_000L, 100));
    }

    static ValidationService pilot() {
        ValidatorProperties p = pilotProps();
        return new ValidationService(p, new PolicyStore(p), new AttestationKeyStore(p), java.time.Clock.fixed(ValidationServiceTest.NOW, java.time.ZoneOffset.UTC));
    }

    /** A swap is PILOT_SWAP; anything else is the REBALANCE facts every predicate accepts. */
    static ValidationService.Request request(String claimedHash, byte[] tx, Map<String, Object> swap) {
        return request(claimedHash, tx, swap, swap == null ? "REBALANCE" : "PILOT_SWAP");
    }

    static ValidationService.Request request(String claimedHash, byte[] tx, Map<String, Object> swap, String strategy) {
        String policyHash = new PolicyStore(pilotProps()).hash();
        Map<String, Object> intent = new HashMap<>(ValidationServiceTest.intent());
        intent.put("strategy_id", strategy);
        return new ValidationService.Request("prop-1", policyHash, null, claimedHash, intent, ValidationServiceTest.state(),
                "devnet", tx == null ? null : b64(tx), swap);
    }

    static JsonNode payload(ValidationService.Response r) throws Exception {
        return JSON.readTree(r.attestation().payload());
    }

    static String messageHashOf(byte[] tx) {
        return ValidationService.sha256Hex(CompiledTransaction.decode(tx).message());
    }

    @Test
    void theHashOnlyRequestIsUnchanged() throws Exception {
        ValidationService.Response r = pilot().validate(request(ValidationServiceTest.MSG, null, null));

        assertThat(r.messageHash()).isEqualTo(ValidationServiceTest.MSG);
        assertThat(r.checks()).isEmpty();
        assertThat(payload(r).has("checks")).isFalse();
    }

    @Test
    void withTheBytesTheValidatorAttestsTheHashItComputed() throws Exception {
        byte[] tx = jupiterTx();
        ValidationService.Response r = pilot().validate(request(null, tx, null));

        assertThat(r.refusals()).isEmpty();
        assertThat(r.messageHash()).isEqualTo(messageHashOf(tx));
        assertThat(payload(r).path("message_hash").asText()).isEqualTo(messageHashOf(tx));
        assertThat(payload(r).path("checks").toString()).contains(ValidationService.CHECK_HASH_FROM_BYTES);
    }

    @Test
    void aClaimedHashThatIsNotTheBytesIsRefusedAndTheAttestationNamesTheRealOne() throws Exception {
        byte[] tx = jupiterTx();
        ValidationService.Response r = pilot().validate(request("b".repeat(64), tx, null));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_MESSAGE_HASH);
        // never the claimed hash: a DENY that named it could be mistaken for evidence about other bytes
        assertThat(payload(r).path("message_hash").asText()).isEqualTo(messageHashOf(tx));
    }

    @Test
    void aSwapThatPassesTheRulesKeepsThePolicyDecision() throws Exception {
        ValidationService.Response r = pilot().validate(request(null, jupiterTx(), swap()));

        assertThat(r.swapViolations()).isEmpty();
        assertThat(r.refusals()).isEmpty();
        assertThat(r.decision()).isNotEqualTo("DENY");
        assertThat(r.checks()).containsExactly(ValidationService.CHECK_HASH_FROM_BYTES, ValidationService.CHECK_JUPITER_SWAP);
        assertThat(payload(r).path("checks").toString()).contains(ValidationService.CHECK_JUPITER_SWAP);
        // the swap itself is signed, so the signer can require the exact minimum
        JsonNode swap = payload(r).path("swap");
        assertThat(swap.path("input_mint").asText()).isEqualTo(SOL);
        assertThat(swap.path("output_mint").asText()).isEqualTo(USDC);
        assertThat(swap.path("in_amount").asLong()).isEqualTo(10_000_000L);
        assertThat(swap.path("min_out_amount").asLong()).isEqualTo(1_042_824L);
    }

    @Test
    void withoutASwapIntentThereIsNoSwapInThePayload() throws Exception {
        assertThat(payload(pilot().validate(request(null, jupiterTx(), null))).has("swap")).isFalse();
    }

    @Test
    void aSwapToSomeoneElsesAccountIsDenied() {
        CompiledTransaction t = CompiledTransaction.decode(jupiterTx());
        String mine = TokenAccounts.associated(OWNER, USDC);
        String theirs = TokenAccounts.associated("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", USDC);
        List<String> keys = t.staticKeys().stream().map(k -> k.equals(mine) ? theirs : k).toList();
        byte[] mutant = new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                keys, t.recentBlockhash(), t.instructions(), t.lookupTables()).encode();

        ValidationService.Response r = pilot().validate(request(null, mutant, swap()));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_SWAP_RULES);
        assertThat(r.swapViolations()).anyMatch(v -> v.startsWith("route_destination_mismatch"));
    }

    @Test
    void aSwapForADifferentAmountIsDenied() {
        Map<String, Object> s = swap();
        s.put("in_amount", 20_000_000L);
        ValidationService.Response r = pilot().validate(request(null, jupiterTx(), s));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.swapViolations()).anyMatch(v -> v.startsWith("in_amount_mismatch"));
    }

    // ---- KAN-1249: PILOT_SWAP ⇔ a swap, within the policy's pilot_swap limits -----------------------

    @Test
    void aSwapUnderAnyOtherStrategyIsDenied() {
        ValidationService.Response r = pilot().validate(request(null, jupiterTx(), swap(), "REBALANCE"));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_SWAP_STRATEGY);
    }

    @Test
    void pilotSwapWithoutASwapIsDenied() {
        ValidationService.Response r = pilot().validate(request(null, jupiterTx(), null, "PILOT_SWAP"));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_SWAP_STRATEGY);
    }

    @Test
    void aSwapAboveThePilotLimitsIsDenied() {
        Map<String, Object> big = swap();
        big.put("in_amount", 100_000_001L); // 0.1 SOL + 1 lamport
        assertThat(pilot().validate(request(null, jupiterTx(), big)).refusals()).contains(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        Map<String, Object> slippy = swap();
        slippy.put("max_slippage_bps", 101);
        assertThat(pilot().validate(request(null, jupiterTx(), slippy)).refusals()).contains(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        // exactly at the caps it is the bytes that decide (here the real tx is for 0.01 SOL, so SwapRules refuses the amount)
        Map<String, Object> atCap = swap();
        atCap.put("max_slippage_bps", 100);
        assertThat(pilot().validate(request(null, jupiterTx(), atCap)).refusals()).doesNotContain(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
    }

    static byte[] usdcToSolTx() {
        try (InputStream in = ValidationServiceBytesTest.class.getResourceAsStream("/jupiter/swap-usdc-sol-route.json")) {
            return Base64.getDecoder().decode(JSON.readTree(in).path("swapTransaction").asText());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A USDC → SOL swap intent for {@code inAmount} USDC base units. */
    static Map<String, Object> usdcSwap(long inAmount) {
        Map<String, Object> s = swap();
        s.put("input_mint", USDC);
        s.put("output_mint", SOL);
        s.put("in_amount", inAmount);
        s.put("min_out_amount", 1L);
        return s;
    }

    static java.util.List<String> refusals(Map<String, Object> swap, byte[] tx) {
        return pilot().validate(request(null, tx, swap)).refusals();
    }

    @Test
    void eachMintHasItsOwnCapAndTheCapItselfIsAllowed() {
        // the caps differ on purpose (0.1 SOL = 100 000 000 lamports, 10 USDC = 10 000 000 units): a crossed mint would show
        Map<String, Object> solAtCap = swap();
        solAtCap.put("in_amount", 100_000_000L);
        assertThat(refusals(solAtCap, jupiterTx())).doesNotContain(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        Map<String, Object> solOver = swap();
        solOver.put("in_amount", 100_000_001L);
        assertThat(refusals(solOver, jupiterTx())).contains(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        // USDC is held to the USDC cap, not the (ten times larger) lamports one
        assertThat(refusals(usdcSwap(10_000_000L), usdcToSolTx())).doesNotContain(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        assertThat(refusals(usdcSwap(10_000_001L), usdcToSolTx())).contains(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
        assertThat(refusals(usdcSwap(99_999_999L), usdcToSolTx())).contains(ValidationService.REFUSAL_PILOT_SWAP_LIMITS);
    }

    @Test
    void aRealUsdcToSolSwapWithinThePilotIsAttested() {
        Map<String, Object> s = usdcSwap(1_000_000L);
        s.put("min_out_amount", 9_540_000L);
        ValidationService.Response r = pilot().validate(request(null, usdcToSolTx(), s));
        assertThat(r.refusals()).isEmpty();
        assertThat(r.swapViolations()).isEmpty();
        assertThat(r.decision()).isNotEqualTo("DENY");
    }

    @Test
    void anotherStrategyWithoutASwapIsUntouchedByThePilot() {
        // POV_REWARD (enabled next to the pilot): no swap, not PILOT_SWAP ⇒ no swap refusal of any kind
        ValidatorProperties base = pilotProps();
        ValidatorProperties.Policy p = base.policy();
        ValidatorProperties withPov = new ValidatorProperties(base.token(), base.keypairPath(), base.keypairJson(), base.attestationTtl(), true,
                new ValidatorProperties.Policy(p.version(), p.allowedAssets(), List.of("REBALANCE", "PILOT_SWAP", "POV_REWARD"), p.maxTradeValueCents(),
                        p.dailyLimitCents(), p.maxAssetExposureBps(), p.maxSlippageBps(), p.maxOracleAgeSeconds(), p.autonomousUpToCents(),
                        p.secondAgentUpToCents(), "", 100_000_000L, 10_000_000L, 100));
        ValidationService svc = new ValidationService(withPov, new PolicyStore(withPov), new AttestationKeyStore(withPov),
                java.time.Clock.fixed(ValidationServiceTest.NOW, java.time.ZoneOffset.UTC));
        Map<String, Object> intent = new HashMap<>(ValidationServiceTest.intent());
        intent.put("strategy_id", "POV_REWARD");
        ValidationService.Response r = svc.validate(new ValidationService.Request("prop-1", new PolicyStore(withPov).hash(), null, null, intent,
                ValidationServiceTest.state(), "devnet", b64(jupiterTx()), null));
        assertThat(r.refusals()).doesNotContain(ValidationService.REFUSAL_SWAP_STRATEGY, ValidationService.REFUSAL_PILOT_SWAP_LIMITS,
                ValidationService.REFUSAL_PILOT_SWAP_NOT_CONFIGURED, ValidationService.REFUSAL_SWAP_RULES);
    }

    @Test
    void withoutAPilotSectionNoSwapIsEverAttestedAllowed() {
        // the default policy (no pilot_swap): even the strategy right and the bytes fine, a swap is refused
        ValidationService plain = ValidationServiceTest.service(true);
        String plainHash = new PolicyStore(ValidationServiceTest.props(true, "")).hash();
        Map<String, Object> intent = new HashMap<>(ValidationServiceTest.intent());
        intent.put("strategy_id", "PILOT_SWAP");
        ValidationService.Response r = plain.validate(new ValidationService.Request("prop-1", plainHash, null, null, intent, ValidationServiceTest.state(),
                "devnet", b64(jupiterTx()), swap()));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_PILOT_SWAP_NOT_CONFIGURED);
    }

    @Test
    void thePilotChangesThePolicyHashAndNoPilotKeepsIt() {
        assertThat(io.lifeengine.cryptobot.validator.policy.CanonicalJson.canonicalize(new PolicyStore(ValidationServiceTest.props(true, "")).rules().canonicalMap())).doesNotContain("pilot_swap");
        assertThat(io.lifeengine.cryptobot.validator.policy.CanonicalJson.canonicalize(new PolicyStore(pilotProps()).rules().canonicalMap()))
                .contains("\"pilot_swap\":{\"max_lamports_in\":100000000,\"max_slippage_bps\":100,\"max_usdc_in\":10000000}");
        assertThat(new PolicyStore(pilotProps()).hash()).isNotEqualTo(new PolicyStore(ValidationServiceTest.props(true, "")).hash());
    }

    @Test
    void malformedInputsAreRejectedWithoutAnAttestation() {
        ValidationService svc = pilot();
        assertThatThrownBy(() -> svc.validate(request(ValidationServiceTest.MSG, null, swap())))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("swap_requires_transaction");
        assertThatThrownBy(() -> svc.validate(request(null, new byte[] {1, 2, 3}, null)))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("malformed_transaction");
        Map<String, Object> bad = swap();
        bad.put("in_amount", "10000000");
        assertThatThrownBy(() -> svc.validate(request(null, jupiterTx(), bad)))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("invalid_swap_intent");
        assertThatThrownBy(() -> svc.validate(request(null, null, null)))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_or_invalid_message_hash");
    }
}
