package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.adapters.solana.MainnetDisabledException;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** KAN-493: the execute gate surfaces as {@code 409 MAINNET_DISABLED}, not as a generic CONFLICT or a 502. */
class ControlPlaneExceptionHandlerMainnetTest {

    @Test
    void mainnetDisabledIs409WithItsOwnCode() {
        ResponseEntity<ControlPlaneDtos.ApiError> r = new ControlPlaneExceptionHandler()
                .mainnetDisabled(new MainnetDisabledException("execute", SolanaCluster.MAINNET_BETA));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().code()).isEqualTo("MAINNET_DISABLED");
        assertThat(r.getBody().message()).contains("mainnet-beta").contains("CRYPTOBOT_ALLOW_MAINNET");
    }
}
