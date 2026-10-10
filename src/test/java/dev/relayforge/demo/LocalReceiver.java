package dev.relayforge.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local equivalent of examples/receiver.py, so the demo also runs with Java alone. */
final class LocalReceiver implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Route> routes = new HashMap<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    LocalReceiver(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestMethod().equals("GET")
                    && exchange.getRequestURI().getPath().equals("/health")) {
                reply(exchange, 200);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(73729);
            if (body.length > 73728) {
                reply(exchange, 413);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/configure")) {
                JsonNode config = mapper.readTree(body);
                String routePath = config.path("path").asText();
                String mode = config.path("mode").asText();
                if (!routePath.startsWith("/hooks/")
                        || !Set.of("ok", "flaky", "reject").contains(mode)) {
                    reply(exchange, 400);
                    return;
                }
                Route route = routes.computeIfAbsent(routePath, ignored -> new Route());
                route.secret = config.path("secret").asText();
                route.mode = mode;
                reply(exchange, 200);
                return;
            }
            Route route = routes.get(path);
            if (route == null) {
                reply(exchange, 404);
                return;
            }
            String signature = exchange.getRequestHeaders().getFirst("Webhook-Signature");
            String id = exchange.getRequestHeaders().getFirst("Webhook-Id");
            if (!verify(route.secret, id, signature, body)) {
                reply(exchange, 401);
                return;
            }
            route.attempts++;
            boolean duplicate = route.processed.contains(id);
            int status =
                    duplicate || route.mode.equals("ok")
                            ? 204
                            : route.mode.equals("reject") ? 400 : route.attempts <= 2 ? 503 : 204;
            if (status == 204) route.processed.add(id);
            System.out.printf(
                    "Receiver: delivery=%s signatureVerified=true status=%d duplicate=%s%n",
                    id, status, duplicate);
            reply(exchange, status);
        } catch (RuntimeException e) {
            // The receiver is a local teaching fixture; malformed input must not kill its server.
            System.out.println("Receiver rejected malformed input.");
        }
    }

    private boolean verify(String secret, String id, String signature, byte[] body) {
        if (signature == null || id == null) return false;
        try {
            String[] parts = signature.split(",v1=");
            if (parts.length != 2 || !parts[0].startsWith("t=")) return false;
            long timestamp = Long.parseLong(parts[0].substring(2));
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) return false;
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            hmac.update((timestamp + "." + id + ".").getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hmac.doFinal(body), HexFormat.of().parseHex(parts[1]));
        } catch (Exception e) {
            return false;
        }
    }

    private void reply(HttpExchange exchange, int status) throws IOException {
        byte[] result = "{}".getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, result.length);
        exchange.getResponseBody().write(result);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class Route {
        String secret;
        String mode;
        int attempts;
        Set<String> processed = new HashSet<>();
    }
}
