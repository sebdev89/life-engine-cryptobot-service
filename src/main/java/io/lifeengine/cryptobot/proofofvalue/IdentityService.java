package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Minimal contributor registry (KAN-818): create, list, get — idempotent by id. The tenant is the
 * JWT owner ({@link Receipts#tenantOf}), the same key the receipts use; an identity of another
 * tenant does not exist for the caller (404).
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    /** {@code created=false}: the id already existed and the stored identity is returned unchanged. */
    public record Created(PovIdentity identity, boolean created) {}

    private final PovIdentityRepository identities;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    @Autowired
    public IdentityService(PovIdentityRepository identities, CryptobotMetrics metrics) {
        this(identities, metrics, Clock.systemUTC());
    }

    IdentityService(PovIdentityRepository identities, CryptobotMetrics metrics, Clock clock) {
        this.identities = identities;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<Created> create(UUID ownerUserId, ProofOfValueDtos.IdentityRequest req) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return identities.find(tenant, req.id())
                .map(existing -> new Created(existing, false))
                .switchIfEmpty(Mono.defer(() -> requireReference(tenant, "ownerId", req.ownerId())
                        .then(requireReference(tenant, "operatorId", req.operatorId()))
                        .then(Mono.defer(() -> {
                            PovIdentity identity = new PovIdentity(tenant, req.id(), req.kind(), req.displayName().trim(), blank(req.wallet()),
                                    blank(req.ownerId()), blank(req.operatorId()), clock.instant());
                            return identities.insertIfAbsent(identity)
                                    .map(stored -> {
                                        metrics.povIdentity();
                                        log.info("pov_identity_created id={} kind={} owner={}", stored.id(), stored.kind(), stored.ownerId());
                                        return new Created(stored, true);
                                    })
                                    // Lost a race with a concurrent create of the same id: the stored one wins.
                                    .switchIfEmpty(Mono.defer(() -> identities.find(tenant, req.id()).map(s -> new Created(s, false))));
                        }))));
    }

    public Flux<PovIdentity> list(UUID ownerUserId) {
        return identities.findAll(Receipts.tenantOf(ownerUserId));
    }

    public Mono<PovIdentity> require(UUID ownerUserId, String id) {
        return identities.find(Receipts.tenantOf(ownerUserId), id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Identity " + id)));
    }

    private Mono<Void> requireReference(String tenant, String field, String id) {
        if (id == null || id.isBlank()) {
            return Mono.empty();
        }
        return identities.find(tenant, id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.InvalidRequest("UNKNOWN_IDENTITY", field + " '" + id + "' is not a registered identity")))
                .then();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
