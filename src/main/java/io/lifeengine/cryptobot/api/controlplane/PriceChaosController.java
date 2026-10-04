package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.chaos.PriceChaos;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * arm/disarm the demo's adversarial price. Exists only with {@code cryptobot.chaos.enabled=true}
 * (the same property that creates the chaos beans); {@code RUNTIME_ADMIN}. Not part of the product API.
 *
 * <pre>
 *   GET    /api/cryptobot/demo/price                                   → {armed, overrides[], injections[]}
 *   PUT    /api/cryptobot/demo/price {"asset":"SOL","source":"pyth-hermes","factor":0.1}   (one source says −90 %)
 *   PUT    /api/cryptobot/demo/price {"asset":"SOL","source":"*","ageSeconds":900}       (every source is stale)
 *   DELETE /api/cryptobot/demo/price[?asset=SOL]                       → disarmed
 * </pre>
 */
@RestController
@ConditionalOnProperty(prefix = "cryptobot.chaos", name = "enabled", havingValue = "true")
@RequestMapping(path = "/api/cryptobot/demo/price", produces = "application/json")
public class PriceChaosController {

    private final PriceChaos chaos;

    public PriceChaosController(PriceChaos chaos) {
        this.chaos = chaos;
    }

    public record ArmRequest(String asset, String source, BigDecimal priceUsd, BigDecimal factor, Long ageSeconds) {}

    public record View(boolean armed, List<PriceChaos.Override> overrides, List<PriceChaos.Injection> injections) {
        static View of(PriceChaos c) {
            return new View(c.armed(), c.overrides(), c.injections());
        }
    }

    @GetMapping
    public Mono<View> state() {
        return Mono.fromSupplier(() -> View.of(chaos));
    }

    @PutMapping
    public Mono<View> arm(@RequestBody ArmRequest body) {
        return Mono.fromSupplier(() -> {
            try {
                chaos.arm(new PriceChaos.Override(body == null ? null : body.asset(), body == null ? null : body.source(),
                        body == null ? null : body.priceUsd(), body == null ? null : body.factor(), body == null ? null : body.ageSeconds()));
            } catch (IllegalArgumentException ex) {
                throw new ControlPlaneExceptions.InvalidRequest("INVALID_PRICE_OVERRIDE", ex.getMessage());
            }
            return View.of(chaos);
        });
    }

    @DeleteMapping
    public Mono<View> disarm(@RequestParam(required = false) String asset) {
        return Mono.fromSupplier(() -> {
            if (asset == null || asset.isBlank()) {
                chaos.disarmAll();
            } else {
                chaos.disarm(asset);
            }
            return View.of(chaos);
        });
    }
}
