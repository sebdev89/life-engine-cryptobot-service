package io.lifeengine.cryptobot.application.paper;

import jakarta.annotation.PreDestroy;
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
 * Drives {@link PaperTradingService#tick} once the application is ready (a restart catches up the candles it
 * missed) and then every {@code cryptobot.paper.interval}. Off by default ({@code cryptobot.paper.enabled}): it
 * runs where the 2–4 week window is measured, k8s-uat, and nowhere else. A failed tick is logged and retried on
 * the next interval; nothing is skipped, because the next tick applies every candle not applied yet.
 */
@Component
public class PaperTradingJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingJob.class);

    private final PaperTradingService service;
    private final PaperProperties config;
    private volatile Disposable loop;

    public PaperTradingJob(PaperTradingService service, PaperProperties config) {
        this.service = service;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!config.enabled()) {
            log.info("paper_job_disabled");
            return;
        }
        log.info("paper_job_starting interval={} granularity={} portfolios={}", config.interval(), config.granularity(),
                config.portfolios().stream().map(PaperProperties.PortfolioDef::id).toList());
        loop = Flux.concat(Mono.just(-1L), Flux.interval(config.interval(), config.interval()))
                .onBackpressureDrop()
                .concatMap(t -> service.tick()
                        .doOnNext(r -> log.info("paper_tick closed_up_to={} candles={} portfolios={}", r.closedUpTo(), r.candles(), r.portfolios()))
                        .onErrorResume(ex -> {
                            log.warn("paper_tick_failed error={}", ex.toString());
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
