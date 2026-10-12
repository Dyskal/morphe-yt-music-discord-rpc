package app.dyskal.ytmrpc;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordProtocolTest {
    private static final String APP = "123456789012345678";

    @Test void pkceMatchesRfc7636() throws Exception {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                DiscordOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String url = DiscordOAuth.authorizeUrl(APP, "state", DiscordOAuth.randomSecret());
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("scope=openid+sdk.social_layer_presence"));
        assertFalse(url.contains("client_secret"));
        assertThrows(IOException.class, () -> DiscordOAuth.authorizeUrl("bad", "state", "verifier"));
    }

    @Test void codeExchangeAndRefreshUsePublicClientForms() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(tokenResponse("access-one", "refresh-one"));
            server.enqueue(tokenResponse("access-two", "refresh-two"));
            DiscordOAuth oauth = new DiscordOAuth(server.url("/token").toString());
            assertEquals("access-one", oauth.exchange(APP, "code+with&symbols", "verifier").getString("access_token"));
            String form = server.takeRequest().getBody().readUtf8();
            assertTrue(form.contains("code=code%2Bwith%26symbols"));
            assertTrue(form.contains("code_verifier=verifier"));
            assertTrue(form.contains("redirect_uri=ytmdiscord%3A%2F%2Foauth2%2Fcallback"));
            assertFalse(form.contains("client_secret"));
            assertEquals("access-two", oauth.refresh(APP, "refresh-one").getString("access_token"));
            assertTrue(server.takeRequest().getBody().readUtf8().contains("grant_type=refresh_token"));
        }
    }

    @Test void rejectsMissingScopeAndDoesNotLeakServerResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"secret\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"openid\"}"));
            server.enqueue(new MockResponse().setResponseCode(400).setBody("SECRET ACCOUNT DATA"));
            DiscordOAuth oauth = new DiscordOAuth(server.url("/token").toString());
            assertThrows(IOException.class, () -> oauth.exchange(APP, "code", "verifier"));
            Exception error = assertThrows(IOException.class, () -> oauth.exchange(APP, "code", "verifier"));
            assertEquals("Discord OAuth HTTP 400", error.getMessage());
        }
    }

    @Test void playbackPayloadSupportsProgressLinksAndUnicode() throws Exception {
        JSONObject track = DiscordPresence.track(APP, "🎵".repeat(150), "Artist", "dQw4w9WgXcQ", 180_000, 30_000, 1, 1_000_000);
        assertEquals(2, track.getInt("type"));
        assertEquals(APP, track.getString("application_id"));
        assertEquals(128, track.getString("details").codePointCount(0, track.getString("details").length()));
        assertEquals(970_000, track.getJSONObject("timestamps").getLong("start"));
        assertEquals(1_150_000, track.getJSONObject("timestamps").getLong("end"));
        assertTrue(track.getJSONObject("metadata").getJSONArray("button_urls").getString(0).endsWith("dQw4w9WgXcQ"));
        assertFalse(DiscordPresence.track(APP, "Title", null, "invalid", 0, -1, 1, 0).has("timestamps"));
        assertFalse(DiscordPresence.track(APP, "Title", null, null, 1000, 0, 2, 0).has("timestamps"));
        assertEquals(0, new JSONObject(DiscordPresence.update(null)).getJSONObject("d").getJSONArray("activities").length());
    }

    @Test void gatewayAuthenticatesHeartbeatsCoalescesAndClears() throws Exception {
        BlockingQueue<JSONObject> frames = new LinkedBlockingQueue<>();
        BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(upgrade(frames, true, 60_000));
            try (DiscordGateway gateway = new DiscordGateway(APP, () -> "oauth-access", statuses::add, server.url("/gateway").toString())) {
                gateway.update(DiscordPresence.update(new JSONObject().put("name", "first")), "first");
                gateway.start();
                JSONObject identify = awaitOp(frames, 2, 5);
                assertEquals("Bearer oauth-access", identify.getJSONObject("d").getString("token"));
                assertEquals("first", awaitOp(frames, 3, 5).getJSONObject("d").getJSONArray("activities").getJSONObject(0).getString("name"));
                // Rapid skips are merged, not emitted as a burst exceeding Discord's limit.
                gateway.update(DiscordPresence.update(new JSONObject().put("name", "intermediate")), "intermediate");
                gateway.update(DiscordPresence.update(new JSONObject().put("name", "latest")), "latest");
                JSONObject latest = awaitOp(frames, 3, 7);
                assertEquals("latest", latest.getJSONObject("d").getJSONArray("activities").getJSONObject(0).getString("name"));
                gateway.close();
                assertEquals(0, awaitOp(frames, 3, 5).getJSONObject("d").getJSONArray("activities").length());
            }
        }
    }

    @Test void missingHeartbeatAckReconnectsAndRepublishesLatestTrack() throws Exception {
        BlockingQueue<JSONObject> frames = new LinkedBlockingQueue<>();
        AtomicInteger connections = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(upgrade(frames, false, 1000));
            server.enqueue(upgrade(frames, true, 60_000));
            try (DiscordGateway gateway = new DiscordGateway(APP, () -> { connections.incrementAndGet(); return "access"; },
                    status -> { }, server.url("/gateway").toString())) {
                gateway.update(DiscordPresence.update(new JSONObject().put("name", "track")), "track");
                gateway.start();
                awaitOp(frames, 3, 5);
                awaitOp(frames, 1, 3);
                JSONObject republished = awaitOp(frames, 3, 8);
                assertEquals("track", republished.getJSONObject("d").getJSONArray("activities").getJSONObject(0).getString("name"));
                assertEquals(2, connections.get());
            }
        }
    }

    @Test void fatalAuthorizationDoesNotReconnectForever() throws Exception {
        BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        AtomicInteger attempts = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.close(4004, "credential-containing reason must not be displayed");
                }
            }));
            try (DiscordGateway gateway = new DiscordGateway(APP, () -> { attempts.incrementAndGet(); return "token"; }, statuses::add,
                    server.url("/gateway").toString())) {
                gateway.start();
                String status;
                do { status = statuses.poll(5, TimeUnit.SECONDS); assertNotNull(status); }
                while (!status.contains("rejected"));
                assertFalse(status.contains("credential-containing"));
                assertNull(statuses.poll(1500, TimeUnit.MILLISECONDS));
                assertEquals(0, attempts.get());
            }
        }
    }

    private static MockResponse tokenResponse(String access, String refresh) {
        return new MockResponse().setBody("{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
                + "\",\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"openid sdk.social_layer_presence\"}");
    }

    private static MockResponse upgrade(BlockingQueue<JSONObject> frames, boolean ack, long interval) {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send("{\"op\":10,\"d\":{\"heartbeat_interval\":" + interval + "}}");
            }
            @Override public void onMessage(WebSocket ws, String text) {
                JSONObject frame = new JSONObject(text);
                frames.add(frame);
                if (frame.getInt("op") == 2) ws.send("{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{}}");
                if (frame.getInt("op") == 1 && ack) ws.send("{\"op\":11,\"d\":null}");
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); }
        });
    }

    private static JSONObject awaitOp(BlockingQueue<JSONObject> frames, int op, int timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            JSONObject frame = frames.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(frame, "Timed out waiting for opcode " + op);
            if (frame.getInt("op") == op) return frame;
        }
        fail("Missing opcode " + op);
        return null;
    }
}
