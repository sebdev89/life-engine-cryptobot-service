package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code SIGNER_REQUIRE_ATTESTATION=false} outside the {@code local}/{@code test}
 * profiles is a refusal to start, not a warning. The whole {@link SignerApplication} context is
 * booted (web layer off) so the check is proven where it runs, not in isolation.
 */
class AttestationRequirementGuardTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();

    /** Boots the signer as a deployment would: the settings arrive as environment-level (command-line) properties, above application.yml. */
    private static ConfigurableApplicationContext boot(boolean requireAttestation, String... profiles) {
        SpringApplicationBuilder b = new SpringApplicationBuilder(SignerApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF);
        if (profiles.length > 0) {
            b.profiles(profiles);
        }
        return b.run("--signer.keypair-json=" + SigningPolicyTest.keyJson(KEY), "--signer.token=t", "--signer.require-attestation=" + requireAttestation);
    }

    @Test
    @DisplayName("AC3: require-attestation=false under a non-local profile ⇒ the context fails to start, with the variable named")
    void refusesToStartOutsideLocalOrTest() {
        assertThatThrownBy(() -> boot(false, "prod").close())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNER_REQUIRE_ATTESTATION=false")
                .hasMessageContaining("'local' or 'test'")
                .hasMessageContaining("[prod]");
    }

    @Test
    @DisplayName("no profile at all is not local either")
    void refusesToStartWithNoProfile() {
        assertThatThrownBy(() -> boot(false).close())
                .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("active profiles: []");
    }

    @Test
    @DisplayName("local (or test) may switch the gate off — demo signer, empty wallet")
    void startsUnderLocalOrTest() {
        try (ConfigurableApplicationContext ctx = boot(false, "local")) {
            assertThat(ctx.getBean(AttestationVerifier.class).required()).isFalse();
        }
        try (ConfigurableApplicationContext ctx = boot(false, "test", "something-else")) {
            assertThat(ctx.getBean(AttestationVerifier.class).required()).isFalse();
        }
    }

    @Test
    @DisplayName("the default (attestation required) starts under any profile")
    void attestationRequiredStartsAnywhere() {
        try (ConfigurableApplicationContext ctx = boot(true, "prod")) {
            assertThat(ctx.getBean(AttestationVerifier.class).required()).isTrue();
            assertThat(ctx.getBean(SignerProperties.class).allowMainnet()).as("mainnet flag defaults to false").isFalse();
        }
    }
}
