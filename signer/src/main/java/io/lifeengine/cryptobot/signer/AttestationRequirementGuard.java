package io.lifeengine.cryptobot.signer;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * KAN-493 — {@code SIGNER_REQUIRE_ATTESTATION=false} is not a production setting. It is accepted
 * only when the Spring profile {@code local} or {@code test} is active; under any other profile
 * (including none) the signer refuses to start, so the level-5 gate cannot be switched off by an
 * environment variable on a deployed instance.
 */
@Component
public class AttestationRequirementGuard {

    private static final Logger log = LoggerFactory.getLogger(AttestationRequirementGuard.class);
    static final Profiles PERMITTED = Profiles.of("local", "test");

    public AttestationRequirementGuard(SignerProperties props, Environment env) {
        if (props.requireAttestation()) {
            return;
        }
        if (!env.acceptsProfiles(PERMITTED)) {
            throw new IllegalStateException(message(env.getActiveProfiles()));
        }
        log.warn("signer_attestation_not_required profiles={} — accepted only because a local/test profile is active",
                Arrays.toString(env.getActiveProfiles()));
    }

    static String message(String[] activeProfiles) {
        return "SIGNER_REQUIRE_ATTESTATION=false (signer.require-attestation) is only allowed under the Spring profile 'local' or 'test'; "
                + "active profiles: " + Arrays.toString(activeProfiles) + ". Remove the variable, or set SPRING_PROFILES_ACTIVE=local for a demo signer.";
    }
}
