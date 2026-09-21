package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.observability.ErrorCode;
import io.lifeengine.cryptobot.signer.observability.LogContext;
import io.lifeengine.cryptobot.signer.observability.LogFields;
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
     * {@code cluster} (KAN-493): the cluster the bytes are for; mainnet is refused unless {@code signer.allow-mainnet=true}.
     * {@code attestation} (KAN-438): the validator's signed payload; required unless {@code signer.require-attestation=false}.
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

    public SignerController(SignerProperties props, SignerKeyStore keys, SigningPolicy policy, AttestationVerifier attestations) {
        this.props = props;
        this.keys = keys;
        this.policy = policy;
        this.attestations = attestations;
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
        // KAN-573: el trabajo corre dentro de la cadena reactiva para que proposalId esté en el MDC de
        // cada línea (LogContext); con Mono.just(...) el log saldría antes de que exista el Context.
        return Mono.<ResponseEntity<?>>fromCallable(() -> doSign(token, req))
                .contextWrite(ctx -> LogContext.write(ctx, LogContext.PROPOSAL_ID, req == null ? null : req.proposalId()));
    }

    private ResponseEntity<?> doSign(String token, SignRequest req) {
        if (!authorized(token)) {
            return badToken("sign");
        }
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            log.warn("signer_bad_request reason=missing_transaction", LogFields.event("sign_refused"), LogFields.status(400), ErrorCode.HTTP_400.kv());
            return ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction"));
        }
        SigningPolicy.Verdict v = policy.evaluate(req.unsignedTransactionBase64(), req.expectedFeePayer(), req.cluster());
        if (!v.allowed()) {
            log.warn("signer_refused proposalId={} cluster={} reason={}", req.proposalId(), req.cluster(), v.reason(),
                    LogFields.event("sign_refused"), LogFields.status("refused"), ErrorCode.SIGN_REFUSED.kv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason()));
        }
        byte[] message = v.decoded().message();
        // Level 5 (paper §20): the bytes that passed our own limits must also have been attested by
        // the independent validator — for this proposal, these exact bytes, for this cluster, and not as a DENY.
        AttestationVerifier.Verdict a = attestations.verify(req.attestation(), req.proposalId(), message, req.cluster());
        if (!a.ok()) {
            log.warn("signer_refused proposalId={} reason={}", req.proposalId(), a.reason(),
                    LogFields.event("sign_refused"), LogFields.status("refused"), ErrorCode.ATTESTATION_REFUSED.kv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", a.reason()));
        }
        SignResponse signed = sign(message, a.validator(), a.verdictHash());
        log.info("signer_signed proposalId={} lamports={} destination={} signature={} validator={} decision={} verdictHash={}",
                req.proposalId(), v.lamports(), v.destination(), signed.signature(), a.validator(), a.decision(), a.verdictHash(),
                LogFields.event("signed"), LogFields.status("signed"));
        return ResponseEntity.ok(signed);
    }

    /**
     * A receipt-batch anchor (KAN-394): the memo transaction must carry exactly {@code root} and {@code receiptCount}; devnet only.
     * No validator attestation: there is no proposal behind it and the memo moves no funds (KAN-438 keeps the gate on {@code /sign}).
     */
    public record AnchorSignRequest(String root, int receiptCount, String unsignedTransactionBase64, String expectedFeePayer) {}

    @PostMapping(path = "/sign-anchor", consumes = "application/json")
    public Mono<ResponseEntity<?>> signAnchor(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody AnchorSignRequest req) {
        if (!authorized(token)) {
            return Mono.just(badToken("sign-anchor"));
        }
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction")));
        }
        SigningPolicy.Verdict v = policy.evaluateAnchor(req.unsignedTransactionBase64(), req.expectedFeePayer(), req.root(), req.receiptCount());
        if (!v.allowed()) {
            log.warn("signer_anchor_refused root={} reason={}", req.root(), v.reason(),
                    LogFields.event("anchor_refused"), LogFields.status("refused"), ErrorCode.ANCHOR_REFUSED.kv());
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason())));
        }
        SignResponse signed = sign(v.decoded().message(), null, null);
        log.info("signer_anchor_signed root={} receipts={} signature={}", req.root(), req.receiptCount(), signed.signature(),
                LogFields.event("anchor_signed"), LogFields.status("signed"));
        return Mono.just(ResponseEntity.ok(signed));
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
}
