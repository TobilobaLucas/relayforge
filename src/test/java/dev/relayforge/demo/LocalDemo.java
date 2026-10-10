package dev.relayforge.demo;

import dev.relayforge.RelayForgeApplication;
import dev.relayforge.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/**
 * Disposable developer launcher. Neither this class nor PostgreSQL binaries enter the production
 * jar.
 */
public final class LocalDemo {
    private LocalDemo() {}

    public static void main(String[] args) throws Exception {
        Path env = Path.of(".env");
        if (!Files.exists(env))
            throw new IllegalStateException("Run scripts/setup.ps1 or scripts/setup.sh first.");
        for (String line : Files.readAllLines(env)) {
            if (line.startsWith("RELAY_ADMIN_TOKEN="))
                System.setProperty("relay.admin-token", line.substring(line.indexOf('=') + 1));
            if (line.startsWith("RELAY_ENCRYPTION_KEY="))
                System.setProperty("relay.encryption-key", line.substring(line.indexOf('=') + 1));
        }
        var postgres = TestSupport.postgres();
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("relay.development-hosts", "localhost");
        var receiver = new LocalReceiver(9000);
        var context = SpringApplication.run(RelayForgeApplication.class, args);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    context.close();
                                    receiver.close();
                                    try {
                                        postgres.close();
                                    } catch (Exception ignored) {
                                    }
                                }));
        System.out.println(
                "Local demo is ready: API http://localhost:8080/swagger-ui.html, receiver http://localhost:9000.");
        System.out.println("This database is disposable. Stop with Ctrl+C.");
        // Keep the Maven exec launcher alive until the process is stopped.
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
