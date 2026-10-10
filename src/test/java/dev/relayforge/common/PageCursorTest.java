package dev.relayforge.common;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageCursorTest {
    @Test
    void retainsMicrosecondPrecision() {
        PageCursor cursor =
                new PageCursor(Instant.parse("2026-10-07T00:00:00.123456Z"), UUID.randomUUID());
        assertThat(PageCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void invalidCursorIsAClientError() {
        assertThatThrownBy(() -> PageCursor.decode("garbage")).isInstanceOf(ApiException.class);
    }
}
