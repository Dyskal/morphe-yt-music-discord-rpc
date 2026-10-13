package me.dyskal.ytmrpc;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** All mutable connection state belongs to one worker. Stale socket callbacks are ignored. */
public final class DiscordGateway implements AutoCloseable {
    public interface TokenProvider { String get() throws Exception; }
    public interface StatusListener { void changed(String status); }
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Discord presence");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();
    private final TokenProvider tokens;
    private final StatusListener status;
    private final String appId;
    private final String url;
    private WebSocket socket;
    private long generation;
    private boolean enabled, ready, awaitingAck;
    private int attempts;
    private Object sequence = JSONObject.NULL;
    private String desired, desiredKey, sentKey;
    private long lastSentNanos;
    private ScheduledFuture<?> heartbeat, watchdog, retry, flush;

    public DiscordGateway(String appId, TokenProvider tokens, StatusListener status) {
        this(appId, tokens, status, "wss://gateway.discord.gg/?v=10&encoding=json");
    }

    DiscordGateway(String appId, TokenProvider tokens, StatusListener status, String url) {
        this.appId = appId;
        this.tokens = tokens;
        this.status = status;
        this.url = url;
    }

    public void start() {
        worker.execute(() -> {
            if (enabled) return;
            enabled = true;
            attempts = 0;
            connect();
        });
    }

    public void update(String presence, String key) {
        worker.execute(() -> {
            desired = presence;
            desiredKey = key;
            sendLatest();
        });
    }

    private void connect() {
        if (!enabled) return;
        long id = ++generation;
        ready = false;
        awaitingAck = false;
        sequence = JSONObject.NULL;
        sentKey = null;
        status.changed("Connecting to Discord");
        socket = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                dispatch(() -> {
                    if (id != generation || !enabled) return;
                    try { frame(text); }
                    catch (Exception ignored) { failed(id, 0); }
                });
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
                dispatch(() -> failed(id, code));
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                dispatch(() -> failed(id, code));
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                // Neither server reasons nor exceptions are displayed; they can contain secrets.
                dispatch(() -> failed(id, response == null ? 0 : response.code()));
            }
        });
        watchdog = worker.schedule(() -> failed(id, 0), 30, TimeUnit.SECONDS);
    }

    private void dispatch(Runnable action) {
        if (!worker.isShutdown()) {
            try { worker.execute(action); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }

    private void frame(String text) throws Exception {
        JSONObject frame = new JSONObject(text);
        if (frame.has("s") && !frame.isNull("s")) sequence = frame.get("s");
        switch (frame.getInt("op")) {
            case 10 -> {
                long interval = frame.getJSONObject("d").getLong("heartbeat_interval");
                if (interval < 1000 || interval > 120_000) throw new IllegalStateException();
                cancel(heartbeat);
                heartbeat = worker.scheduleAtFixedRate(() -> {
                    if (awaitingAck) { failed(generation, 0); return; }
                    sendHeartbeat();
                }, (long) (Math.random() * interval), interval, TimeUnit.MILLISECONDS);
                // Like Metrolist, authenticate with an OAuth bearer, not an account token.
                String token = tokens.get();
                JSONObject data = new JSONObject().put("token", "Bearer " + token).put("intents", 0)
                        .put("compress", false).put("properties", new JSONObject()
                                .put("os", "android").put("browser", "Discord Android").put("device", appId));
                send(new JSONObject().put("op", 2).put("d", data).toString());
            }
            case 0 -> {
                if ("READY".equals(frame.optString("t"))) {
                    ready = true;
                    attempts = 0;
                    cancel(watchdog);
                    status.changed("Connected to Discord");
                    sendLatest();
                }
            }
            case 1 -> sendHeartbeat();
            case 11 -> awaitingAck = false;
            case 7, 9 -> failed(generation, 0);
            default -> { }
        }
    }

    private void sendHeartbeat() {
        try {
            send(new JSONObject().put("op", 1).put("d", sequence).toString());
            awaitingAck = true;
        } catch (Exception ignored) { failed(generation, 0); }
    }

    private void sendLatest() {
        if (!ready || desired == null || java.util.Objects.equals(desiredKey, sentKey)) return;
        // Discord allows five presence updates per 20s. Coalesce seeks/rapid skips to the latest.
        long remaining = 4_100 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastSentNanos);
        if (lastSentNanos != 0 && remaining > 0) {
            if (flush == null || flush.isDone()) flush = worker.schedule(this::sendLatest, remaining, TimeUnit.MILLISECONDS);
            return;
        }
        if (socket != null && socket.send(desired)) {
            sentKey = desiredKey;
            lastSentNanos = System.nanoTime();
        } else {
            failed(generation, 0);
        }
    }

    private void send(String text) {
        if (socket == null || !socket.send(text)) throw new IllegalStateException("Discord socket closed");
    }

    private void failed(long id, int code) {
        if (id != generation || !enabled) return;
        ++generation;
        ready = false;
        cancel(heartbeat);
        cancel(watchdog);
        cancel(flush);
        if (socket != null) socket.cancel();
        socket = null;
        if (code == 4004 || code == 4010 || code == 4011 || code == 4013 || code == 4014) {
            enabled = false;
            status.changed("Discord rejected authorization (" + code + "). Reconnect in settings.");
            return;
        }
        long delay = Math.min(60, 1L << Math.min(attempts++, 6));
        status.changed("Discord disconnected; retrying in " + delay + "s");
        retry = worker.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }

    @Override public void close() {
        dispatch(() -> {
            enabled = false;
            ++generation;
            cancel(heartbeat);
            cancel(watchdog);
            cancel(retry);
            cancel(flush);
            if (socket != null) {
                try { if (ready) socket.send(DiscordPresence.update(null)); }
                catch (Exception ignored) { }
                socket.close(1000, null);
            }
            ready = false;
            socket = null;
            status.changed("Disconnected");
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdown();
            worker.shutdown();
        });
    }
}
