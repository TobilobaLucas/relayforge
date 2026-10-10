package dev.relayforge.security;

import static org.assertj.core.api.Assertions.*;

import dev.relayforge.TestSupport;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretVaultTest {
    private final SecretVault vault = new SecretVault(TestSupport.properties(Set.of()));

    @Test
    void roundTripsWithoutStoringPlaintextAndUsesFreshNonces() {
        UUID endpoint = UUID.randomUUID();
        String first = vault.encrypt("whsec_sensitive", endpoint);
        assertThat(first)
                .doesNotContain("sensitive")
                .isNotEqualTo(vault.encrypt("whsec_sensitive", endpoint));
        assertThat(vault.decrypt(first, endpoint)).isEqualTo("whsec_sensitive");
    }

    @Test
    void rejectsCiphertextMovedToAnotherEndpoint() {
        String value = vault.encrypt("secret", UUID.randomUUID());
        assertThatThrownBy(() -> vault.decrypt(value, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsModifiedCiphertext() {
        UUID endpoint = UUID.randomUUID();
        byte[] value = Base64.getDecoder().decode(vault.encrypt("secret", endpoint));
        value[value.length - 1] ^= 1;
        assertThatThrownBy(() -> vault.decrypt(Base64.getEncoder().encodeToString(value), endpoint))
                .isInstanceOf(IllegalStateException.class);
    }
}
