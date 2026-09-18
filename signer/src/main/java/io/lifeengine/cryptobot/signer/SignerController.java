package io.lifeengine.cryptobot.signer;

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

    public record SignRequest(String proposalId, String unsignedTransactionBase64, String expectedFeePayer) {}

    public record SignResponse(String signedTransactionBase64, String signer, String txHash, String signature) {}

    public record Identity(String publicKey, String cluster, long maxLamports, List<String> allowedDestinations, boolean enabled) {}

    private final SignerProperties props;
    private final SignerKeyStore keys;
    private final SigningPolicy policy;

    public SignerController(SignerProperties props, SignerKeyStore keys, SigningPolicy policy) {
        this.props = props;
        this.keys = keys;
        this.policy = policy;
    }

    @GetMapping("/identity")
    public Mono<ResponseEntity<?>> identity(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token")));
        }
        return Mono.just(ResponseEntity.ok(new Identity(keys.publicKey(), props.cluster(), props.maxLamports(), props.allowedDestinations(), props.enabled())));
    }

    @PostMapping(path = "/sign", consumes = "application/json")
    public Mono<ResponseEntity<?>> sign(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody SignRequest req) {
        if (!authorized(token)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token")));
        }
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction")));
        }
        SigningPolicy.Verdict v = policy.evaluate(req.unsignedTransactionBase64(), req.expectedFeePayer());
        if (!v.allowed()) {
            log.warn("signer_refused proposalId={} reason={}", req.proposalId(), v.reason());
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason())));
        }
        SignResponse signed = sign(v.decoded().message());
        log.info("signer_signed proposalId={} lamports={} destination={} signature={}", req.proposalId(), v.lamports(), v.destination(), signed.signature());
        return Mono.just(ResponseEntity.ok(signed));
    }

    /** A receipt-batch anchor (KAN-394): the memo transaction must carry exactly {@code root} and {@code receiptCount}; devnet only. */
    public record AnchorSignRequest(String root, int receiptCount, String unsignedTransactionBase64, String expectedFeePayer) {}

    @PostMapping(path = "/sign-anchor", consumes = "application/json")
    public Mono<ResponseEntity<?>> signAnchor(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody AnchorSignRequest req) {
        if (!authorized(token)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token")));
        }
        if (req == null || req.unsignedTransactionBase64() == null || req.unsignedTransactionBase64().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("reason", "missing_transaction")));
        }
        SigningPolicy.Verdict v = policy.evaluateAnchor(req.unsignedTransactionBase64(), req.expectedFeePayer(), req.root(), req.receiptCount());
        if (!v.allowed()) {
            log.warn("signer_anchor_refused root={} reason={}", req.root(), v.reason());
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", v.reason())));
        }
        SignResponse signed = sign(v.decoded().message());
        log.info("signer_anchor_signed root={} receipts={} signature={}", req.root(), req.receiptCount(), signed.signature());
        return Mono.just(ResponseEntity.ok(signed));
    }

    private SignResponse sign(byte[] message) {
        byte[] signature = keys.sign(message);
        byte[] wire = new byte[1 + 64 + message.length];
        wire[0] = 1;
        System.arraycopy(signature, 0, wire, 1, 64);
        System.arraycopy(message, 0, wire, 65, message.length);
        return new SignResponse(Base64.getEncoder().encodeToString(wire), keys.publicKey(), sha256Hex(message), Base58.encode(signature));
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
