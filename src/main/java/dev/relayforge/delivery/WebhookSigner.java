package dev.relayforge.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSigner {
    private WebhookSigner() {}

    public static String sign(String secret, long timestamp, UUID deliveryId, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = timestamp + "." + deliveryId + "." + body;
            return "t="
                    + timestamp
                    + ",v1="
                    + HexFormat.of()
                            .formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook signing failed", e);
        }
    }
}
