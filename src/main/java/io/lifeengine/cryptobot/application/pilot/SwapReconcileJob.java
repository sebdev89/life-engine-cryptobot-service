package io.lifeengine.cryptobot.application.pilot;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * KAN-1249 pilot: settles swap orders left in flight — by a crash, a restart or a caller that went away — once as soon
 * as the application is ready and then every {@code cryptobot.swaps.reconcile-interval}, through
 * {@link SwapExecutor#reconcileOrder}. It only asks the chain; it never sends. The order this process is executing
 * right now is left to the executor. Off while {@code cryptobot.swaps.enabled=false}.
 */
public class SwapReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SwapReconcileJob.class);

    private final SwapsProperties props;
    private final SwapOrderRepository repo;
    private final SwapExecutor executor;
    private volatile Disposable loop;

    public SwapReconcileJob(SwapsProperties props, SwapOrderRepository repo, SwapExecutor executor) {
        this.props = props;
        this.repo = repo;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!props.enabled()) {
            log.info("swap_reconcile_job_disabled");
            return;
        }
        Duration interval = props.reconcileInterval();
        log.info("swap_reconcile_job_starting interval={}", interval);
        loop = Flux.concat(Mono.just(-1L), Flux.interval(interval, interval))
                .onBackpressureDrop()
                .concatMap(tick -> sweep()
                        .onErrorResume(ex -> {
                            log.warn("swap_reconcile_sweep_failed error={}", ex.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /** One pass over the in-flight orders; how many it looked at. */
    Mono<Long> sweep() {
        return repo.inFlight()
                .filter(o -> !o.id().equals(executor.running()))
                .concatMap(o -> executor.reconcileOrder(o, null))
                .count();
    }

    /** Whether the loop is running (it never is with the flag off). */
    boolean started() {
        return loop != null && !loop.isDisposed();
    }

    @PreDestroy
    void stop() {
        if (loop != null && !loop.isDisposed()) {
            loop.dispose();
        }
    }
}
