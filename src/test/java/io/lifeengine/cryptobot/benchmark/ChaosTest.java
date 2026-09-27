package io.lifeengine.cryptobot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Outcome;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Result;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Stage;
import io.lifeengine.cryptobot.core.intent.AssetId;
import io.lifeengine.cryptobot.core.intent.IntentAction;
import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.intent.TradingIntent;
import io.lifeengine.cryptobot.core.policy.PolicyPredicate;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Paper §30: the failures the system must provoke on purpose — oracle offline, validator
 * offline, RPC degraded, policy unavailable, duplicate transaction, network partition, signer
 * unavailable, malformed intent, old schema version — and the only acceptable answer to each:
 * <b>DENY or PAUSE, never silent execution</b>. Every scenario starts from an intent that
 * executes when everything is healthy (the control), so the difference is the failure alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChaosTest {

    final AgentRegistry agents = BenchmarkRun.agents();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    AuthorityLayer layer;
    SimpleMeterRegistry registry;
    long nonce = 9_000_000L;

    @BeforeEach
    void freshLayer() {
        registry = new SimpleMeterRegistry();
        layer = new AuthorityLayer(CorpusGenerator.paperRules(), agents, CorpusGenerator.oracle(), CorpusGenerator.START_SLOT,
                new CryptobotMetrics(registry, CorpusGenerator.ASSETS));
    }

    @AfterAll
    void report() throws Exception {
        evidence.put("unexpected contract upgrade", Map.of("result", "N/A", "stage", "-",
                "reason", "no on-chain program yet (KAN-437 pending): nothing to upgrade; the envelope has no contract dependency to fail on"));
        BenchmarkReport.writeSection("cryptobot chaos scenarios (KAN-440, paper §30)", "chaos", evidence, "chaos-v1");
    }

    /** A $500 SELL of USDC by a permitted agent: ALLOW tier, executes on a healthy layer. */
    private TradingIntent control() {
        String agent = agents.permittedAgents().get(3);
        return TradingIntent.trade(IntentAction.SELL, agent, "momentum-v3", layer.rules().version(), layer.currentSlot() + 500, nonce++,
                AssetId.of("USDC"), AssetId.of("SOL"), CorpusGenerator.amountFor("USDC", 50_000L), 25);
    }

    private String json(TradingIntent t) {
        return Json.write(CorpusGenerator.fields(t), new Random(t.nonce()));
    }

    private byte[] sign(TradingIntent t) {
        return agents.sign(t.agentId(), JsonCanonicalizer.canonicalBytes(t.canonicalMap()));
    }

    private Outcome record(String scenario, Outcome o) {
        evidence.put(scenario, Map.of("result", o.result().name(), "stage", o.stage().name(), "reason", o.reason()));
        return o;
    }

    @Test
    void control_healthyLayerExecutesTheIntent() {
        TradingIntent t = control();
        Outcome o = record("control (healthy)", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.EXECUTED);
        assertThat(layer.executedCount()).isEqualTo(1);
    }

    @Test
    void oracleOffline_denies() {
        TradingIntent t = control();
        layer.oracle().ageSeconds(null);
        Outcome o = record("oracle offline", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.verdict().failedPredicates()).contains(PolicyPredicate.ORACLE_FRESH, PolicyPredicate.TRADE_WITHIN_MAX);
        assertThat(layer.executedCount()).isZero();
        // back online: the same intent is fine (nothing was consumed by the denial)
        layer.oracle().ageSeconds(5L);
        assertThat(layer.submit(json(t), sign(t)).result()).isEqualTo(Result.EXECUTED);
    }

    @Test
    void validatorOffline_pauses() {
        TradingIntent t = control();
        layer.validatorOnline(false);
        Outcome o = record("validator offline", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.PAUSED);
        assertThat(o.stage()).isEqualTo(Stage.VALIDATION);
        assertThat(o.verdict().decision()).as("the engine said ALLOW, and that alone is not enough").isEqualTo(PolicyVerdict.Decision.ALLOW);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void validatorDisagreement_pausesAndCountsAMismatch() {
        TradingIntent t = control();
        // A validator that flips every ALLOW to DENY: a real bug in one implementation, or a tampered one.
        layer.validator((rules, input) -> {
            PolicyVerdict v = io.lifeengine.cryptobot.core.policy.DeterministicPolicyEngine.evaluate(rules, input);
            return new PolicyVerdict(PolicyVerdict.Decision.DENY, PolicyVerdict.Escalation.NONE, v.tier(), List.of(PolicyPredicate.AGENT_PERMITTED),
                    v.evaluatedPredicates(), v.policyVersion(), v.policyHash(), v.inputHash());
        });
        Outcome o = record("validator disagreement", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.PAUSED);
        assertThat(o.reason()).startsWith("validator disagreement");
        assertThat(registry.get("deterministic.mismatch").counter().count()).isEqualTo(1);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void rpcDegraded_noSlot_denies() {
        TradingIntent t = control();
        layer.slotOnline(false);
        Outcome o = record("rpc degraded (getSlot fails)", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.verdict().failedPredicates()).contains(PolicyPredicate.NOT_EXPIRED, PolicyPredicate.DAILY_LIMIT);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void rpcDegraded_broadcastUncertain_pausesWithoutRetry() {
        TradingIntent t = control();
        layer.broadcastOnline(false);
        Outcome o = record("rpc degraded (sendTransaction times out)", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.PAUSED);
        assertThat(o.stage()).isEqualTo(Stage.EXECUTION);
        assertThat(layer.executedCount()).isZero();
        assertThat(registry.get("trade.submitted").counters().stream().mapToDouble(c -> c.count()).sum()).isZero();
    }

    @Test
    void policyUnavailable_denies() {
        TradingIntent t = control();
        layer.rules(null);
        Outcome o = record("policy unavailable", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.stage()).isEqualTo(Stage.POLICY);
        assertThat(layer.executedCount()).isZero();
        assertThat(registry.get("trade.requested").tag("result", "blocked_by_policy").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(1);
    }

    @Test
    void duplicateTransaction_executesExactlyOnce() {
        TradingIntent t = control();
        String json = json(t);
        byte[] sig = sign(t);
        assertThat(layer.submit(json, sig).result()).isEqualTo(Result.EXECUTED);
        Outcome replay = record("duplicate transaction (same bytes twice)", layer.submit(json, sig));
        assertThat(replay.result()).isEqualTo(Result.DENIED);
        assertThat(replay.verdict().failedPredicates()).containsExactly(PolicyPredicate.NONCE_UNUSED);
        // and with the nonce ledger's reservation lost (crash between execution and ledger write): still once
        layer.forgetNonce(t.agentId(), t.nonce());
        Outcome afterCrash = record("duplicate transaction (nonce ledger lost the reservation)", layer.submit(json, sig));
        assertThat(afterCrash.result()).isEqualTo(Result.DUPLICATE);
        assertThat(layer.executedCount()).isEqualTo(1);
        assertThat(registry.get("duplicate.trade.suppressed").counter().count()).isEqualTo(1);
    }

    @Test
    void networkPartition_stateUnreadable_denies() {
        TradingIntent t = control();
        layer.stateReadable(false);
        Outcome o = record("network partition (state store unreachable)", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.verdict().failedPredicates()).contains(PolicyPredicate.DAILY_LIMIT, PolicyPredicate.ASSET_CONCENTRATION, PolicyPredicate.NONCE_UNUSED);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void signerUnavailable_pauses() {
        TradingIntent t = control();
        layer.signerOnline(false);
        Outcome o = record("signer unavailable", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.PAUSED);
        assertThat(o.stage()).isEqualTo(Stage.EXECUTION);
        assertThat(layer.executedCount()).isZero();
        // the nonce was not consumed by a pause: once the signer is back the intent goes through
        layer.signerOnline(true);
        assertThat(layer.submit(json(t), sign(t)).result()).isEqualTo(Result.EXECUTED);
    }

    @Test
    void malformedIntent_denies() {
        TradingIntent t = control();
        String broken = json(t).replace("\"action\"", "\"action");
        Outcome o = record("malformed intent", layer.submit(broken, sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.stage()).isEqualTo(Stage.SCHEMA);
        Outcome empty = layer.submit("", sign(t));
        assertThat(empty.result()).isEqualTo(Result.DENIED);
        Outcome nul = layer.submit("null", sign(t));
        assertThat(nul.result()).isEqualTo(Result.DENIED);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void oldSchemaVersion_denies() {
        TradingIntent t = control();
        Map<String, Object> f = CorpusGenerator.fields(t);
        f.put(TradingIntent.F_SCHEMA_VERSION, "0");
        Outcome o = record("old schema version", layer.submit(Json.writeStable(f), sign(t)));
        assertThat(o.result()).isEqualTo(Result.DENIED);
        assertThat(o.stage()).isEqualTo(Stage.SCHEMA);
        assertThat(o.reason()).contains("schema_version");
        f.put(TradingIntent.F_SCHEMA_VERSION, "2");
        assertThat(layer.submit(Json.writeStable(f), sign(t)).result()).isEqualTo(Result.DENIED);
        assertThat(layer.executedCount()).isZero();
    }

    @Test
    void escalatedIntentDoesNotExecuteEvenWhenEverythingIsHealthy() {
        // Graduated autonomy (§18): $5 000 is inside the policy but outside the autonomous band.
        String agent = agents.permittedAgents().get(4);
        TradingIntent t = TradingIntent.trade(IntentAction.SELL, agent, "momentum-v3", layer.rules().version(), layer.currentSlot() + 500, nonce++,
                AssetId.of("USDC"), AssetId.of("SOL"), CorpusGenerator.amountFor("USDC", 500_000L), 25);
        Outcome o = record("escalated tier (healthy)", layer.submit(json(t), sign(t)));
        assertThat(o.result()).isEqualTo(Result.AUTHORIZED_PENDING);
        assertThat(o.verdict().escalation()).isEqualTo(PolicyVerdict.Escalation.REQUIRE_SECOND_AGENT);
        assertThat(layer.executedCount()).isZero();
        assertThat(layer.pending()).containsKey(t.hash().value());
    }
}
