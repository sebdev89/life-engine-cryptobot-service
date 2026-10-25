package io.lifeengine.cryptobot.application.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.signer.SignerProperties;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** KAN-1249 pilot: swaps use the dedicated (mainnet) signer only when one is configured; everything else keeps the shared one. */
class SwapsSignerTest {

    static MockResponse identity(String key) {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"publicKey\":\"" + key + "\",\"cluster\":\"x\",\"maxLamports\":1,\"allowedDestinations\":[]}");
    }

    @Test
    void withoutABaseUrlSwapsUseTheSharedSigner() {
        SignerClient shared = new SignerClient(WebClient.builder(), new SignerProperties(true, "http://localhost:1", "t", Duration.ofSeconds(1)));
        SwapsSigner s = new SwapsConfiguration().swapsSigner(shared, new SwapsSignerProperties(" ", null, null), WebClient.builder());
        assertThat(s.dedicated()).isFalse();
        assertThat(s.client()).isSameAs(shared);
    }

    @Test
    void withABaseUrlSwapsTalkToTheDedicatedSignerWithItsToken() throws Exception {
        try (MockWebServer shared = new MockWebServer(); MockWebServer pilot = new MockWebServer()) {
            shared.enqueue(identity("SHARED"));
            pilot.enqueue(identity("PILOT"));
            shared.start();
            pilot.start();
            SignerClient sharedClient = new SignerClient(WebClient.builder(),
                    new SignerProperties(true, "http://localhost:" + shared.getPort(), "shared-token", Duration.ofSeconds(2)));
            SwapsSigner s = new SwapsConfiguration().swapsSigner(sharedClient,
                    new SwapsSignerProperties("http://localhost:" + pilot.getPort() + "/", "pilot-token", Duration.ofSeconds(2)), WebClient.builder());

            assertThat(s.dedicated()).isTrue();
            assertThat(s.client().identity().block()).hasValueSatisfying(id -> assertThat(id.publicKey()).isEqualTo("PILOT"));
            assertThat(pilot.getRequestCount()).isEqualTo(1);
            assertThat(shared.getRequestCount()).isZero();
            assertThat(pilot.takeRequest().getHeaders().toMultimap().toString()).contains("pilot-token");
        }
    }

    @Test
    void theTokenIsTrimmedLikeTheSignerDoesSoASecretWithANewlineStillMatches() {
        // the signer trims SIGNER_TOKEN (SignerProperties); a Secret written with a trailing newline must not become a 401
        assertThat(new SwapsSignerProperties("http://x", " tok\n", null).token()).isEqualTo("tok");
        assertThat(new SwapsSignerProperties("http://x", null, null).token()).isEmpty();
    }
}
