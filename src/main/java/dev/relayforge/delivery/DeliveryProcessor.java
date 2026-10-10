package dev.relayforge.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeliveryProcessor {
    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private final DeliveryQueue queue;
    private final WebhookTransport transport;
    private final MeterRegistry metrics;

    public DeliveryProcessor(
            DeliveryQueue queue, WebhookTransport transport, MeterRegistry metrics) {
        this.queue = queue;
        this.transport = transport;
        this.metrics = metrics;
    }

    public void process(DeliveryJob job) {
        DeliveryResult result = transport.send(job);
        boolean completed = queue.complete(job, result);
        String outcome =
                !completed
                        ? "stale"
                        : result.succeeded()
                                ? "success"
                                : result.retryable() ? "retryable_failure" : "permanent_failure";
        metrics.counter("relay.delivery.attempts", "outcome", outcome).increment();
        metrics.timer("relay.delivery.duration").record(result.durationMs(), TimeUnit.MILLISECONDS);
        log.atInfo()
                .addKeyValue("deliveryId", job.id())
                .addKeyValue("attempt", job.attemptNumber())
                .addKeyValue("outcome", outcome)
                .addKeyValue("httpStatus", result.httpStatus())
                .log("Delivery attempt completed");
    }
}
