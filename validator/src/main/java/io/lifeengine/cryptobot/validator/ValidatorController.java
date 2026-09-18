package io.lifeengine.cryptobot.validator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
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
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token")));
        }
        return Mono.just(ResponseEntity.ok(new Identity(keys.publicKey(), policy.rules().version(), policy.hash(), policy.pinned(), props.enabled(),
                props.attestationTtl().toSeconds())));
    }

    @PostMapping(path = "/validate", consumes = "application/json")
    public Mono<ResponseEntity<?>> validate(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody ValidationService.Request req) {
        if (!authorized(token)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reason", "bad_token")));
        }
        try {
            return Mono.just(ResponseEntity.ok(validation.validate(req)));
        } catch (ValidationService.MalformedRequest e) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("reason", e.getMessage())));
        }
    }

    private boolean authorized(String presented) {
        String expected = props.token();
        if (expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
