package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.observability.ErrorCode;
import io.lifeengine.cryptobot.signer.observability.LogContext;
import io.lifeengine.cryptobot.signer.observability.LogFields;
import io.lifeengine.cryptobot.signer.observability.SignerMetrics;
import io.micrometer.core.instrument.Timer;
import io.lifeengine.cryptobot.signer.solana.Base58;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/signer", produces = "application/json")
public class SignerController {

    private static final Logger log = LoggerFactory.getLogger(SignerController.class);
    public static final String TOKEN_HEADER = "X-Signer-Token";

    /**
     * {@code cluster}: the cluster the bytes are for; mainnet is refused unless {@code signer.allow-mainnet=true}.
     * {@code attestation}: the validator's signed payload; required unless {@code signer.require-attestation=false}.
     */
    public record SignRequest(String proposalId, String unsignedTransactionBase64, String expectedFeePayer, String cluster,
            AttestationVerifier.Attestation attestation) {}

    public record SignResponse(String signedTransactionBase64, String signer, String txHash, String signature, String validator, String verdictHash) {}

    public record Identity(String publicKey, String cluster, long maxLamports, List<String> allowedDestinations, boolean enabled,
            boolean attestationRequired, String validatorPublicKey) {}

    private final SignerProperties props;
    private final SignerKeyStore keys;
    private final SigningPolicy policy;
    private final AttestationVerifier attestations;
    private final SignerMetrics metrics;
    private final DailySpendLedger dailyCap;
    private final SwapProperties swaps;
    private final DailySpendLedger usdcCap;
    /** Both swap caps are checked and reserved under one lock: either both reservations happen or neither does. */
    private final Object swapCaps = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public SignerController(SignerProperties props, SignerKeyStore keys, SigningPolicy policy, AttestationVerifier attestations, SignerMetrics metrics,
            DailySpendLedger dailyCap, SwapProperties swaps, UsdcSpendLedger usdcCap) {
        this.props = props;
        this.swaps = swaps;
        this.usdcCap = usdcCap.ledger();
        this.keys = keys;
        this.policy = policy;
        this.attestations = attestations;
        this.metrics = metrics == null ? SignerMetrics.noop() : metrics;
        this.dailyCap = dailyCap;
    }

    @GetMapping("/identity")
    public Mono<ResponseEntity<?>> identity(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return Mono.just(badToken("identity"));
        }
        return Mono.just(ResponseEntity.ok(new Identity(keys.publicKey(), props.cluster(), props.maxLamports(), props.allowedDestinations(), props.enabled(),
                props.requireAttestation(), props.validatorPublicKey())));
    }

    @PostMapping(path = "/sign", consumes = "application/json")
    public Mono<ResponseEntity<?>> sign(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody SignRequest req) {
        // el trabajo corre dentro de la cadena reactiva para que proposalId esté en el MDC de
        // cada línea (LogContext); con Mono.just(...) el log saldría antes de que exista el Context.
        return Mono.<ResponseEntity<?>>fromCallable(() -> doSign(token, req))
                .contextWrite(ctx -> LogContext.write(ctx, LogContext.PROPOSAL_ID, req == null ? null : req.proposalId()));
    }

    private ResponseEntity<?> doSign(String token, SignRequest req) {
        if (!authorized(token)) {
            return badToken("sign");
        }
        // the signer's own series — signed vs refused-by-rule — and the time it took, so the
        // dashboard's "firmados" reads a real counter instead of a derived value.
        Timer.Sample sample = metrics.start();
        try {
            return signTransfer(req);
        } finally {
            metrics.stop(SignerMetrics.KIND_TRANSFER, sample);
        }
    }

    private ResponseEntity<?> signTransfer(SignRequest req) {
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            metrics.refused(SignerMetrics.KIND_TRANSFER, "missing_transaction");
            log.warn("signer_bad_request reason=missing_transaction", LogFields.event("sign_refused"), LogFields.status(400), ErrorCode.HTTP_400.kv());
            return ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction"));
        }
        SigningPolicy.Verdict v = policy.evaluate(req.unsignedTransactionBase64(), req.expectedFeePayer(), req.cluster());
        if (!v.allowed()) {
            metrics.refused(SignerMetrics.KIND_TRANSFER, v.reason());
            log.warn("signer_refused proposalId={} cluster={} reason={}", req.proposalId(), req.cluster(), v.reason(),
                    LogFields.event("sign_refused"), LogFields.status("refused"), ErrorCode.SIGN_REFUSED.kv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason()));
        }
        byte[] message = v.decoded().message();
        // Level 5 (paper §20): the bytes that passed our own limits must also have been attested by
        // the independent validator — for this proposal, these exact bytes, for this cluster, and not as a DENY.
        AttestationVerifier.Verdict a = attestations.verify(req.attestation(), req.proposalId(), message, req.cluster(),
                AttestationVerifier.requiredChecks(v.decoded()));
        if (!a.ok()) {
            metrics.refused(SignerMetrics.KIND_TRANSFER, a.reason());
            log.warn("signer_refused proposalId={} reason={}", req.proposalId(), a.reason(),
                    LogFields.event("sign_refused"), LogFields.status("refused"), ErrorCode.ATTESTATION_REFUSED.kv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", a.reason()));
        }
        // KAN-602: the daily velocity cap, reserved last so that a refused request never uses budget.
        String capRefusal = reserveDaily(v.lamports());
        if (capRefusal != null) {
            metrics.refused(SignerMetrics.KIND_TRANSFER, capRefusal);
            log.warn("signer_refused proposalId={} reason={} lamports={} spent_24h={} max_per_day={}", req.proposalId(), capRefusal, v.lamports(),
                    dailyCap.spentInWindow(), dailyCap.maxPerDay(), LogFields.event("sign_refused"), LogFields.status("refused"),
                    ErrorCode.SIGN_REFUSED.kv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", capRefusal));
        }
        SignResponse signed = sign(message, a.validator(), a.verdictHash());
        metrics.signed(SignerMetrics.KIND_TRANSFER);
        log.info("signer_signed proposalId={} lamports={} destination={} signature={} validator={} decision={} verdictHash={}",
                req.proposalId(), v.lamports(), v.destination(), signed.signature(), a.validator(), a.decision(), a.verdictHash(),
                LogFields.event("signed"), LogFields.status("signed"));
        return ResponseEntity.ok(signed);
    }

    /**
     * The pilot's swap ({@code signer.swaps.enabled}, off by default). The bytes must pass {@link SwapSigningPolicy}
     * with this signer as owner, carry an attestation whose {@code checks} include both
     * {@code message_hash_from_bytes} and {@code jupiter_swap_v1} for the hash of exactly these bytes, and fit the
     * daily cap, which reserves {@link SwapSigningPolicy.Verdict#lamportsOut()} before anything is signed. A
     * reservation is never released: the signer does not broadcast, so it cannot prove a send failed, and counting a
     * swap that never landed is the safe side.
     */
    /**
     * {@code minOut}: the intent's minimum output (base units of the output mint); missing or {@code <= 0} is refused,
     * and it must equal the {@code swap.min_out_amount} the validator signed.
     */
    public record SwapSignRequest(String proposalId, String unsignedTransactionBase64, String cluster, Long minOut,
            AttestationVerifier.Attestation attestation) {}

    static final java.util.Set<String> SWAP_CHECKS = java.util.Set.of(AttestationVerifier.CHECK_HASH_FROM_BYTES, AttestationVerifier.CHECK_JUPITER_SWAP);

    @PostMapping(path = "/sign-swap", consumes = "application/json")
    public Mono<ResponseEntity<?>> signSwap(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody SwapSignRequest req) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    if (!authorized(token)) {
                        return badToken("sign-swap");
                    }
                    Timer.Sample sample = metrics.start();
                    try {
                        return doSignSwap(req);
                    } finally {
                        metrics.stop(SignerMetrics.KIND_SWAP, sample);
                    }
                })
                .contextWrite(ctx -> LogContext.write(ctx, LogContext.PROPOSAL_ID, req == null ? null : req.proposalId()));
    }

    private ResponseEntity<?> doSignSwap(SwapSignRequest req) {
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            return refuseSwap(req, "missing_transaction", HttpStatus.BAD_REQUEST);
        }
        if (!props.enabled()) {
            return refuseSwap(req, "signer_disabled", HttpStatus.FORBIDDEN);
        }
        if (!swaps.enabled()) {
            return refuseSwap(req, "swaps_disabled", HttpStatus.FORBIDDEN);
        }
        // a swap is never signed on the token alone, whatever the profile allows for the legacy transfer path
        if (!attestations.required()) {
            return refuseSwap(req, "attestation_not_required", HttpStatus.FORBIDDEN);
        }
        SigningPolicy.Verdict cluster = policy.evaluateCluster(req.cluster());
        if (!cluster.allowed()) {
            return refuseSwap(req, cluster.reason(), HttpStatus.FORBIDDEN);
        }
        byte[] wire;
        try {
            wire = Base64.getDecoder().decode(req.unsignedTransactionBase64());
        } catch (IllegalArgumentException ex) {
            return refuseSwap(req, "undecodable_transaction", HttpStatus.FORBIDDEN);
        }
        SwapSigningPolicy.Verdict v = SwapSigningPolicy.evaluate(wire, keys.publicKey(), swaps.limits(), req.minOut() == null ? 0 : req.minOut());
        if (!v.allowed()) {
            return refuseSwap(req, v.reason(), HttpStatus.FORBIDDEN);
        }
        // the hash is recomputed here over the exact message about to be signed, and both checks are required
        byte[] message = v.decoded().message();
        AttestationVerifier.Verdict a = attestations.verify(req.attestation(), req.proposalId(), message, req.cluster(), SWAP_CHECKS);
        if (!a.ok()) {
            return refuseSwap(req, a.reason(), HttpStatus.FORBIDDEN);
        }
        // the exact minimum is the attested one: the validator signed the swap (mints, amount, minimum), and it has to
        // be the swap in these bytes and the minimum this request asked the policy to enforce
        java.util.Optional<AttestationVerifier.AttestedSwap> attested = attestations.swapOf(req.attestation());
        if (attested.isEmpty()) {
            return refuseSwap(req, "attestation_swap_missing", HttpStatus.FORBIDDEN);
        }
        AttestationVerifier.AttestedSwap s = attested.get();
        if (!s.inputMint().equals(v.inputMint()) || !s.outputMint().equals(v.outputMint()) || s.inAmount() != v.inAmount()
                || req.minOut() == null || s.minOutAmount() != req.minOut()) {
            return refuseSwap(req, "attestation_swap_mismatch", HttpStatus.FORBIDDEN);
        }
        String capRefusal = reserveSwap(v);
        if (capRefusal != null) {
            return refuseSwap(req, capRefusal, HttpStatus.FORBIDDEN);
        }
        SignResponse signed = sign(message, a.validator(), a.verdictHash());
        metrics.signed(SignerMetrics.KIND_SWAP);
        log.info("signer_swap_signed proposalId={} cluster={} input={} output={} in_amount={} lamports_out={} spent_24h={} signature={} validator={} verdictHash={}",
                req.proposalId(), req.cluster(), v.inputMint(), v.outputMint(), v.inAmount(), v.lamportsOut(), dailyCap.spentInWindow(),
                signed.signature(), a.validator(), a.verdictHash(), LogFields.event("swap_signed"), LogFields.status("signed"));
        return ResponseEntity.ok(signed);
    }

    private ResponseEntity<?> refuseSwap(SwapSignRequest req, String reason, HttpStatus status) {
        metrics.refused(SignerMetrics.KIND_SWAP, reason);
        log.warn("signer_swap_refused proposalId={} cluster={} reason={}", req == null ? null : req.proposalId(), req == null ? null : req.cluster(), reason,
                LogFields.event("swap_refused"), LogFields.status("refused"), ErrorCode.SIGN_REFUSED.kv());
        return ResponseEntity.status(status).body(Map.of("reason", reason));
    }

    /**
     * A receipt-batch anchor: the memo transaction must carry exactly {@code root} and {@code receiptCount}; devnet only.
     * No validator attestation: there is no proposal behind it and the memo moves no funds (internal ticket keeps the gate on {@code /sign}).
     */
    public record AnchorSignRequest(String root, int receiptCount, String unsignedTransactionBase64, String expectedFeePayer) {}

    @PostMapping(path = "/sign-anchor", consumes = "application/json")
    public Mono<ResponseEntity<?>> signAnchor(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody AnchorSignRequest req) {
        if (!authorized(token)) {
            return Mono.just(badToken("sign-anchor"));
        }
        Timer.Sample sample = metrics.start();
        try {
            if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
                metrics.refused(SignerMetrics.KIND_ANCHOR, "missing_transaction");
                return Mono.just(ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction")));
            }
            SigningPolicy.Verdict v = policy.evaluateAnchor(req.unsignedTransactionBase64(), req.expectedFeePayer(), req.root(), req.receiptCount());
            if (!v.allowed()) {
                metrics.refused(SignerMetrics.KIND_ANCHOR, v.reason());
                log.warn("signer_anchor_refused root={} reason={}", req.root(), v.reason(),
                        LogFields.event("anchor_refused"), LogFields.status("refused"), ErrorCode.ANCHOR_REFUSED.kv());
                return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason())));
            }
            SignResponse signed = sign(v.decoded().message(), null, null);
            metrics.signed(SignerMetrics.KIND_ANCHOR);
            log.info("signer_anchor_signed root={} receipts={} signature={}", req.root(), req.receiptCount(), signed.signature(),
                    LogFields.event("anchor_signed"), LogFields.status("signed"));
            return Mono.just(ResponseEntity.ok(signed));
        } finally {
            metrics.stop(SignerMetrics.KIND_ANCHOR, sample);
        }
    }

    /** 401 con su línea: el token de servicio es la única puerta y un rechazo tiene que verse en Loki (CB-SIGNER-003). */
    private static ResponseEntity<?> badToken(String operation) {
        log.warn("signer_bad_token operation={}", operation, LogFields.event("auth_rejected"), LogFields.status(401), ErrorCode.BAD_TOKEN.kv());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token"));
    }

    private SignResponse sign(byte[] message, String validator, String verdictHash) {
        byte[] signature = keys.sign(message);
        byte[] wire = new byte[1 + 64 + message.length];
        wire[0] = 1;
        System.arraycopy(signature, 0, wire, 1, 64);
        System.arraycopy(message, 0, wire, 65, message.length);
        return new SignResponse(Base64.getEncoder().encodeToString(wire), keys.publicKey(), sha256Hex(message), Base58.encode(signature),
                validator, verdictHash);
    }

    private boolean authorized(String presented) {
        String expected = props.token();
        if (expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The swap's SOL (lamportsOut) against the lamports cap and, when the input is USDC, its amount against the USDC
     * cap: both checked first, then both reserved, under one lock. null = reserved; a ledger that cannot persist
     * refuses (a reservation already written stays: counting spend that did not happen is the safe side).
     */
    private String reserveSwap(SwapSigningPolicy.Verdict v) {
        boolean usdc = SwapSigningPolicy.USDC_MINT.equals(v.inputMint());
        synchronized (swapCaps) {
            try {
                if (usdc && !usdcCap.canReserve(v.inAmount())) {
                    return "daily_cap_usdc";
                }
                if (!dailyCap.canReserve(v.lamportsOut())) {
                    return "daily_cap";
                }
                if (usdc && !usdcCap.tryReserve(v.inAmount())) {
                    return "daily_cap_usdc";
                }
                return dailyCap.tryReserve(v.lamportsOut()) ? null : "daily_cap";
            } catch (IllegalStateException ex) {
                log.error("signer_spend_ledger_failed error={}", ex.toString());
                return "spend_ledger_unavailable";
            }
        }
    }

    /** null = reserved; otherwise the refusal. A ledger that cannot persist refuses too: fail-closed. */
    private String reserveDaily(long lamports) {
        try {
            return dailyCap.tryReserve(lamports) ? null : "daily_cap";
        } catch (IllegalStateException ex) {
            log.error("signer_spend_ledger_failed error={}", ex.toString());
            return "spend_ledger_unavailable";
        }
    }
}
