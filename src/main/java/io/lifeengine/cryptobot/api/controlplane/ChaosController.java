package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.chaos.BroadcastChaos;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * arm/disarm the demo's fault injection. Exists only with {@code cryptobot.chaos.enabled=true}
 * (the same property that creates the chaos bean); {@code RUNTIME_ADMIN}. Not part of the product API.
 *
 * <pre>
 *   GET    /api/cryptobot/demo/chaos                       → {broadcast, shotsLeft, armed, faults[]}
 *   PUT    /api/cryptobot/demo/chaos {"broadcast":"rpc-down","shots":-1}
 *   DELETE /api/cryptobot/demo/chaos                       → disarmed
 * </pre>
 */
@RestController
@ConditionalOnProperty(prefix = "cryptobot.chaos", name = "enabled", havingValue = "true")
@RequestMapping(path = "/api/cryptobot/demo/chaos", produces = "application/json")
public class ChaosController {

    private final BroadcastChaos chaos;

    public ChaosController(BroadcastChaos chaos) {
        this.chaos = chaos;
    }

    public record ArmRequest(String broadcast, Integer shots) {}

    public record View(String broadcast, int shotsLeft, boolean armed, java.util.List<BroadcastChaos.Fault> faults) {
        static View of(BroadcastChaos.State s) {
            return new View(s.broadcast() == null ? null : s.broadcast().id(), s.shotsLeft(), s.armed(), s.faults());
        }
    }

    @GetMapping
    public Mono<View> state() {
        return Mono.fromSupplier(() -> View.of(chaos.state()));
    }

    @PutMapping
    public Mono<View> arm(@RequestBody ArmRequest body) {
        BroadcastChaos.Mode mode;
        try {
            mode = BroadcastChaos.Mode.parse(body == null ? null : body.broadcast());
        } catch (IllegalArgumentException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_CHAOS_MODE",
                    "broadcast must be one of uncertain | rpc-down | confirm-timeout, got " + body.broadcast().toLowerCase(Locale.ROOT)));
        }
        if (mode == null) {
            return Mono.fromSupplier(() -> View.of(chaos.disarm()));
        }
        int shots = body.shots() == null ? 1 : body.shots();
        return Mono.fromSupplier(() -> View.of(chaos.arm(mode, shots)));
    }

    @DeleteMapping
    public Mono<View> disarm() {
        return Mono.fromSupplier(() -> View.of(chaos.disarm()));
    }
}
