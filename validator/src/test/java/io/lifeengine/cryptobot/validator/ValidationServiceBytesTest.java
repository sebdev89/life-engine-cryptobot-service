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

    static ValidationService.Request request(String claimedHash, byte[] tx, Map<String, Object> swap) {
        String policyHash = new PolicyStore(ValidationServiceTest.props(true, "")).hash();
        return new ValidationService.Request("prop-1", policyHash, null, claimedHash, ValidationServiceTest.intent(), ValidationServiceTest.state(),
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
        ValidationService.Response r = ValidationServiceTest.service(true).validate(request(ValidationServiceTest.MSG, null, null));

        assertThat(r.messageHash()).isEqualTo(ValidationServiceTest.MSG);
        assertThat(r.checks()).isEmpty();
        assertThat(payload(r).has("checks")).isFalse();
    }

    @Test
    void withTheBytesTheValidatorAttestsTheHashItComputed() throws Exception {
        byte[] tx = jupiterTx();
        ValidationService.Response r = ValidationServiceTest.service(true).validate(request(null, tx, null));

        assertThat(r.refusals()).isEmpty();
        assertThat(r.messageHash()).isEqualTo(messageHashOf(tx));
        assertThat(payload(r).path("message_hash").asText()).isEqualTo(messageHashOf(tx));
        assertThat(payload(r).path("checks").toString()).contains(ValidationService.CHECK_HASH_FROM_BYTES);
    }

    @Test
    void aClaimedHashThatIsNotTheBytesIsRefusedAndTheAttestationNamesTheRealOne() throws Exception {
        byte[] tx = jupiterTx();
        ValidationService.Response r = ValidationServiceTest.service(true).validate(request("b".repeat(64), tx, null));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_MESSAGE_HASH);
        // never the claimed hash: a DENY that named it could be mistaken for evidence about other bytes
        assertThat(payload(r).path("message_hash").asText()).isEqualTo(messageHashOf(tx));
    }

    @Test
    void aSwapThatPassesTheRulesKeepsThePolicyDecision() throws Exception {
        ValidationService.Response r = ValidationServiceTest.service(true).validate(request(null, jupiterTx(), swap()));

        assertThat(r.swapViolations()).isEmpty();
        assertThat(r.refusals()).isEmpty();
        assertThat(r.decision()).isNotEqualTo("DENY");
        assertThat(r.checks()).containsExactly(ValidationService.CHECK_HASH_FROM_BYTES, ValidationService.CHECK_JUPITER_SWAP);
        assertThat(payload(r).path("checks").toString()).contains(ValidationService.CHECK_JUPITER_SWAP);
    }

    @Test
    void aSwapToSomeoneElsesAccountIsDenied() {
        CompiledTransaction t = CompiledTransaction.decode(jupiterTx());
        String mine = TokenAccounts.associated(OWNER, USDC);
        String theirs = TokenAccounts.associated("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", USDC);
        List<String> keys = t.staticKeys().stream().map(k -> k.equals(mine) ? theirs : k).toList();
        byte[] mutant = new CompiledTransaction(t.signatures(), t.versioned(), t.requiredSignatures(), t.readonlySigned(), t.readonlyUnsigned(),
                keys, t.recentBlockhash(), t.instructions(), t.lookupTables()).encode();

        ValidationService.Response r = ValidationServiceTest.service(true).validate(request(null, mutant, swap()));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).contains(ValidationService.REFUSAL_SWAP_RULES);
        assertThat(r.swapViolations()).anyMatch(v -> v.startsWith("route_destination_mismatch"));
    }

    @Test
    void aSwapForADifferentAmountIsDenied() {
        Map<String, Object> s = swap();
        s.put("in_amount", 20_000_000L);
        ValidationService.Response r = ValidationServiceTest.service(true).validate(request(null, jupiterTx(), s));

        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.swapViolations()).anyMatch(v -> v.startsWith("in_amount_mismatch"));
    }

    @Test
    void malformedInputsAreRejectedWithoutAnAttestation() {
        ValidationService svc = ValidationServiceTest.service(true);
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
