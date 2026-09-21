package io.lifeengine.cryptobot.benchmark;

import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.domain.intent.AssetId;
import io.lifeengine.cryptobot.domain.intent.IntentAction;
import io.lifeengine.cryptobot.domain.intent.IntentSchema;
import io.lifeengine.cryptobot.domain.intent.IntentSchemaViolation;
import io.lifeengine.cryptobot.domain.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.domain.intent.TradingIntent;
import io.lifeengine.cryptobot.domain.oracle.OracleConsensus;
import io.lifeengine.cryptobot.domain.oracle.OracleLimits;
import io.lifeengine.cryptobot.domain.oracle.OracleRefusal;
import io.lifeengine.cryptobot.domain.oracle.PriceObservation;
import io.lifeengine.cryptobot.domain.oracle.PriceOracle;
import io.lifeengine.cryptobot.domain.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyInput.IntentFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyInput.StateFacts;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.policy.ReferencePolicyValidator;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic execution envelope of the paper (§9) as the benchmark exercises it: the
 * only path from "an agent said something" to "something executed". It is <em>test</em> code
 * that composes <em>production</em> primitives; what is which matters for the claim, so:
 *
 * <pre>
 *   production (cryptobot-service, main)                    harness (this class)
 *   ----------------------------------------------------    ----------------------------------------------
 *   IntentSchema.parse / TradingIntent  (KAN-435)           the authoritative state S: agent registry,
 *   JsonCanonicalizer, IntentHash       (KAN-435)             nonce ledger, oracle (2 sources), slot clock,
 *   SolanaKeypair.verify (Ed25519)      (Colosseum MVP)       daily/asset exposure ledgers
 *   DeterministicPolicyEngine, PolicyRules,                  TradingIntent → IntentFacts (asset, trade value
 *     PolicyInput, PolicyVerdict        (KAN-436)             in cents from the oracle) — see proposal
 *   CryptobotMetrics                    (KAN-425/440)         intent-to-policy-binding for the production version
 *   ReferencePolicyValidator (test, KAN-436)                 the execution stub: nonce consumed, exposure added
 * </pre>
 *
 * Pipeline, every stage fail-closed (§17): {@code SCHEMA} (parse the JSON the agent emitted) →
 * {@code AUTHENTICATION} (Ed25519 over the canonical bytes, key from the registry) →
 * {@code STATE} (resolve {@code S}; whatever cannot be resolved is {@code null} = unknown) →
 * {@code POLICY} (the engine; a policy that cannot be loaded denies) → {@code VALIDATION} (the
 * independent validator must produce the same verdict hash; offline or disagreeing ⇒ PAUSE) →
 * {@code GATE} (ALLOW executes; ESCALATE waits; DENY stops; a duplicate is suppressed) →
 * {@code EXECUTION} (signer + RPC must be up, otherwise PAUSE — never a retry, never a guess).
 *
 * <p>{@code HOLD} and {@code CANCEL} are not trades and do not reach the trade policy; they still
 * need a valid signature, a permitted agent, the active policy version, an unexpired slot and a
 * fresh nonce, and a {@code CANCEL} only cancels a pending intent of the same agent.
 */
public final class AuthorityLayer {

    public static final long SLOTS_PER_DAY = 216_000L;
    /** Each agent manages a $1 000 000 book in the benchmark; concentration is measured against it. */
    public static final long PORTFOLIO_CENTS = 100_000_000L;

    public enum Result { EXECUTED, AUTHORIZED_PENDING, NOOP, CANCELLED, DENIED, PAUSED, DUPLICATE }

    public enum Stage { SCHEMA, AUTHENTICATION, STATE, POLICY, VALIDATION, GATE, EXECUTION }

    public record Timings(long parseNs, long authNs, long stateNs, long policyNs, long validateNs, long executeNs) {
        public long totalNs() {
            return parseNs + authNs + stateNs + policyNs + validateNs + executeNs;
        }
    }

    /**
     * @param result what happened
     * @param stage the stage that decided it
     * @param reason human-readable, for the report
     * @param intent the parsed intent, {@code null} when the schema refused it
     * @param input the {@code (I, S)} the policy saw, {@code null} before the STATE stage
     * @param verdict the engine's verdict, {@code null} before the POLICY stage
     * @param signatureValid whether the Ed25519 signature verified against the registered key
     */
    public record Outcome(Result result, Stage stage, String reason, TradingIntent intent, PolicyInput input, PolicyVerdict verdict,
            boolean signatureValid, Timings timings) {

        public boolean executed() {
            return result == Result.EXECUTED;
        }

        /** The paper's "authorized according to policy": not stopped by the authority layer. */
        public boolean authorized() {
            return result == Result.EXECUTED || result == Result.AUTHORIZED_PENDING || result == Result.NOOP || result == Result.CANCELLED;
        }

        public boolean blocked() {
            return result == Result.DENIED;
        }
    }

    /**
     * Three independent price sources reduced by the real {@link PriceOracle} under real
     * {@link OracleLimits} (KAN-439 / KAN-572): quorum 2, max age 60 s, deviation 100 bps. A refused
     * consensus is an unknown price ({@code null} cents) and {@link #refusalRule} names the
     * {@code PolicyEngine} rule the service would block with ({@code PRICE_DEVIATION},
     * {@code PRICE_STALE}, {@code PRICE_QUORUM}). The breaker is not exercised here (no memory
     * between cases): it is covered by {@code PriceOracleTest} and the service's flow test.
     */
    public static final class Oracle {
        public static final OracleLimits LIMITS = new OracleLimits(2, 60, 100, 1_000, 300);
        static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
        static final List<String> SOURCES = List.of("primary", "secondary", "tertiary");
        private final Map<String, Long> primaryCents = new HashMap<>();
        private final Map<String, Long> secondaryCents = new HashMap<>();
        private final Map<String, Integer> decimals = new HashMap<>();
        private Long ageSeconds = 5L;
        private boolean staleSources;
        private boolean singleSource;
        private String lastRefusalRule;

        public Oracle price(String asset, long centsPerUnit, int decimals) {
            primaryCents.put(asset, centsPerUnit);
            secondaryCents.put(asset, centsPerUnit);
            this.decimals.put(asset, decimals);
            return this;
        }

        public void ageSeconds(Long age) {
            this.ageSeconds = age;
        }

        public Long ageSeconds() {
            return ageSeconds;
        }

        /** Simulates a manipulated feed: the secondary source reports a different price. */
        public void secondary(String asset, long centsPerUnit) {
            secondaryCents.put(asset, centsPerUnit);
        }

        public void restoreSecondary(String asset) {
            secondaryCents.put(asset, primaryCents.get(asset));
        }

        /** KAN-572: every source's observation is 15 minutes old (quorum lost to staleness). */
        public void staleSources(boolean stale) {
            this.staleSources = stale;
        }

        /** KAN-572: only the primary source answers (no quorum). */
        public void singleSource(boolean single) {
            this.singleSource = single;
        }

        public boolean offline() {
            return ageSeconds == null;
        }

        public Integer decimalsOf(String asset) {
            return decimals.get(asset);
        }

        /** The {@code PolicyEngine} rule the last {@link #priceCents} of {@code asset} was refused by, or {@code null}. */
        public String refusalRule() {
            return lastRefusalRule;
        }

        /** Cents per whole unit, or {@code null}: offline, unknown asset, or no consensus (deviation, stale, quorum). */
        public Long priceCents(String asset) {
            lastRefusalRule = null;
            if (ageSeconds == null) {
                return null;
            }
            Long p = primaryCents.get(asset);
            Long sec = secondaryCents.get(asset);
            if (p == null || sec == null || p <= 0) {
                return null;
            }
            Instant observedAt = staleSources ? NOW.minusSeconds(900) : NOW;
            List<PriceObservation> obs = new java.util.ArrayList<>();
            obs.add(new PriceObservation("primary", asset, null, cents(p), observedAt));
            if (!singleSource) {
                obs.add(new PriceObservation("secondary", asset, null, cents(sec), observedAt));
                obs.add(new PriceObservation("tertiary", asset, null, cents(p), observedAt));
            }
            OracleConsensus c = PriceOracle.consensus(asset, null, obs, null, NOW, LIMITS);
            if (!c.accepted()) {
                lastRefusalRule = rule(c);
                return null;
            }
            return c.priceUsd().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        }

        static java.math.BigDecimal cents(long cents) {
            return java.math.BigDecimal.valueOf(cents).movePointLeft(2);
        }

        /** The same mapping {@code PolicyEngine.refusals} applies in the service. */
        static String rule(OracleConsensus c) {
            for (OracleRefusal r : c.refusals()) {
                switch (r) {
                    case DEVIATION_EXCEEDED -> {
                        return "PRICE_DEVIATION";
                    }
                    case CIRCUIT_BREAKER -> {
                        return "PRICE_CIRCUIT_BREAKER";
                    }
                    case INSUFFICIENT_SOURCES -> {
                        return c.rejected().stream().anyMatch(x -> "STALE".equals(x.reason())) ? "PRICE_STALE" : "PRICE_QUORUM";
                    }
                    case NO_OBSERVATIONS -> {
                        return "PRICE_QUORUM";
                    }
                }
            }
            return "PRICE_QUORUM";
        }
    }

    // ---- authoritative state -------------------------------------------------------------------

    private PolicyRules rules;
    private final AgentRegistry agents;
    private final Oracle oracle;
    private final CryptobotMetrics metrics;
    private Long currentSlot;
    private boolean slotOnline = true;
    private boolean broadcastOnline = true;
    private boolean signerOnline = true;
    private boolean validatorOnline = true;
    private boolean stateReadable = true;
    /** The independent validator (§20); replaceable so chaos can make it disagree. */
    private java.util.function.BiFunction<PolicyRules, PolicyInput, PolicyVerdict> validator = ReferencePolicyValidator::verdict;

    private final Set<String> reservedNonces = new HashSet<>();
    private final Map<String, Long> dailyExposureCents = new HashMap<>();
    private final Map<String, Long> assetExposureCents = new HashMap<>();
    private final Set<String> executedHashes = new HashSet<>();
    private final Map<String, TradingIntent> pendingByHash = new LinkedHashMap<>();

    public AuthorityLayer(PolicyRules rules, AgentRegistry agents, Oracle oracle, long startSlot, CryptobotMetrics metrics) {
        this.rules = rules;
        this.agents = agents;
        this.oracle = oracle;
        this.currentSlot = startSlot;
        this.metrics = metrics;
    }

    public PolicyRules rules() {
        return rules;
    }

    /** Rotates the active policy: what was authorized under the old {@code H_R} must not execute now (I4). */
    public void rules(PolicyRules newRules) {
        this.rules = newRules;
    }

    public AgentRegistry agents() {
        return agents;
    }

    public Oracle oracle() {
        return oracle;
    }

    public long currentSlot() {
        return currentSlot;
    }

    public void advanceSlots(long n) {
        currentSlot += n;
    }

    public void slotOnline(boolean v) {
        slotOnline = v;
    }

    public void broadcastOnline(boolean v) {
        broadcastOnline = v;
    }

    public void signerOnline(boolean v) {
        signerOnline = v;
    }

    public void validatorOnline(boolean v) {
        validatorOnline = v;
    }

    public void validator(java.util.function.BiFunction<PolicyRules, PolicyInput, PolicyVerdict> v) {
        validator = v;
    }

    /** {@code false} simulates a partition from the state store: every fact of {@code S} becomes unknown. */
    public void stateReadable(boolean v) {
        stateReadable = v;
    }

    public boolean nonceReserved(String agentId, long nonce) {
        return reservedNonces.contains(agentId + "#" + nonce);
    }

    public Map<String, TradingIntent> pending() {
        return Map.copyOf(pendingByHash);
    }

    public int executedCount() {
        return executedHashes.size();
    }

    // ---- the pipeline ------------------------------------------------------------------------

    public Outcome submit(String json, byte[] signature) {
        long t0 = System.nanoTime();
        TradingIntent intent;
        try {
            intent = IntentSchema.parse(json);
        } catch (IntentSchemaViolation e) {
            long t1 = System.nanoTime();
            return new Outcome(Result.DENIED, Stage.SCHEMA, "schema: " + e.getMessage(), null, null, null, false,
                    new Timings(t1 - t0, 0, 0, 0, 0, 0));
        }
        long t1 = System.nanoTime();

        boolean signatureValid = verify(intent, signature);
        boolean permitted = signatureValid && agents.isPermitted(intent.agentId());
        long t2 = System.nanoTime();

        if (intent.action() == IntentAction.HOLD || intent.action() == IntentAction.CANCEL) {
            return nonTrade(intent, signatureValid, permitted, t0, t1, t2);
        }

        PolicyInput input = policyInput(intent, permitted);
        long t3 = System.nanoTime();

        PolicyVerdict verdict;
        try {
            verdict = DeterministicPolicyEngine.evaluate(rules, input);
        } catch (IllegalArgumentException e) {
            long t4 = System.nanoTime();
            metrics.tradeRequested("blocked_by_policy", asset(input));
            return new Outcome(Result.DENIED, Stage.POLICY, "policy unavailable: " + e.getMessage(), intent, input, null, signatureValid,
                    new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, 0, 0));
        }
        long t4 = System.nanoTime();
        metrics.policyVerdict(verdict.decision().name(), verdict.escalation().name());
        verdict.failedPredicates().forEach(p -> metrics.policyPredicateFailed(p.name()));

        // Independent validation (§20): a second implementation must reach the same verdict hash.
        if (!validatorOnline) {
            long t5 = System.nanoTime();
            return new Outcome(Result.PAUSED, Stage.VALIDATION, "validator offline", intent, input, verdict, signatureValid,
                    new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, 0));
        }
        PolicyVerdict second = validator.apply(rules, input);
        if (!second.hash().equals(verdict.hash())) {
            metrics.deterministicMismatch();
            long t5 = System.nanoTime();
            return new Outcome(Result.PAUSED, Stage.VALIDATION, "validator disagreement: " + verdict.hash() + " vs " + second.hash(), intent, input, verdict,
                    signatureValid, new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, 0));
        }
        metrics.deterministicInference();
        long t5 = System.nanoTime();

        switch (verdict.decision()) {
            case DENY -> {
                metrics.tradeRequested("blocked_by_policy", asset(input));
                // KAN-572: when the price was refused, the reason also names the price-integrity rule the service blocks with.
                String priceRule = oracle.refusalRule();
                return new Outcome(Result.DENIED, Stage.GATE, "DENY " + verdict.failedPredicates() + (priceRule == null ? "" : " · " + priceRule), intent, input, verdict, signatureValid,
                        new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
            }
            case ESCALATE -> {
                metrics.tradeRequested("awaiting_approval", asset(input));
                reserveNonce(intent);
                pendingByHash.put(intent.hash().value(), intent);
                return new Outcome(Result.AUTHORIZED_PENDING, Stage.GATE, "ESCALATE " + verdict.escalation(), intent, input, verdict, signatureValid,
                        new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
            }
            default -> {
                return execute(intent, input, verdict, signatureValid, t0, t1, t2, t3, t4, t5);
            }
        }
    }

    private Outcome execute(TradingIntent intent, PolicyInput input, PolicyVerdict verdict, boolean signatureValid,
            long t0, long t1, long t2, long t3, long t4, long t5) {
        String hash = intent.hash().value();
        if (executedHashes.contains(hash)) {
            metrics.duplicateTradeSuppressed();
            return new Outcome(Result.DUPLICATE, Stage.GATE, "duplicate: " + hash + " already executed", intent, input, verdict, signatureValid,
                    new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
        }
        metrics.tradeRequested("awaiting_approval", asset(input));
        if (!signerOnline) {
            return new Outcome(Result.PAUSED, Stage.EXECUTION, "signer unavailable", intent, input, verdict, signatureValid,
                    new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
        }
        if (!broadcastOnline) {
            // The transaction is signed but the node did not answer: uncertain, left for reconciliation (KAN-403). Never retried here.
            return new Outcome(Result.PAUSED, Stage.EXECUTION, "rpc degraded: broadcast uncertain", intent, input, verdict, signatureValid,
                    new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
        }
        reserveNonce(intent);
        executedHashes.add(hash);
        long value = input.intent().tradeValueCents();
        dailyExposureCents.merge(intent.agentId() + "#" + (currentSlot / SLOTS_PER_DAY), value, Long::sum);
        assetExposureCents.merge(intent.agentId() + "#" + input.intent().asset(), value, Long::sum);
        metrics.tradeSubmitted(asset(input));
        metrics.tradeConfirmed("confirmed", asset(input));
        return new Outcome(Result.EXECUTED, Stage.EXECUTION, "executed " + hash, intent, input, verdict, signatureValid,
                new Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, System.nanoTime() - t5));
    }

    private Outcome nonTrade(TradingIntent intent, boolean signatureValid, boolean permitted, long t0, long t1, long t2) {
        String why = null;
        if (!signatureValid) {
            why = "signature does not verify";
        } else if (!permitted) {
            why = "agent not permitted";
        } else if (rules == null) {
            why = "policy unavailable";
        } else if (!rules.version().equals(intent.policyVersion())) {
            why = "policy version " + intent.policyVersion() + " is not the active " + rules.version();
        } else if (!slotOnline || !stateReadable) {
            why = "state unknown (rpc/partition)";
        } else if (currentSlot > intent.validUntilSlot()) {
            why = "expired at slot " + intent.validUntilSlot() + ", now " + currentSlot;
        } else if (nonceReserved(intent.agentId(), intent.nonce())) {
            why = "nonce reused";
        }
        long t3 = System.nanoTime();
        Timings t = new Timings(t1 - t0, t2 - t1, t3 - t2, 0, 0, 0);
        if (why != null) {
            return new Outcome(Result.DENIED, Stage.GATE, intent.action() + ": " + why, intent, null, null, signatureValid, t);
        }
        if (intent.action() == IntentAction.HOLD) {
            reserveNonce(intent);
            return new Outcome(Result.NOOP, Stage.GATE, "HOLD", intent, null, null, true, t);
        }
        TradingIntent target = pendingByHash.get(intent.targetIntentHash().value());
        if (target == null || !target.agentId().equals(intent.agentId())) {
            return new Outcome(Result.DENIED, Stage.GATE, "CANCEL: target is not a pending intent of this agent", intent, null, null, true, t);
        }
        pendingByHash.remove(intent.targetIntentHash().value());
        reserveNonce(intent);
        return new Outcome(Result.CANCELLED, Stage.GATE, "CANCEL " + intent.targetIntentHash(), intent, null, null, true, t);
    }

    // ---- resolving (I, S) --------------------------------------------------------------------

    private boolean verify(TradingIntent intent, byte[] signature) {
        SolanaKeypair key = agents.keyOf(intent.agentId());
        if (key == null || signature == null || signature.length != 64) {
            return false;
        }
        try {
            return SolanaKeypair.verify(key.publicKeyBytes(), JsonCanonicalizer.canonicalBytes(intent.canonicalMap()), signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * {@code TradingIntent → (I, S)}. Nothing is defaulted: a price the oracle cannot give, a
     * ledger that cannot be read, a slot the RPC did not return are {@code null} and fail their
     * predicate. Trade value is computed in {@link BigInteger} and rounded <em>up</em> to cents; a
     * value outside the safe integer range is unknown, not truncated.
     */
    PolicyInput policyInput(TradingIntent intent, boolean permitted) {
        String asset = headlineAsset(intent);
        Long tradeValueCents = tradeValueCents(intent, oracle);
        IntentFacts i = new IntentFacts(intent.agentId(), intent.strategyId(), intent.policyVersion(), asset, tradeValueCents,
                intent.maxSlippageBps(), intent.validUntilSlot());
        Long daily = stateReadable && slotOnline ? dailyExposureCents.getOrDefault(intent.agentId() + "#" + (currentSlot / SLOTS_PER_DAY), 0L) : null;
        Integer exposureAfter = null;
        if (stateReadable && tradeValueCents != null && asset != null) {
            long after = assetExposureCents.getOrDefault(intent.agentId() + "#" + asset, 0L) + tradeValueCents;
            BigInteger bps = BigInteger.valueOf(after).multiply(BigInteger.valueOf(10_000)).divide(BigInteger.valueOf(PORTFOLIO_CENTS));
            exposureAfter = bps.compareTo(BigInteger.valueOf(10_000)) > 0 ? 10_000 : bps.intValue();
        }
        Long oracleAge = oracle.offline() ? null : oracle.ageSeconds();
        Boolean nonceUnused = stateReadable ? !nonceReserved(intent.agentId(), intent.nonce()) : null;
        Long slot = slotOnline ? currentSlot : null;
        StateFacts s = new StateFacts(daily, exposureAfter, oracleAge, permitted, nonceUnused, slot);
        return new PolicyInput(i, s);
    }

    /** The asset the policy judges: a disallowed leg if there is one (so the deny names it), else the acquired asset. */
    private String headlineAsset(TradingIntent intent) {
        List<AssetId> legs = switch (intent.action()) {
            case BUY, SWAP -> List.of(intent.outputAsset(), intent.inputAsset());
            case SELL -> List.of(intent.inputAsset(), intent.outputAsset());
            case REBALANCE -> {
                List<AssetId> all = new java.util.ArrayList<>(intent.targetWeightsBps().keySet());
                all.sort((a, b) -> {
                    int byWeight = Integer.compare(intent.targetWeightsBps().get(b), intent.targetWeightsBps().get(a));
                    return byWeight != 0 ? byWeight : a.value().compareTo(b.value());
                });
                all.add(intent.counterAsset());
                yield all;
            }
            default -> List.of();
        };
        if (rules != null) {
            for (AssetId a : legs) {
                if (!rules.allowsAsset(a.value())) {
                    return a.value();
                }
            }
        }
        return legs.isEmpty() ? null : legs.get(0).value();
    }

    /** Trade value in cents at the oracle's price, rounded up; {@code null} when the oracle cannot price it or the value is not a safe integer. */
    static Long tradeValueCents(TradingIntent intent, Oracle oracle) {
        BigInteger cents;
        switch (intent.action()) {
            case BUY, SELL, SWAP -> {
                Long price = oracle.priceCents(intent.inputAsset().value());
                Integer decimals = oracle.decimalsOf(intent.inputAsset().value());
                if (price == null || decimals == null) {
                    return null;
                }
                BigInteger scale = BigInteger.TEN.pow(decimals);
                BigInteger[] qr = intent.inputAmount().multiply(BigInteger.valueOf(price)).divideAndRemainder(scale);
                cents = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
            }
            case REBALANCE -> {
                // Current book: 50 % SOL / 50 % USDC. Turnover = Σ|target − current| / 2 of the portfolio.
                Map<String, Integer> current = Map.of("SOL", 5_000, "USDC", 5_000);
                long delta = 0;
                Set<String> assets = new HashSet<>(current.keySet());
                intent.targetWeightsBps().keySet().forEach(a -> assets.add(a.value()));
                for (String a : assets) {
                    int target = intent.targetWeightsBps().entrySet().stream().filter(e -> e.getKey().value().equals(a)).map(Map.Entry::getValue).findFirst().orElse(0);
                    delta += Math.abs(target - current.getOrDefault(a, 0));
                }
                cents = BigInteger.valueOf(PORTFOLIO_CENTS).multiply(BigInteger.valueOf(delta)).divide(BigInteger.valueOf(20_000));
            }
            default -> {
                return null;
            }
        }
        if (cents.compareTo(BigInteger.valueOf(JsonCanonicalizer.MAX_SAFE_INTEGER)) > 0) {
            return null;
        }
        return cents.longValueExact();
    }

    private void reserveNonce(TradingIntent intent) {
        reservedNonces.add(intent.agentId() + "#" + intent.nonce());
    }

    /** Chaos: the nonce ledger lost a reservation (crash between execution and the ledger write); the executed-hash record must still hold. */
    public void forgetNonce(String agentId, long nonce) {
        reservedNonces.remove(agentId + "#" + nonce);
    }

    private static String asset(PolicyInput input) {
        return input == null || input.intent() == null ? null : input.intent().asset();
    }
}
