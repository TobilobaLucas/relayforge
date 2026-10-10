package dev.relayforge.security;

import dev.relayforge.config.RelayProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretVault {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretVault(RelayProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RELAY_ENCRYPTION_KEY must be Base64", e);
        }
        if (decoded.length != 32)
            throw new IllegalArgumentException("RELAY_ENCRYPTION_KEY must encode exactly 32 bytes");
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String secret, UUID endpointId) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, endpointId);
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(
                            ByteBuffer.allocate(iv.length + ciphertext.length)
                                    .put(iv)
                                    .put(ciphertext)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(String encoded, UUID endpointId) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            return new String(
                    cipher(Cipher.DECRYPT_MODE, iv, endpointId).doFinal(ciphertext),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    private Cipher cipher(int mode, byte[] iv, UUID endpointId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(endpointId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
