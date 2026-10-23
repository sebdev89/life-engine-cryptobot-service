package io.lifeengine.cryptobot.application.pilot;

import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.jupiter.SwapQuote;
import io.lifeengine.cryptobot.adapters.jupiter.SwapRejectedException;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwap;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwapCheck;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import io.lifeengine.cryptobot.core.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.MainnetDisabledException;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * KAN-1249 pilot (b2b): executes an {@code APPROVED} swap order, once. Nothing is ever retried: a swap that may have
 * reached the chain is reconciled by its signature, never sent again.
 *
 * <ol>
 *   <li>Claim: APPROVED → EXECUTING, a conditional update (one executor wins across processes) inside the approval
 *       window; and at most one order executing per process.</li>
 *   <li>Re-quote with the exact binding of the approval: same mints, same input amount, and Jupiter's own minimum
 *       ({@code otherAmountThreshold}) at or above the order's {@code min_out}. The route may differ; the bound may not.
 *       The new transaction is checked ({@link UnsignedSwapCheck}) and simulated again; those exact bytes are what the
 *       validator attests, the signer signs and the RPC receives.</li>
 *   <li>Policy: {@code (I, S)} for strategy {@code PILOT_SWAP} through the same deterministic engine the validator re-runs.
 *       ALLOW, or ESCALATE for a human signature (this order has one: the approval). Anything else is a refusal.</li>
 *   <li>Validator {@code authorizeSwap} over the bytes with the swap intent (mints, in amount, the order's min_out), then
 *       signer {@code signSwap} with that same min_out. Our own check of what came back: same message, signed by the
 *       owner's key.</li>
 *   <li>The signature and the blockhash's last valid height are stored before the broadcast. The window is checked once
 *       more, then {@code sendTransaction}, which carries the mainnet gate ({@code cryptobot.execution.allow-mainnet}).
 *       A JSON-RPC rejection is certain (FAILED); any other broadcast error is not, nor is an answer naming another
 *       signature (UNKNOWN_SENT).</li>
 *   <li>Confirmation at {@code confirmed} within {@code confirm-timeout}: CONFIRMED, FAILED (the chain said so), or
 *       UNKNOWN_SENT.</li>
 * </ol>
 *
 * <p><b>The caller does not own the execution.</b> Once claimed, the run is subscribed on its own: a client that
 * disconnects (or times out) during the ~30 s does not cancel it halfway. The caller gets the final order if it waits;
 * otherwise {@code GET /swaps/{id}} shows it, and {@link SwapReconcileJob} settles whatever a crash left in flight.
 *
 * <p>{@link #reconcileOrder} asks the chain about an order's signature and moves SENT/UNKNOWN_SENT (or an EXECUTING order
 * a crash left behind) to where the chain says it is. It reads the block height <em>before</em> the signature status: if
 * the chain was already past the blockhash's last valid height and still does not know the signature, the swap can
 * never land (FAILED, {@code blockhash_expired}). An EXECUTING order without a signature past its window was never
 * broadcast — the signature is written first — so it is FAILED.
 */
public class SwapExecutor {

    private static final Logger log = LoggerFactory.getLogger(SwapExecutor.class);
    public static final String STRATEGY = "PILOT_SWAP";
    public static final String AGENT = "pilot-swap";
    /** How long the intent the policy sees is valid, in seconds (the slot is the epoch second, as for PoV payouts). */
    static final long VALID_FOR_SECONDS = 60;

    /** The SOL price the policy values a swap at, or empty when the oracle has no accepted consensus (then DENY). */
    public interface SolPrice {
        Mono<Optional<Price>> read();
    }

    public record Price(BigDecimal usd, Instant asOf) {}

    /** A step that refused; {@code code} is what the order's {@code error} and the client get, the detail stays in the log. */
    static final class StepFailed extends RuntimeException {
        final String code;

        StepFailed(String code, String detail) {
            super(detail, null, false, false);
            this.code = code;
        }
    }

    private record Signed(String signedBase64, String signature, long lastValidBlockHeight) {}

    private final SwapsProperties props;
    private final SwapOrderRepository repo;
    private final SwapOrderService orders;
    private final JupiterSwapClient jupiter;
    private final SolanaRpcClient rpc;
    private final ValidatorClient validator;
    private final SignerClient signer;
    private final PolicyEngine policy;
    private final SolPrice solPrice;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** The order this process is executing right now; the reconcile job leaves it alone. */
    private final AtomicReference<UUID> running = new AtomicReference<>();

    public SwapExecutor(SwapsProperties props, SwapOrderRepository repo, SwapOrderService orders, JupiterSwapClient jupiter, SolanaRpcClient rpc,
            ValidatorClient validator, SignerClient signer, PolicyEngine policy, SolPrice solPrice, MeterRegistry meters, Clock clock) {
        this.props = props;
        this.repo = repo;
        this.orders = orders;
        this.jupiter = jupiter;
        this.rpc = rpc;
        this.validator = validator;
        this.signer = signer;
        this.policy = policy;
        this.solPrice = solPrice;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- execute --------------------------------------------------------------------------------------

    /** Only the approver may execute. Returns the order as it ended (CONFIRMED, FAILED or UNKNOWN_SENT). */
    public Mono<SwapOrder> execute(CryptobotPrincipal principal, UUID id) {
        if (!props.enabled()) {
            return Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        String identity = orders.approverIdentityRefusal(principal);
        if (identity != null) {
            return Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.FORBIDDEN, identity));
        }
        return Mono.defer(() -> {
            if (!busy.compareAndSet(false, true)) {
                return Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.CONFLICT, "executor_busy"));
            }
            running.set(id);
            Mono<SwapOrder> work = claim(principal, id).flatMap(this::run)
                    .doFinally(s -> {
                        running.set(null);
                        busy.set(false);
                    })
                    .cache();
            // the run is subscribed here, not by the caller: a caller that goes away does not cancel it halfway
            work.subscribe(o -> { }, ex -> {
                if (!(ex instanceof SwapOrderService.SwapRefused)) {
                    log.warn("swap_order_execution_error id={} error={}", id, ex.getClass().getSimpleName());
                }
            });
            return work;
        });
    }

    private Mono<SwapOrder> claim(CryptobotPrincipal principal, UUID id) {
        Instant now = clock.instant();
        return repo.find(id)
                .switchIfEmpty(Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.NOT_FOUND, "order_not_found")))
                .flatMap(order -> {
                    if (order.status() != SwapOrder.Status.APPROVED) {
                        return Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.CONFLICT, "not_approved"));
                    }
                    if (!now.isBefore(order.expiresAt())) {
                        return repo.transition(order.id(), SwapOrder.Status.APPROVED, SwapOrder.Status.EXPIRED, "execution window elapsed")
                                .flatMap(e -> repo.audit(e.id(), "EXPIRED", null, e.previewHash(), null).doOnSuccess(v -> count(e.status())))
                                .then(Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.CONFLICT, "execution_expired")));
                    }
                    return repo.transition(order.id(), SwapOrder.Status.APPROVED, SwapOrder.Status.EXECUTING, null)
                            .switchIfEmpty(Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.CONFLICT, "not_approved")))
                            .flatMap(e -> repo.audit(e.id(), "EXECUTING", principal.userId(), e.previewHash(), null).thenReturn(e))
                            .doOnNext(e -> count(e.status()));
                });
    }

    private Mono<SwapOrder> run(SwapOrder order) {
        SolanaCluster cluster = SolanaCluster.parse(order.cluster());
        Mono<Object> signedOrFailed = prepare(order, cluster)
                .flatMap(tx -> attestAndSign(order, cluster, tx))
                .flatMap(signed -> repo.markSigned(order.id(), signed.signature(), signed.lastValidBlockHeight())
                        .switchIfEmpty(Mono.error(new StepFailed("signature_not_recorded", "order left EXECUTING before the signature was stored")))
                        .flatMap(s -> repo.audit(s.id(), "SIGNED", null, s.previewHash(), "signature=" + signed.signature()).thenReturn(signed)))
                .<Object>map(signed -> signed)
                // up to here nothing can be on the chain: any error is FAILED. Errors after the broadcast never reach this.
                .onErrorResume(ex -> fail(order, SwapOrder.Status.EXECUTING, ex instanceof StepFailed f ? f.code : "execution_failed",
                                ex instanceof StepFailed ? ex.getMessage() : ex.getClass().getSimpleName())
                        .map(failed -> failed));
        return signedOrFailed.flatMap(x -> x instanceof Signed signed ? broadcast(order, cluster, signed) : Mono.just((SwapOrder) x))
                .switchIfEmpty(Mono.defer(() -> repo.find(order.id())));
    }

    /** Re-quote within the approval's binding, build, check and simulate. */
    private Mono<PreparedTransaction> prepare(SwapOrder order, SolanaCluster cluster) {
        if (!policy.properties().executionEnabled()) {
            return Mono.error(new StepFailed("emergency_stop", "cryptobot.policy.execution-enabled=false"));
        }
        if (!order.cluster().equals(props.cluster())) {
            return Mono.error(new StepFailed("cluster_changed", "order for " + order.cluster() + ", swaps now run on " + props.cluster()));
        }
        return signer.identity()
                .flatMap(id -> {
                    if (id.isEmpty() || id.get().publicKey() == null) {
                        return Mono.error(new StepFailed("signer_unavailable", "no signer identity"));
                    }
                    if (!order.owner().equals(id.get().publicKey())) {
                        return Mono.error(new StepFailed("signer_changed", "order owner " + order.owner() + ", signer now " + id.get().publicKey()));
                    }
                    String signerCluster = id.get().cluster();
                    if (signerCluster != null && !signerCluster.isBlank() && !signerCluster.equalsIgnoreCase(order.cluster())) {
                        return Mono.error(new StepFailed("signer_cluster_mismatch", "signer signs for " + signerCluster));
                    }
                    return jupiter.quote(order.direction().inputMint(), order.direction().outputMint(), order.inAmount(), order.maxSlippageBps());
                })
                .flatMap(quote -> {
                    String broken = binding(order, quote);
                    if (broken != null) {
                        return Mono.error(new StepFailed(broken, "quote in=" + quote.inAmount() + " min_out=" + quote.minOutAmount()
                                + " order in=" + order.inAmount() + " min_out=" + order.minOut()));
                    }
                    return jupiter.buildSwap(quote, order.owner());
                })
                .flatMap(swap -> {
                    if (swap.prioritizationFeeLamports() > props.maxPriorityFeeLamports()) {
                        return Mono.error(new StepFailed("priority_fee_too_high", swap.prioritizationFeeLamports() + " > " + props.maxPriorityFeeLamports()));
                    }
                    try {
                        UnsignedSwapCheck.check(swap.transactionBase64(), order.owner());
                    } catch (SwapRejectedException ex) {
                        return Mono.error(new StepFailed("transaction_rejected", ex.getMessage()));
                    }
                    return rpc.simulateTransaction(cluster, swap.transactionBase64(), false)
                            .flatMap(sim -> sim.ok() ? Mono.just(prepared(order, swap))
                                    : Mono.error(new StepFailed("simulation_failed", String.valueOf(sim.error()))));
                });
    }

    /** null = the new quote is the approved swap: same mints, same input, a minimum at least the approved one. */
    static String binding(SwapOrder order, SwapQuote quote) {
        if (!order.direction().inputMint().equals(quote.inputMint()) || !order.direction().outputMint().equals(quote.outputMint())) {
            return "quote_mints_changed";
        }
        if (quote.inAmount() != order.inAmount()) {
            return "quote_amount_changed";
        }
        if (quote.minOutAmount() < order.minOut()) {
            return "price_moved";
        }
        return null;
    }

    /** The legacy transaction as the validator and the signer take it; the message is what follows its one empty signature. */
    static PreparedTransaction prepared(SwapOrder order, UnsignedSwap swap) {
        byte[] wire = Base64.getDecoder().decode(swap.transactionBase64());
        byte[] message = Arrays.copyOfRange(wire, 1 + 64, wire.length);
        return new PreparedTransaction(order.cluster(), order.owner(), null, 0L, null, swap.lastValidBlockHeight(), swap.transactionBase64(),
                Base64.getEncoder().encodeToString(message), "jupiter swap " + order.direction() + " in=" + order.inAmount() + " min_out=" + order.minOut());
    }

    private Mono<Signed> attestAndSign(SwapOrder order, SolanaCluster cluster, PreparedTransaction tx) {
        Instant now = clock.instant();
        return Mono.zip(solPrice.read(), repo.spentSince(now.minus(Duration.ofHours(24))).filter(o -> !o.id().equals(order.id())).collectList())
                .flatMap(ctx -> {
                    PolicyInput input = facts(order, ctx.getT1(), ctx.getT2(), now);
                    PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(policy.rules(), input);
                    if (!acceptable(verdict)) {
                        return Mono.error(new StepFailed("policy_denied", verdict.decision() + " " + verdict.escalation() + " failed=" + verdict.failedPredicates()));
                    }
                    ValidatorClient.SwapIntent intent = new ValidatorClient.SwapIntent(order.owner(), order.direction().inputMint(),
                            order.direction().outputMint(), order.inAmount(), order.minOut(), order.maxSlippageBps(), props.maxPriorityFeeLamports());
                    return validator.authorizeSwap(order.id(), verdict, input, tx, intent)
                            .onErrorMap(ValidatorClient.ValidatorRefused.class, ex -> new StepFailed("validator_refused", httpStatusOnly(ex.getMessage())));
                })
                .flatMap(att -> signer.signSwap(order.id(), tx.unsignedTransactionBase64(), cluster, order.minOut(), att.attestation())
                        .onErrorMap(SignerClient.SignerRefused.class, ex -> new StepFailed("signer_refused", httpStatusOnly(ex.getMessage()))))
                .map(resp -> verifySigned(tx, resp, order.owner()));
    }

    /** What of a validator or signer refusal reaches the log: its HTTP status, never the body it sent back. */
    static String httpStatusOnly(String message) {
        if (message != null && message.matches("^HTTP \\d{3}\\b.*")) {
            return message.substring(0, 8);
        }
        return "refused";
    }

    /** ALLOW, or ESCALATE for a human signature — which this order carries (its approval). */
    static boolean acceptable(PolicyVerdict v) {
        return v.allowed() || (v.decision() == PolicyVerdict.Decision.ESCALATE && v.escalation() == PolicyVerdict.Escalation.REQUIRE_HUMAN_SIGNATURE);
    }

    /**
     * {@code (I, S)} of one swap. The asset is what it spends; its value in cents comes from the SOL price (USDC at
     * par). Exposure is what the other swaps of the last 24 h that may have moved funds spent (EXECUTING, SENT,
     * UNKNOWN_SENT, CONFIRMED). Unknown price ⇒ value unknown ⇒ DENY.
     *
     * <p>Two state facts are fixed for the pilot, as for PoV payouts: {@code nonce_unused} is true because the claim
     * (APPROVED → EXECUTING, conditional) is the nonce being spent — a second run of the same order never gets here; and
     * {@code asset_exposure_after_bps} is 0 because the pilot holds no portfolio to weigh (one wallet, swaps capped at
     * 0.1 SOL / 10 USDC by the policy's {@code pilot_swap} section and again by the signer).
     */
    PolicyInput facts(SwapOrder order, Optional<Price> sol, List<SwapOrder> others, Instant now) {
        Long tradeCents = cents(order, sol);
        Long exposureCents = 0L;
        for (SwapOrder o : others) {
            Long c = cents(o, sol);
            exposureCents = c == null || exposureCents == null ? null : exposureCents + c;
        }
        Long oracleAge = sol.map(p -> p.asOf().isAfter(now) ? 0L : Duration.between(p.asOf(), now).getSeconds()).orElse(null);
        String asset = order.direction() == SwapOrder.Direction.SOL_USDC ? "SOL" : "USDC";
        return new PolicyInput(
                new PolicyInput.IntentFacts(AGENT, STRATEGY, policy.rules().version(), asset, tradeCents, order.maxSlippageBps(),
                        now.getEpochSecond() + VALID_FOR_SECONDS),
                new PolicyInput.StateFacts(exposureCents, 0, oracleAge, props.enabled(), true, now.getEpochSecond()));
    }

    /** What an order spends, in cents, rounded up. SOL needs the price; a USDC unit is 1e-6 USD. */
    static Long cents(SwapOrder o, Optional<Price> sol) {
        if (o.direction() == SwapOrder.Direction.USDC_SOL) {
            return BigDecimal.valueOf(o.inAmount()).movePointLeft(4).setScale(0, RoundingMode.CEILING).longValueExact();
        }
        return sol.map(p -> BigDecimal.valueOf(o.inAmount()).multiply(p.usd()).movePointLeft(9).movePointRight(2).setScale(0, RoundingMode.CEILING)
                .longValueExact()).orElse(null);
    }

    /** Defence in depth: the signer's output must be the message we sent, signed by the owner's key. */
    static Signed verifySigned(PreparedTransaction tx, SignerClient.SignResponse resp, String owner) {
        byte[] wire;
        try {
            wire = Base64.getDecoder().decode(resp.signedTransactionBase64());
        } catch (RuntimeException ex) {
            throw new StepFailed("signature_invalid", "signer returned no base64");
        }
        byte[] message = Base64.getDecoder().decode(tx.messageBase64());
        if (wire.length != 1 + 64 + message.length || wire[0] != 1) {
            throw new StepFailed("signature_invalid", "signer returned a transaction with an unexpected shape");
        }
        byte[] signature = Arrays.copyOfRange(wire, 1, 65);
        if (!Arrays.equals(message, Arrays.copyOfRange(wire, 65, wire.length))) {
            throw new StepFailed("signature_invalid", "signer altered the message");
        }
        if (!SolanaKeypair.verify(Base58.decode(owner), message, signature)) {
            throw new StepFailed("signature_invalid", "signature does not verify against the owner");
        }
        // the transaction id IS the first signature: known before anyone broadcasts it
        return new Signed(resp.signedTransactionBase64(), Base58.encode(signature), tx.lastValidBlockHeight());
    }

    private Mono<SwapOrder> broadcast(SwapOrder order, SolanaCluster cluster, Signed signed) {
        if (!clock.instant().isBefore(order.expiresAt())) {
            // signed but not sent: the approval's window closed while the validator and the signer answered
            return fail(order, SwapOrder.Status.EXECUTING, "execution_window_elapsed", "signed, not sent: window closed at " + order.expiresAt());
        }
        return rpc.sendTransaction(cluster, signed.signedBase64())
                .flatMap(answered -> answered.equals(signed.signature())
                        ? move(order, SwapOrder.Status.EXECUTING, SwapOrder.Status.SENT, null, "SENT", "signature=" + signed.signature())
                        // the RPC acknowledged a different transaction id: we cannot tell what it sent
                        : move(order, SwapOrder.Status.EXECUTING, SwapOrder.Status.UNKNOWN_SENT, "signature_mismatch", "UNKNOWN_SENT",
                                "expected " + signed.signature() + " rpc answered " + answered).then(Mono.empty()))
                .onErrorResume(ex -> !(ex instanceof SwapOrderService.SwapRefused), ex -> {
                    // a JSON-RPC rejection (or the mainnet gate) is certain: nothing landed. Anything else may have.
                    boolean certain = (ex instanceof SolanaRpcException r && r.getCause() == null) || ex instanceof MainnetDisabledException;
                    return certain ? fail(order, SwapOrder.Status.EXECUTING, ex instanceof MainnetDisabledException ? "mainnet_disabled" : "broadcast_rejected",
                                    ex.getMessage()).then(Mono.empty())
                            : move(order, SwapOrder.Status.EXECUTING, SwapOrder.Status.UNKNOWN_SENT, "broadcast_uncertain", "UNKNOWN_SENT",
                                    ex.getClass().getSimpleName()).then(Mono.empty());
                })
                .flatMap(sent -> confirm(cluster, signed.signature())
                        .flatMap(status -> settle(sent, status, SwapOrder.Status.SENT)));
    }

    /** {@code confirmed} or {@code finalized}, or a failure the chain reported; "pending" when the timeout passed first. */
    private Mono<SolanaRpcClient.SignatureStatus> confirm(SolanaCluster cluster, String signature) {
        long attempts = Math.max(1, props.confirmTimeout().toMillis() / props.confirmPollInterval().toMillis());
        return rpc.getSignatureStatus(cluster, signature)
                .flatMap(s -> known(s) ? Mono.just(s) : Mono.<SolanaRpcClient.SignatureStatus>error(new Pending()))
                .retryWhen(Retry.fixedDelay(attempts, props.confirmPollInterval()).filter(Pending.class::isInstance))
                .onErrorResume(ex -> Mono.just(new SolanaRpcClient.SignatureStatus(signature, "pending", false, null)));
    }

    private static boolean known(SolanaRpcClient.SignatureStatus s) {
        return s.failed() || "confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus());
    }

    private Mono<SwapOrder> settle(SwapOrder order, SolanaRpcClient.SignatureStatus status, SwapOrder.Status from) {
        if (status.failed()) {
            return fail(order, from, "onchain_failed", status.error());
        }
        if ("confirmed".equals(status.confirmationStatus()) || "finalized".equals(status.confirmationStatus())) {
            return move(order, from, SwapOrder.Status.CONFIRMED, null, "CONFIRMED", status.confirmationStatus());
        }
        return from == SwapOrder.Status.UNKNOWN_SENT ? repo.find(order.id())
                : move(order, from, SwapOrder.Status.UNKNOWN_SENT, "not_confirmed_in_time", "UNKNOWN_SENT", "status=" + status.confirmationStatus());
    }

    // ---- reconcile -------------------------------------------------------------------------------------

    /** The creator or the approver asks where an order really is. See {@link #reconcileOrder}. */
    public Mono<SwapOrder> reconcile(CryptobotPrincipal principal, UUID id) {
        if (!props.enabled()) {
            return Mono.error(new SwapOrderService.SwapRefused(SwapOrderService.SwapRefused.Kind.NOT_FOUND, "swaps_disabled"));
        }
        return orders.view(principal, id).map(SwapOrderService.View::order).flatMap(order -> reconcileOrder(order, principal.userId()));
    }

    /**
     * SENT / UNKNOWN_SENT / EXECUTING-with-signature: the chain decides — CONFIRMED, FAILED (it failed, or its blockhash
     * expired without it landing), or unchanged while it does not know. EXECUTING without a signature past its window:
     * never broadcast, FAILED. Never sends anything. {@code actor} is null for {@link SwapReconcileJob}.
     */
    Mono<SwapOrder> reconcileOrder(SwapOrder order, UUID actor) {
        SolanaCluster cluster = SolanaCluster.parse(order.cluster());
        boolean inFlight = order.status() == SwapOrder.Status.SENT || order.status() == SwapOrder.Status.UNKNOWN_SENT
                || order.status() == SwapOrder.Status.EXECUTING;
        if (!inFlight) {
            return Mono.just(order);
        }
        if (order.txSignature() == null) {
            if (order.status() == SwapOrder.Status.EXECUTING && !clock.instant().isBefore(order.expiresAt())) {
                return fail(order, SwapOrder.Status.EXECUTING, "abandoned_before_signing", "no signature stored: never broadcast");
            }
            return Mono.just(order);
        }
        // the height first: a transaction can only land at or below its last valid height, so if the chain is already
        // past it and the status (read after) does not know the signature, it never landed and never will
        Mono<Boolean> expired = repo.lastValidBlockHeight(order.id())
                .flatMap(lvb -> rpc.getBlockHeight(cluster).map(h -> h > lvb))
                .defaultIfEmpty(false);
        return expired.flatMap(past -> rpc.getSignatureStatus(cluster, order.txSignature()).flatMap(s -> {
                    if (known(s)) {
                        return repo.audit(order.id(), "RECONCILED", actor, order.previewHash(), "status=" + s.confirmationStatus())
                                .then(settle(order, s, order.status()));
                    }
                    if (past) {
                        return repo.audit(order.id(), "RECONCILED", actor, order.previewHash(), "blockhash expired, signature unknown")
                                .then(fail(order, order.status(), "blockhash_expired", "past last valid block height, signature unknown"));
                    }
                    // EXECUTING with a signature: signed, maybe sent, the chain does not know yet
                    return order.status() == SwapOrder.Status.EXECUTING
                            ? move(order, SwapOrder.Status.EXECUTING, SwapOrder.Status.UNKNOWN_SENT, "not_confirmed_in_time", "UNKNOWN_SENT",
                                    "reconcile status=" + s.confirmationStatus())
                            : Mono.just(order);
                }))
                .onErrorResume(ex -> !(ex instanceof SwapOrderService.SwapRefused), ex -> {
                    log.warn("swap_order_reconcile_unavailable id={} error={}", order.id(), ex.getClass().getSimpleName());
                    return Mono.just(order);
                });
    }

    /** The order this process is executing, if any (the job must not reconcile it under the executor's feet). */
    UUID running() {
        return running.get();
    }

    // ---- transitions -----------------------------------------------------------------------------------

    private Mono<SwapOrder> fail(SwapOrder order, SwapOrder.Status from, String code, String detail) {
        log.warn("swap_order_failed id={} code={} detail={}", order.id(), code, detail);
        return move(order, from, SwapOrder.Status.FAILED, code, "FAILED", code);
    }

    /** {@code from} → {@code to} with an audit row; if the order was no longer in {@code from}, it is returned as it is. */
    private Mono<SwapOrder> move(SwapOrder order, SwapOrder.Status from, SwapOrder.Status to, String error, String event, String detail) {
        return repo.transition(order.id(), from, to, error)
                .flatMap(m -> repo.audit(m.id(), event, null, m.previewHash(), detail).thenReturn(m))
                .doOnNext(m -> {
                    count(m.status());
                    log.info("swap_order_{} id={} signature={} detail={}", to.name().toLowerCase(java.util.Locale.ROOT), m.id(), m.txSignature(), detail);
                })
                .switchIfEmpty(Mono.defer(() -> repo.find(order.id())));
    }

    private void count(SwapOrder.Status status) {
        meters.counter(SwapOrderService.METRIC_ORDERS, "status", status.name()).increment();
    }

    private static final class Pending extends RuntimeException {
        Pending() {
            super("pending", null, false, false);
        }
    }
}
