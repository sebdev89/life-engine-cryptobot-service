package io.lifeengine.cryptobot.application.receipt;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Drives {@link AnchorService#sweep} once the application is ready (a restart is exactly when a
 * SUBMITTED batch is waiting to be settled) and then every {@code cryptobot.anchor.interval}.
 * Off by default: it needs the isolated signer and devnet; {@code POST /api/cryptobot/anchors}
 * does the same on demand.
 */
@Component
public class AnchorJob {

    private static final Logger log = LoggerFactory.getLogger(AnchorJob.class);

    private final AnchorService service;
    private final AnchorProperties config;
    private volatile Disposable loop;

    public AnchorJob(AnchorService service, AnchorProperties config) {
        this.service = service;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!config.enabled()) {
            log.info("anchor_job_disabled");
            return;
        }
        Duration interval = config.interval();
        log.info("anchor_job_starting cluster={} interval={} batchSize={} maxAttempts={}", config.solanaCluster().id(), interval, config.batchSize(), config.maxAttempts());
        loop = Flux.concat(Mono.just(-1L), Flux.interval(interval, interval))
                .onBackpressureDrop()
                .concatMap(tick -> service.sweep(false)
                        .doOnNext(r -> log.info("anchor_sweep settled={} opened={} pending={}", r.settled().size(),
                                r.anchored() == null ? "-" : r.anchored().root(), r.pending()))
                        .onErrorResume(ex -> {
                            log.warn("anchor_sweep_failed error={}", ex.toString());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (loop != null && !loop.isDisposed()) {
            loop.dispose();
        }
    }
}
