package me.dyskal.ytmrpc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpTest {
    @Test public void utf8ReadsAllowTheByteLimitAndRejectOverflowWithoutLeakingContent() throws Exception {
        byte[] body = "🎵SECRET".getBytes(StandardCharsets.UTF_8);
        assertEquals("🎵SECRET", Http.readLimited(new ByteArrayInputStream(body), body.length));
        DiscordFailure error = assertThrows(DiscordFailure.class,
                () -> Http.readLimited(new ByteArrayInputStream(body), body.length - 1));
        assertEquals(DiscordFailure.Kind.PROTOCOL, error.kind);
        assertFalse(error.getMessage().contains("SECRET"));
        assertEquals("", Http.readLimited(new ByteArrayInputStream(new byte[0]), 0));
    }

    @Test public void retryAfterKeepsCallerMinimumsForMissingInvalidOrShortValues() {
        assertEquals(0, Http.retryAfterMillis(null, 0));
        assertEquals(60_000, Http.retryAfterMillis("invalid", 60_000));
        assertEquals(60_000, Http.retryAfterMillis("-1", 60_000));
        assertEquals(60_000, Http.retryAfterMillis("3", 60_000));
        assertEquals(3500, Http.retryAfterMillis("3.5", 0));
        assertEquals(90_000, Http.retryAfterMillis("90", 60_000));
    }
}
