package dev.relayforge;

import dev.relayforge.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        exclude =
                org.springframework.boot.autoconfigure.security.servlet
                        .UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(RelayProperties.class)
public class RelayForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayForgeApplication.class, args);
    }
}
