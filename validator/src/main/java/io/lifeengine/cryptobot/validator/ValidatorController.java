package io.lifeengine.cryptobot.validator;

import io.lifeengine.cryptobot.validator.observability.ErrorCode;
import io.lifeengine.cryptobot.validator.observability.LogContext;
import io.lifeengine.cryptobot.validator.observability.LogFields;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
@RequestMapping(path = "/api/validator", produces = "application/json")
public class ValidatorController {

    private static final Logger log = LoggerFactory.getLogger(ValidatorController.class);
    public static final String TOKEN_HEADER = "X-Validator-Token";

    public record Identity(String publicKey, String policyVersion, String policyHash, boolean pinned, boolean enabled, long attestationTtlSeconds) {}

    private final ValidatorProperties props;
    private final PolicyStore policy;
    private final AttestationKeyStore keys;
    private final ValidationService validation;

    public ValidatorController(ValidatorProperties props, PolicyStore policy, AttestationKeyStore keys, ValidationService validation) {
        this.props = props;
        this.policy = policy;
        this.keys = keys;
        this.validation = validation;
    }

    @GetMapping("/identity")
    public Mono<ResponseEntity<?>> identity(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return Mono.just(badToken("identity"));
        }
        return Mono.just(ResponseEntity.ok(new Identity(keys.publicKey(), policy.rules().version(), policy.hash(), policy.pinned(), props.enabled(),
                props.attestationTtl().toSeconds())));
    }

    @PostMapping(path = "/validate", consumes = "application/json")
    public Mono<ResponseEntity<?>> validate(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody ValidationService.Request req) {
        // KAN-573: el veredicto corre dentro de la cadena reactiva para que proposalId esté en el MDC de
        // cada línea (LogContext), incluida validator_decision de ValidationService.
        return Mono.<ResponseEntity<?>>fromCallable(() -> doValidate(token, req))
                .contextWrite(ctx -> LogContext.write(ctx, LogContext.PROPOSAL_ID, req == null ? null : req.proposalId()));
    }

    private ResponseEntity<?> doValidate(String token, ValidationService.Request req) {
        if (!authorized(token)) {
            return badToken("validate");
        }
        try {
            ValidationService.Response response = validation.validate(req);
            if (!"ALLOW".equals(response.decision()) && !"ESCALATE".equals(response.decision())) {
                log.warn("validator_denied proposalId={} decision={} refusals={} failed={}", req.proposalId(), response.decision(),
                        response.refusals(), response.failedPredicates(),
                        LogFields.event("validation_denied"), LogFields.status("deny"), ErrorCode.DENIED.kv());
            }
            return ResponseEntity.ok(response);
        } catch (ValidationService.MalformedRequest e) {
            log.warn("validator_malformed reason={}", e.getMessage(), LogFields.event("validation_refused"), LogFields.status(400), ErrorCode.MALFORMED.kv());
            return ResponseEntity.badRequest().body(Map.of("reason", e.getMessage()));
        }
    }

    /** 401 con su línea: el token de servicio es la única puerta y un rechazo tiene que verse en Loki (CB-VALIDATOR-002). */
    private static ResponseEntity<?> badToken(String operation) {
        log.warn("validator_bad_token operation={}", operation, LogFields.event("auth_rejected"), LogFields.status(401), ErrorCode.BAD_TOKEN.kv());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token"));
    }

    private boolean authorized(String presented) {
        String expected = props.token();
        if (expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
