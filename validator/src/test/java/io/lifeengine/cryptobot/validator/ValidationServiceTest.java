package io.lifeengine.cryptobot.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.validator.crypto.Base58;
import io.lifeengine.cryptobot.validator.crypto.SolanaKeypair;
import io.lifeengine.cryptobot.validator.policy.CanonicalJson;
import io.lifeengine.cryptobot.validator.policy.IndependentPolicyTable;
import io.lifeengine.cryptobot.validator.policy.PolicyInput;
import io.lifeengine.cryptobot.validator.policy.PolicyVerdict;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationServiceTest {

    static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    static final String MSG = "a".repeat(64);
    static final SolanaKeypair KEY = SolanaKeypair.generate();

    static ValidatorProperties props(boolean enabled, String expectedHash) {
        return new ValidatorProperties("tok", "", keyJson(KEY), Duration.ofSeconds(90), enabled,
                new ValidatorProperties.Policy("cryptobot-policy-v1", List.of("SOL", "USDC"), List.of("REBALANCE"),
                        50_000L, 250_000L, 8_000, 100, 900L, 10_000L, 25_000L, expectedHash));
    }

    static String keyJson(SolanaKeypair k) {
        byte[] s = k.secretKey();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.length; i++) {
            sb.append(i == 0 ? "" : ",").append(s[i] & 0xFF);
        }
        return sb.append("]").toString();
    }

    static ValidationService service(boolean enabled) {
        ValidatorProperties p = props(enabled, "");
        return new ValidationService(p, new PolicyStore(p), new AttestationKeyStore(p), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The facts of a $150 SOL rebalance that every predicate accepts: second-agent tier ⇒ ESCALATE. */
    static Map<String, Object> intent() {
        Map<String, Object> i = new HashMap<>();
        i.put("agent_id", "user-1");
        i.put("strategy_id", "REBALANCE");
        i.put("policy_version", "cryptobot-policy-v1");
        i.put("asset", "SOL");
        i.put("trade_value_cents", 15_000);
        i.put("max_slippage_bps", 50);
        i.put("valid_until_slot", NOW.getEpochSecond() + 600);
        return i;
    }

    static Map<String, Object> state() {
        Map<String, Object> s = new HashMap<>();
        s.put("daily_exposure_cents", 0);
        s.put("asset_exposure_after_bps", 5_000);
        s.put("oracle_age_seconds", 30);
        s.put("agent_permitted", true);
        s.put("nonce_unused", true);
        s.put("current_slot", NOW.getEpochSecond());
        return s;
    }

    static ValidationService.Request request(ValidationService svc, String expectedVerdictHash) {
        PolicyStore store = new PolicyStore(props(true, ""));
        return new ValidationService.Request("prop-1", store.hash(), expectedVerdictHash, MSG, intent(), state(), "devnet");
    }

    @Test
    void agreesWithTheRecordedVerdictAndAttestsTheExactBytes() {
        ValidationService svc = service(true);
        PolicyStore store = new PolicyStore(props(true, ""));
        PolicyVerdict expected = IndependentPolicyTable.evaluate(store.rules(), ValidationService.Facts.input(intent(), state()));

        ValidationService.Response r = svc.validate(request(svc, expected.hash()));

        assertThat(r.decision()).isEqualTo("ESCALATE");
        assertThat(r.escalation()).isEqualTo("REQUIRE_SECOND_AGENT");
        assertThat(r.refusals()).isEmpty();
        assertThat(r.failedPredicates()).isEmpty();
        assertThat(r.policyHash()).isEqualTo(store.hash());
        assertThat(r.verdictHash()).isEqualTo(expected.hash());
        assertThat(r.expiresAt()).isEqualTo(NOW.getEpochSecond() + 90);

        // The attestation is canonical JSON signed by the validator key and bound to proposal + message hash.
        ValidationService.Attestation a = r.attestation();
        assertThat(a.validator()).isEqualTo(KEY.publicKeyBase58());
        assertThat(a.payload()).contains("\"message_hash\":\"" + MSG + "\"").contains("\"proposal_id\":\"prop-1\"")
                .contains("\"decision\":\"ESCALATE\"").contains("\"verdict_hash\":\"" + expected.hash() + "\"")
                // ... and to the cluster the bytes are for.
                .contains("\"cluster\":\"devnet\"");
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), a.payload().getBytes(StandardCharsets.UTF_8), Base58.decode(a.signature()))).isTrue();
        // A byte flipped in the payload no longer verifies: the signer cannot be fooled by editing it.
        String tampered = a.payload().replace("\"decision\":\"ESCALATE\"", "\"decision\":\"ALLOW\"");
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), tampered.getBytes(StandardCharsets.UTF_8), Base58.decode(a.signature()))).isFalse();
    }

    @Test
    void disagreementWithTheAgentIsDeny() {
        ValidationService svc = service(true);
        ValidationService.Response r = svc.validate(request(svc, "sha256:" + "0".repeat(64)));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).containsExactly(ValidationService.REFUSAL_DISAGREEMENT);
        assertThat(r.attestation().payload()).contains("\"decision\":\"DENY\"");
    }

    @Test
    void anotherPolicyHashIsDeny() {
        ValidationService svc = service(true);
        ValidationService.Request req = new ValidationService.Request("prop-1", "sha256:" + "f".repeat(64), null, MSG, intent(), state(), "devnet");
        ValidationService.Response r = svc.validate(req);
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).containsExactly(ValidationService.REFUSAL_POLICY_HASH);
    }

    @Test
    void unknownFactsDenyAndNameThePredicates() {
        ValidationService svc = service(true);
        Map<String, Object> partial = intent();
        partial.remove("trade_value_cents");
        Map<String, Object> s = state();
        s.put("oracle_age_seconds", "30"); // wrong type ⇒ unknown, never coerced
        ValidationService.Response r = svc.validate(new ValidationService.Request("prop-1", new PolicyStore(props(true, "")).hash(), null, MSG, partial, s, "devnet"));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.failedPredicates()).contains("TRADE_WITHIN_MAX", "DAILY_LIMIT", "ORACLE_FRESH");
        assertThat(r.tier()).isEqualTo("OVER_LIMIT");
    }

    @Test
    void disabledValidatorDeniesEverything() {
        ValidationService svc = service(false);
        ValidationService.Response r = svc.validate(request(svc, null));
        assertThat(r.decision()).isEqualTo("DENY");
        assertThat(r.refusals()).containsExactly(ValidationService.REFUSAL_DISABLED);
    }

    @Test
    void malformedRequestsGetNoAttestation() {
        ValidationService svc = service(true);
        String hash = new PolicyStore(props(true, "")).hash();
        assertThatThrownBy(() -> svc.validate(new ValidationService.Request(null, hash, null, MSG, intent(), state(), "devnet")))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_proposal_id");
        assertThatThrownBy(() -> svc.validate(new ValidationService.Request("p", hash, null, "zz", intent(), state(), "devnet")))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_or_invalid_message_hash");
        assertThatThrownBy(() -> svc.validate(new ValidationService.Request("p", " ", null, MSG, intent(), state(), "devnet")))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_policy_hash");
    }

    @Test
    void inputHashIsTheSchemasCanonicalForm() {
        PolicyInput in = ValidationService.Facts.input(intent(), state());
        assertThat(in.canonicalJson()).startsWith("{\"intent\":{\"agent_id\":\"user-1\",\"asset\":\"SOL\"");
        assertThat(in.hash()).isEqualTo(CanonicalJson.sha256(in.canonicalJson()));
        // A 1.5 is not an integer fact; 2.0 is.
        assertThat(ValidationService.Facts.lng(1.5)).isNull();
        assertThat(ValidationService.Facts.lng(2.0)).isEqualTo(2L);
        assertThat(ValidationService.Facts.integer(70_000L)).isEqualTo(70_000);
    }

    @Test
    void theClusterIsRequiredNormalizedAndAttested() {
        ValidationService svc = service(true);
        String hash = new PolicyStore(props(true, "")).hash();
        assertThatThrownBy(() -> svc.validate(new ValidationService.Request("p", hash, null, MSG, intent(), state(), null)))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_or_invalid_cluster");
        assertThatThrownBy(() -> svc.validate(new ValidationService.Request("p", hash, null, MSG, intent(), state(), "testnet")))
                .isInstanceOf(ValidationService.MalformedRequest.class).hasMessage("missing_or_invalid_cluster");
        // "mainnet" and "MAINNET-BETA" are the same cluster; the payload carries the canonical id.
        assertThat(svc.validate(new ValidationService.Request("p", hash, null, MSG, intent(), state(), "mainnet")).attestation().payload())
                .contains("\"cluster\":\"mainnet-beta\"");
        assertThat(svc.validate(new ValidationService.Request("p", hash, null, MSG, intent(), state(), "MAINNET-BETA")).attestation().payload())
                .contains("\"cluster\":\"mainnet-beta\"");
    }
}
