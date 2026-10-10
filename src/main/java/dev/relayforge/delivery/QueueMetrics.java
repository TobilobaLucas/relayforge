package dev.relayforge.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics {
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public QueueMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        for (String status : new String[] {"READY", "IN_FLIGHT", "SUCCEEDED", "DEAD"}) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            metrics.gauge(
                    "relay.queue.deliveries",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("status", status)),
                    count);
        }
    }

    @Scheduled(fixedDelayString = "${relay.metrics-refresh-ms:15000}")
    public void refresh() {
        Map<String, Long> snapshot = new java.util.HashMap<>();
        jdbc.query(
                "SELECT status, count(*) AS total FROM deliveries GROUP BY status",
                rs -> {
                    snapshot.put(rs.getString("status"), rs.getLong("total"));
                });
        counts.forEach((status, counter) -> counter.set(snapshot.getOrDefault(status, 0L)));
    }
}
