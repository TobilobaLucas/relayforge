package dev.relayforge;

import dev.relayforge.config.RelayProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

public final class TestSupport {
    public static final String ADMIN_TOKEN = "operator-token-for-tests-only-123456789";
    public static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private TestSupport() {}

    public static RelayProperties properties(Set<String> developmentHosts) {
        return new RelayProperties(
                ADMIN_TOKEN,
                ENCRYPTION_KEY,
                16,
                5,
                Duration.ofSeconds(45),
                Duration.ofSeconds(10),
                10000,
                1000,
                65536,
                developmentHosts);
    }

    public static EmbeddedPostgres postgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).setServerConfig("timezone", "UTC").start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
