package io.lifeengine.cryptobot.application.reliability;

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
 * Drives {@link ReconciliationService#sweep()}: once as soon as the application is ready (a
 * restart is exactly when orphaned {@code EXECUTING}/{@code SUBMITTED} rows exist) and then every
 * {@code cryptobot.reliability.reconciliation.interval}. A failing sweep is logged and the next
 * tick runs anyway.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;
    private final ReliabilityProperties.Reconciliation config;
    private volatile Disposable loop;

    public ReconciliationJob(ReconciliationService service, ReliabilityProperties properties) {
        this.service = service;
        this.config = properties.reconciliation();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!config.enabled()) {
            log.info("reconciliation_job_disabled");
            return;
        }
        Duration interval = config.interval();
        log.info("reconciliation_job_starting interval={} grace={} maxAttempts={}", interval, config.grace(), config.maxAttempts());
        loop = Flux.concat(Mono.just(-1L), Flux.interval(interval, interval))
                .onBackpressureDrop()
                .concatMap(tick -> service.sweep()
                        .onErrorResume(ex -> {
                            log.warn("reconciliation_sweep_failed error={}", ex.toString());
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
