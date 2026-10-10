package dev.relayforge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.relayforge.api.ApiModels.*;
import dev.relayforge.delivery.*;
import dev.relayforge.endpoint.EndpointService;
import dev.relayforge.event.EventService;
import dev.relayforge.project.ProjectService;
import dev.relayforge.security.SecretVault;
import dev.relayforge.security.UrlPolicy;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "relay.worker-enabled=false", "relay.max-pending-per-project=40",
                    "relay.development-hosts=localhost",
            "relay.request-timeout=1s", "relay.lease-duration=5s", "relay.metrics-refresh-ms=60000"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
class RelayForgeIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = TestSupport.postgres();

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        POSTGRES.close();
                                    } catch (Exception ignored) {
                                    }
                                }));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("relay.admin-token", () -> TestSupport.ADMIN_TOKEN);
        registry.add("relay.encryption-key", () -> TestSupport.ENCRYPTION_KEY);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired EndpointService endpoints;
    @Autowired EventService events;
    @Autowired DeliveryService deliveries;
    @Autowired DeliveryQueue queue;
    @Autowired HttpWebhookTransport transport;
    @Autowired DeliveryProcessor processor;
    @Autowired SecretVault vault;

    private ProjectCredential tenant;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE projects RESTART IDENTITY CASCADE");
        tenant = projects.create("Integration project");
    }

    @Test
    void healthAndDocsArePublicButMetricsAndRoutesRequireTheRightRole() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("RelayForge API"));
        mvc.perform(get("/api/v1/project"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/project").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/projects").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/project")
                                .header("Authorization", "Bearer " + TestSupport.ADMIN_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/actuator/prometheus")
                                .header("Authorization", "Bearer " + TestSupport.ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void keyRotationRevokesTheOldKeyAndSecretsAreNeverListed() throws Exception {
        String stored =
                jdbc.queryForObject(
                        "SELECT api_key_hash FROM projects WHERE id = ?",
                        String.class,
                        projectId());
        assertThat(stored).hasSize(64).doesNotContain(tenant.apiKey());
        EndpointCredential endpoint = endpoint("https://8.8.8.8/hook");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_secret FROM endpoints WHERE id = ?",
                                String.class,
                                endpoint.endpoint().id()))
                .doesNotContain(endpoint.signingSecret());
        mvc.perform(get("/api/v1/endpoints").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].signingSecret").doesNotExist());
        ProjectCredential rotated = projects.rotateKey(projectId());
        mvc.perform(get("/api/v1/project").header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/project").header("Authorization", "Bearer " + rotated.apiKey()))
                .andExpect(status().isOk());
    }

    @Test
    void crossTenantReadsAndWritesReturn404() throws Exception {
        EndpointCredential endpoint = endpoint("https://8.8.8.8/hook");
        EventReceipt event = publish("first");
        UUID delivery = deliveryId(event.eventId());
        ProjectCredential other = projects.create("Other project");
        String auth = "Bearer " + other.apiKey();
        for (String path :
                List.of(
                        "/endpoints/" + endpoint.endpoint().id(),
                        "/events/" + event.eventId(),
                        "/deliveries/" + delivery,
                        "/deliveries/" + delivery + "/attempts")) {
            mvc.perform(get("/api/v1" + path).header("Authorization", auth))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(
                        patch("/api/v1/endpoints/" + endpoint.endpoint().id())
                                .header("Authorization", auth)
                                .contentType("application/json")
                                .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/deliveries/" + delivery + "/replay")
                                .header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/deliveries").header("Authorization", auth))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/audit").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void simultaneousIdenticalSubmissionsCommitExactlyOneEvent() throws Exception {
        endpoint("https://8.8.8.8/hook");
        PublishEvent request = request();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EventReceipt>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return events.publish(projectId(), "same-key", request);
                                }));
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            int created = 0;
            for (Future<EventReceipt> future : results) {
                EventReceipt receipt = future.get(15, TimeUnit.SECONDS);
                ids.add(receipt.eventId());
                if (!receipt.duplicate()) created++;
            }
            assertThat(ids).hasSize(1);
            assertThat(created).isEqualTo(1);
        }
        assertThat(count("events")).isEqualTo(1);
        assertThat(count("deliveries")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rate_count FROM projects WHERE id = ?",
                                Integer.class,
                                projectId()))
                .isEqualTo(1);
    }

    @Test
    void equivalentObjectKeyOrderIsIdempotentButDifferentContentConflicts() throws Exception {
        String body = "{\"type\":\"orders.created\",\"data\":{\"a\":1,\"b\":{\"x\":2,\"y\":3}}}";
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "canonical")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "canonical")
                                .contentType("application/json")
                                .content(
                                        "{\"data\":{\"b\":{\"y\":3,\"x\":2},\"a\":1},\"type\":\"orders.created\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "canonical")
                                .contentType("application/json")
                                .content(body.replace("\"a\":1", "\"a\":9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));
    }

    @Test
    void deliveryInsertionFailureRollsBackEventQuotaAndAuditTogether() {
        endpoint("https://8.8.8.8/hook");
        long audits = count("audit_log");
        jdbc.execute("ALTER TABLE deliveries ADD CONSTRAINT test_insertion_failure CHECK (FALSE)");
        try {
            assertThatThrownBy(() -> publish("atomic"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE deliveries DROP CONSTRAINT test_insertion_failure");
        }
        assertThat(count("events")).isZero();
        assertThat(count("deliveries")).isZero();
        assertThat(count("audit_log")).isEqualTo(audits);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rate_count FROM projects WHERE id = ?",
                                Integer.class,
                                projectId()))
                .isZero();
    }

    @Test
    void subscriptionsAndPausedEndpointsControlFanout() {
        EndpointCredential first = endpoint("https://8.8.8.8/one");
        EndpointCredential paused = endpoint("https://8.8.8.8/two");
        endpoints.update(projectId(), paused.endpoint().id(), false);
        endpoints.create(
                projectId(),
                new CreateEndpoint("https://8.8.8.8/other", "Unrelated", Set.of("invoices.paid")));
        EventReceipt event = publish("fanout");
        assertThat(event.deliveryCount()).isEqualTo(1);
        assertThat(deliveries.get(projectId(), deliveryId(event.eventId())).endpointId())
                .isEqualTo(first.endpoint().id());
        endpoints.update(projectId(), first.endpoint().id(), false);
        assertThat(queue.claim(10)).isEmpty();
        endpoints.update(projectId(), first.endpoint().id(), true);
        assertThat(queue.claim(10)).hasSize(1);
    }

    @Test
    void workersNeverClaimTheSameLiveLease() throws Exception {
        endpoint("https://8.8.8.8/hook");
        for (int i = 0; i < 40; i++) publish("parallel-" + i);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<DeliveryJob>>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return queue.claim(5);
                                }));
            start.countDown();
            List<UUID> ids = new ArrayList<>();
            for (var result : results)
                result.get(15, TimeUnit.SECONDS).forEach(job -> ids.add(job.id()));
            assertThat(ids).hasSize(40).doesNotHaveDuplicates();
        }
        assertThat(queue.claim(1)).isEmpty();
        assertThat(count("delivery_attempts")).isEqualTo(40);
    }

    @Test
    void expiredLeaseIsRecoveredAndOldCompletionIsFenced() {
        endpoint("https://8.8.8.8/hook");
        EventReceipt event = publish("recovery");
        DeliveryJob first = queue.claim(1).getFirst();
        expire(first.id());
        DeliveryJob second = queue.claim(1).getFirst();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(queue.complete(first, success())).isFalse();
        assertThat(deliveries.get(projectId(), deliveryId(event.eventId())).status())
                .isEqualTo("IN_FLIGHT");
        assertThat(queue.complete(second, success())).isTrue();
        assertThat(deliveries.attempts(projectId(), first.id()))
                .extracting(Attempt::outcome)
                .containsExactly("LEASE_EXPIRED", "SUCCEEDED");
    }

    @Test
    void exhaustedCrashedAttemptsBecomeDeadAndCanBeReplayedWithHistory() {
        endpoint("https://8.8.8.8/hook");
        UUID id = deliveryId(publish("crash-loop").eventId());
        for (int i = 0; i < 5; i++) {
            assertThat(queue.claim(1)).hasSize(1);
            expire(id);
        }
        assertThat(queue.claim(1)).isEmpty();
        assertThat(queue.reapExhaustedWork()).isEqualTo(1);
        assertThat(deliveries.get(projectId(), id).status()).isEqualTo("DEAD");
        assertThat(deliveries.attempts(projectId(), id))
                .extracting(Attempt::outcome)
                .containsOnly("LEASE_EXPIRED");
        Delivery replay = deliveries.replay(projectId(), id);
        assertThat(replay.status()).isEqualTo("READY");
        assertThat(replay.replayCount()).isEqualTo(1);
        DeliveryJob replayed = queue.claim(1).getFirst();
        assertThat(replayed.attemptNumber()).isEqualTo(6);
        assertThat(replayed.cycleAttempts()).isEqualTo(1);
        queue.complete(replayed, success());
        assertThat(deliveries.attempts(projectId(), id)).hasSize(6);
    }

    @Test
    void retryableFailureIsDelayedAndPermanentFailureIsDeadImmediately() {
        endpoint("https://8.8.8.8/hook");
        UUID id = deliveryId(publish("retry").eventId());
        DeliveryJob first = queue.claim(1).getFirst();
        queue.complete(
                first, new DeliveryResult(503, "http_503", true, Duration.ofSeconds(60), 10));
        Delivery pending = deliveries.get(projectId(), id);
        assertThat(pending.status()).isEqualTo("READY");
        assertThat(Duration.between(pending.updatedAt(), pending.nextAttemptAt()).getSeconds())
                .isGreaterThanOrEqualTo(59);
        assertThat(queue.claim(1)).isEmpty();
        due(id);
        queue.complete(
                queue.claim(1).getFirst(), new DeliveryResult(400, "http_400", false, null, 4));
        assertThat(deliveries.get(projectId(), id).status()).isEqualTo("DEAD");
        assertThat(deliveries.attempts(projectId(), id)).hasSize(2);
    }

    @Test
    void fiveTransientFailuresExhaustTheBudget() {
        endpoint("https://8.8.8.8/hook");
        UUID id = deliveryId(publish("exhaustion").eventId());
        for (int i = 0; i < 5; i++) {
            due(id);
            queue.complete(
                    queue.claim(1).getFirst(), new DeliveryResult(500, "http_500", true, null, 1));
        }
        assertThat(deliveries.get(projectId(), id).status()).isEqualTo("DEAD");
        assertThat(queue.claim(1)).isEmpty();
    }

    @Test
    void loweringRetryBudgetMovesPreviouslyRetryableWorkToDeadLetters() {
        endpoint("https://8.8.8.8/hook");
        UUID id = deliveryId(publish("lowered-budget").eventId());
        for (int i = 0; i < 3; i++) {
            due(id);
            queue.complete(
                    queue.claim(1).getFirst(), new DeliveryResult(503, "http_503", true, null, 1));
        }
        assertThat(deliveries.get(projectId(), id).status()).isEqualTo("READY");
        var settings =
                new dev.relayforge.config.RelayProperties(
                        TestSupport.ADMIN_TOKEN,
                        TestSupport.ENCRYPTION_KEY,
                        16,
                        3,
                        Duration.ofSeconds(45),
                        Duration.ofSeconds(10),
                        10000,
                        1000,
                        65536,
                        Set.of());
        var reduced = new DeliveryQueue(jdbc, settings, new RetryPolicy());
        new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()))
                .executeWithoutResult(
                        transaction -> assertThat(reduced.reapExhaustedWork()).isEqualTo(1));
        Delivery dead = deliveries.get(projectId(), id);
        assertThat(dead.status()).isEqualTo("DEAD");
        assertThat(dead.lastError()).isEqualTo("attempt_budget_exhausted");
        assertThat(dead.attemptCount()).isEqualTo(3);
        assertThat(deliveries.attempts(projectId(), id)).hasSize(3);
    }

    @Test
    void queueBackpressureRejectsAtomicallyAndDuplicatesStillWork() throws Exception {
        endpoint("https://8.8.8.8/hook");
        for (int i = 0; i < 40; i++) publish("capacity-" + i);
        assertThat(publish("capacity-0").duplicate()).isTrue();
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "over-capacity")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(request())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("queue_full"));
        assertThat(count("events")).isEqualTo(40);
        queue.complete(queue.claim(1).getFirst(), success());
        assertThat(publish("after-drain").duplicate()).isFalse();
    }

    @Test
    void rateLimitIsDurableAndResetsAtTheNextMinute() throws Exception {
        jdbc.update(
                "UPDATE projects SET rate_count = 1000, rate_window = date_trunc('minute', clock_timestamp()) WHERE id = ?",
                projectId());
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "rate-limited")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(request())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("event_rate_exceeded"));
        assertThat(count("events")).isZero();
        jdbc.update(
                "UPDATE projects SET rate_window = clock_timestamp() - interval '2 minutes' WHERE id = ?",
                projectId());
        assertThat(publish("next-minute").duplicate()).isFalse();
    }

    @Test
    void paginationTraversesTiedTimestampsWithoutDuplicates() {
        endpoint("https://8.8.8.8/hook");
        for (int i = 0; i < 7; i++) publish("page-" + i);
        jdbc.update("UPDATE deliveries SET created_at = '2026-01-01T00:00:00.123456Z'");
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            Page<Delivery> page = deliveries.list(projectId(), null, null, cursor, 2);
            page.items().forEach(item -> seen.add(item.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void malformedInputsAndOversizedBodiesAreClientErrorsWithRequestIds() throws Exception {
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .contentType("application/json")
                                .content("{\"type\":\"orders.created\",\"data\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "unknown")
                                .contentType("application/json")
                                .content("{\"type\":\"orders.created\",\"data\":{},\"typo\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/deliveries?limit=0").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/unknown")
                                .header("Authorization", bearer())
                                .header("X-Request-Id", "missing-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("missing-route"));
        mvc.perform(get("/api/v1/deliveries?cursor=invalid").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/events")
                                .header("Authorization", bearer())
                                .header("X-Request-Id", "demo-request")
                                .contentType("application/json")
                                .content("x".repeat(70000)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("X-Request-Id", "demo-request"))
                .andExpect(jsonPath("$.requestId").value("demo-request"));
    }

    @Test
    void receiverCanVerifyTheExactBodyAndWorkerPersistsSuccess() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> webhookId = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext(
                "/hooks",
                exchange -> {
                    receivedBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    signature.set(exchange.getRequestHeaders().getFirst("Webhook-Signature"));
                    webhookId.set(exchange.getRequestHeaders().getFirst("Webhook-Id"));
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        receiver.start();
        try {
            EndpointCredential endpoint =
                    endpoint("http://localhost:" + receiver.getAddress().getPort() + "/hooks");
            EventReceipt event = publish("signed");
            DeliveryJob job = queue.claim(1).getFirst();
            processor.process(job);
            assertThat(deliveries.get(projectId(), job.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(receivedBody.get()).isEqualTo(job.envelope());
            assertThat(webhookId.get()).isEqualTo(job.id().toString());
            String[] parts = signature.get().split(",v1=");
            long timestamp = Long.parseLong(parts[0].substring(2));
            // Receiver-side implementation, independent of WebhookSigner.
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(
                    new SecretKeySpec(
                            endpoint.signingSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            String expected =
                    HexFormat.of()
                            .formatHex(
                                    hmac.doFinal(
                                            (timestamp
                                                            + "."
                                                            + webhookId.get()
                                                            + "."
                                                            + receivedBody.get())
                                                    .getBytes(StandardCharsets.UTF_8)));
            assertThat(parts[1]).isEqualTo(expected);
            assertThat(mapper.readTree(receivedBody.get()).get("id").asText())
                    .isEqualTo(event.eventId().toString());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void redirectsAreNeverFollowed() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://localhost:"
                                            + receiver.getAddress().getPort()
                                            + "/target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        receiver.createContext(
                "/target",
                exchange -> {
                    targetHits.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        receiver.start();
        try {
            endpoint("http://localhost:" + receiver.getAddress().getPort() + "/redirect");
            publish("redirect");
            DeliveryJob job = queue.claim(1).getFirst();
            processor.process(job);
            assertThat(deliveries.get(projectId(), job.id()).status()).isEqualTo("DEAD");
            assertThat(targetHits).hasValue(0);
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void deliveryDnsPolicyIsAppliedAgainAfterRegistration() {
        endpoint("https://8.8.8.8/hook");
        publish("dns-revalidation");
        DeliveryJob original = queue.claim(1).getFirst();
        DeliveryJob rebound =
                new DeliveryJob(
                        original.id(),
                        original.eventId(),
                        original.endpointId(),
                        original.leaseToken(),
                        original.attemptNumber(),
                        original.cycleAttempts(),
                        "http://127.0.0.1:1/hooks",
                        original.encryptedSecret(),
                        original.envelope());
        var production =
                new HttpWebhookTransport(
                        new UrlPolicy(TestSupport.properties(Set.of())),
                        vault,
                        TestSupport.properties(Set.of()),
                        Clock.systemUTC());
        DeliveryResult result = production.send(rebound);
        assertThat(result.error()).isEqualTo("unsafe_destination");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void dnsRebindingBetweenUrlValidationAndConnectionIsBlocked() {
        endpoint("https://8.8.8.8/hook");
        publish("rebound-dns");
        DeliveryJob original = queue.claim(1).getFirst();
        DeliveryJob rebound =
                new DeliveryJob(
                        original.id(),
                        original.eventId(),
                        original.endpointId(),
                        original.leaseToken(),
                        original.attemptNumber(),
                        original.cycleAttempts(),
                        "https://receiver.example.test/hooks",
                        original.encryptedSecret(),
                        original.envelope());
        AtomicInteger resolutions = new AtomicInteger();
        UrlPolicy policy =
                new UrlPolicy(TestSupport.properties(Set.of())) {
                    @Override
                    public java.net.InetAddress[] resolve(String host)
                            throws java.net.UnknownHostException {
                        if (resolutions.incrementAndGet() > 1) throw new UnsafeAddressException();
                        return new java.net.InetAddress[] {
                            java.net.InetAddress.getByName("8.8.8.8")
                        };
                    }
                };
        var guarded =
                new HttpWebhookTransport(
                        policy, vault, TestSupport.properties(Set.of()), Clock.systemUTC());
        DeliveryResult result = guarded.send(rebound);
        assertThat(resolutions).hasValue(2);
        assertThat(result.error()).isEqualTo("unsafe_destination");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void transientDnsFailureRemainsRetryable() {
        endpoint("https://8.8.8.8/hook");
        publish("temporary-dns-outage");
        DeliveryJob original = queue.claim(1).getFirst();
        UrlPolicy policy =
                new UrlPolicy(TestSupport.properties(Set.of())) {
                    @Override
                    public java.net.InetAddress[] resolve(String host)
                            throws java.net.UnknownHostException {
                        throw new java.net.UnknownHostException("Simulated resolver outage");
                    }
                };
        var guarded =
                new HttpWebhookTransport(
                        policy, vault, TestSupport.properties(Set.of()), Clock.systemUTC());
        DeliveryResult result = guarded.send(original);
        assertThat(result.error()).isEqualTo("transport_error");
        assertThat(result.retryable()).isTrue();
        queue.complete(original, result);
        assertThat(deliveries.get(projectId(), original.id()).status()).isEqualTo("READY");
    }

    @Test
    void slowReceiverTimesOutAndReturnsDeliveryToTheQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext(
                "/slow",
                exchange -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        receiver.start();
        try {
            endpoint("http://localhost:" + receiver.getAddress().getPort() + "/slow");
            publish("slow-receiver");
            DeliveryJob job = queue.claim(1).getFirst();
            processor.process(job);
            Delivery delivery = deliveries.get(projectId(), job.id());
            assertThat(delivery.status()).isEqualTo("READY");
            assertThat(delivery.lastError()).isEqualTo("transport_error");
        } finally {
            release.countDown();
            receiver.stop(0);
        }
    }

    @Test
    void scheduledWorkerDrainsQueueWithinItsConcurrencyLimit() throws Exception {
        endpoint("https://8.8.8.8/hook");
        for (int i = 0; i < 8; i++) publish("worker-" + i);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(8);
        WebhookTransport fake =
                job -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                        finished.countDown();
                    }
                    return success();
                };
        var settings =
                new dev.relayforge.config.RelayProperties(
                        TestSupport.ADMIN_TOKEN,
                        TestSupport.ENCRYPTION_KEY,
                        2,
                        5,
                        Duration.ofSeconds(45),
                        Duration.ofSeconds(10),
                        10000,
                        1000,
                        65536,
                        Set.of());
        var worker =
                new DeliveryWorker(
                        queue,
                        new DeliveryProcessor(
                                queue,
                                fake,
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                        settings);
        try {
            worker.poll();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            assertThat(maximum).hasValue(2);
            release.countDown();
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                worker.poll();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM deliveries WHERE status = 'SUCCEEDED'",
                                                        Integer.class))
                                        .isEqualTo(8);
                            });
        } finally {
            release.countDown();
            worker.stop();
        }
        assertThat(finished.getCount()).isZero();
        assertThat(maximum.get()).isLessThanOrEqualTo(2);
    }

    private UUID projectId() {
        return tenant.project().id();
    }

    private String bearer() {
        return "Bearer " + tenant.apiKey();
    }

    private EndpointCredential endpoint(String url) {
        return endpoints.create(
                projectId(), new CreateEndpoint(url, "Test endpoint", Set.of("orders.created")));
    }

    private PublishEvent request() {
        try {
            return new PublishEvent(
                    "orders.created",
                    mapper.readTree("{\"orderId\":\"order-42\",\"amount\":1299}"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private EventReceipt publish(String key) {
        return events.publish(projectId(), key, request());
    }

    private UUID deliveryId(UUID eventId) {
        return deliveries.list(projectId(), null, eventId, null, 1).items().getFirst().id();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void due(UUID id) {
        jdbc.update(
                "UPDATE deliveries SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?",
                id);
    }

    private void expire(UUID id) {
        jdbc.update(
                "UPDATE deliveries SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                id);
    }

    private static DeliveryResult success() {
        return new DeliveryResult(204, null, false, null, 1);
    }
}
