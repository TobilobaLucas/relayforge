package dev.relayforge.delivery;

import dev.relayforge.config.RelayProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "relay.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final DeliveryQueue queue;
    private final DeliveryProcessor processor;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private volatile boolean stopping;

    public DeliveryWorker(
            DeliveryQueue queue, DeliveryProcessor processor, RelayProperties properties) {
        this.queue = queue;
        this.processor = processor;
        this.slots = new Semaphore(properties.concurrency());
    }

    @Scheduled(fixedDelayString = "${relay.poll-delay-ms:500}")
    public void poll() {
        if (stopping) return;
        try {
            queue.reapExhaustedWork();
            for (DeliveryJob job : queue.claim(slots.availablePermits())) {
                slots.acquireUninterruptibly();
                try {
                    executor.submit(
                            () -> {
                                try {
                                    processor.process(job);
                                } catch (Exception e) {
                                    log.error(
                                            "Worker failed; the lease will recover this delivery {}",
                                            job.id(),
                                            e);
                                } finally {
                                    slots.release();
                                }
                            });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    slots.release();
                }
            }
        } catch (Exception e) {
            log.error("Queue polling failed; retrying on the next poll", e);
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
