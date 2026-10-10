package dev.relayforge.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public record PageCursor(Instant createdAt, UUID id) {
    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static PageCursor decode(String value) {
        if (value == null) return null;
        try {
            if (value.length() > 200) throw new IllegalArgumentException();
            String[] parts =
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                            .split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new PageCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_cursor",
                    "Use the nextCursor returned by the previous page.");
        }
    }
}
