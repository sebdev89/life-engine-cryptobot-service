package io.lifeengine.cryptobot.api.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.application.pilot.SwapOrderService.SwapRefused;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** KAN-1249: every refusal of the swap service becomes its HTTP status with the stable reason, nothing else. */
class SwapsControllerTest {

    static ResponseEntity<?> of(SwapRefused.Kind kind) {
        return SwapsController.refused(new SwapRefused(kind, "why")).block();
    }

    @Test
    void refusalsMapToTheirStatusAndCarryOnlyTheReason() {
        assertThat(of(SwapRefused.Kind.BAD_REQUEST).getStatusCode().value()).isEqualTo(400);
        assertThat(of(SwapRefused.Kind.FORBIDDEN).getStatusCode().value()).isEqualTo(403);
        assertThat(of(SwapRefused.Kind.NOT_FOUND).getStatusCode().value()).isEqualTo(404);
        assertThat(of(SwapRefused.Kind.CONFLICT).getStatusCode().value()).isEqualTo(409);
        assertThat(of(SwapRefused.Kind.UNAVAILABLE).getStatusCode().value()).isEqualTo(503);
        assertThat(of(SwapRefused.Kind.FORBIDDEN).getBody()).isEqualTo(Map.of("reason", "why"));
    }
}
