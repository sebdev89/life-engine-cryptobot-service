package io.lifeengine.cryptobot.application.paper;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Drives {@link PaperTradingService#tick} once the application is ready (a restart catches up the candles it
 * missed) and then on the wall clock, at every multiple of {@code cryptobot.paper.interval} plus
 * {@code cryptobot.paper.settle} (KAN-1276; before, the cycle was counted from the start of the process, so the delay
 * from a candle's close to its decision moved between 0 and {@code interval} with every deploy). Off by default
 * ({@code cryptobot.paper.enabled}): it runs where the 2–4 week window is measured, k8s-uat, and nowhere else. A
 * failed tick is logged and retried on the next interval; nothing is skipped, because the next tick applies every
 * candle not applied yet. When does not change what: the decisions depend on the candles only.
 *
 * <p>{@value #METRIC_LATENCY} is the measured delay from the close of the last candle a tick applied to the moment
 * it was applied — the number the alignment is for, measured instead of inferred.
 */
@Component
public class PaperTradingJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingJob.class);
    public static final String METRIC_LATENCY = "cryptobot.paper.tick.latency.seconds";

    private final PaperTradingService service;
    private final PaperProperties config;
    private final Clock clock;
    private final AtomicLong lastLatencyMillis = new AtomicLong(-1);
    private volatile Disposable loop;

    @Autowired
    public PaperTradingJob(PaperTradingService service, PaperProperties config, MeterRegistry meters) {
        this(service, config, meters, Clock.systemUTC());
    }

    PaperTradingJob(PaperTradingService service, PaperProperties config, MeterRegistry meters, Clock clock) {
        this.service = service;
        this.config = config;
        this.clock = clock;
        meters.gauge(METRIC_LATENCY, lastLatencyMillis, v -> v.get() < 0 ? Double.NaN : v.get() / 1000.0);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!config.enabled()) {
            log.info("paper_job_disabled");
            return;
        }
        Instant now = clock.instant();
        log.info("paper_job_starting interval={} settle={} first_aligned_in={} granularity={} portfolios={}", config.interval(), config.settle(),
                firstAlignedDelay(now, config.interval(), config.settle()), config.granularity(),
                config.portfolios().stream().map(PaperProperties.PortfolioDef::id).toList());
        loop = schedule(now)
                .onBackpressureDrop()
                .concatMap(t -> service.tick()
                        .doOnNext(this::observe)
                        .onErrorResume(ex -> {
                            log.warn("paper_tick_failed error={}", ex.toString());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /** One immediate catch-up tick, then one at every multiple of {@code interval} plus {@code settle} on the wall clock. */
    Flux<Long> schedule(Instant now) {
        return Flux.concat(Mono.just(-1L), Flux.interval(firstAlignedDelay(now, config.interval(), config.settle()), config.interval()));
    }

    /** Time from {@code now} to the next instant {@code t} with {@code (t − settle)} a multiple of {@code interval} since the epoch (0 if {@code now} is one). */
    static Duration firstAlignedDelay(Instant now, Duration interval, Duration settle) {
        long period = interval.toMillis();
        long phase = Math.floorMod(now.toEpochMilli() - settle.toMillis(), period);
        return phase == 0 ? Duration.ZERO : Duration.ofMillis(period - phase);
    }

    /** Logs the tick and, when it applied candles, records how long after their close it ran. */
    void observe(PaperTradingService.TickResult r) {
        boolean applied = r.portfolios().stream().anyMatch(a -> a.outcome() == PaperTradingService.Outcome.ADVANCED);
        if (applied && r.closedUpTo() != null) {
            long latency = Duration.between(r.closedUpTo(), clock.instant()).toMillis();
            lastLatencyMillis.set(latency);
            log.info("paper_tick closed_up_to={} candles={} latency_s={} portfolios={}", r.closedUpTo(), r.candles(), latency / 1000.0, r.portfolios());
        } else {
            log.info("paper_tick closed_up_to={} candles={} portfolios={}", r.closedUpTo(), r.candles(), r.portfolios());
        }
    }

    @PreDestroy
    void stop() {
        if (loop != null && !loop.isDisposed()) {
            loop.dispose();
        }
    }
}
