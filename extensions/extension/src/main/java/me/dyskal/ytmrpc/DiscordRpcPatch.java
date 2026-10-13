package me.dyskal.ytmrpc;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** MediaSession supplies title, artist, duration and playback position even in background playback. */
public final class DiscordRpcPatch {
    public static final String DEFAULT_APPLICATION_ID = "1559278040175878146";
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final Object credentialsLock = new Object();
    private static volatile String status = "Not connected";
    private static volatile MediaMetadata metadata;
    private static volatile PlaybackState playback;
    private static boolean initialized;
    private static Context context;
    private static DiscordGateway gateway;
    private static volatile long connectionEpoch;
    private static long idleSince;
    private static String previousTrackKey;
    private static long previousStart;

    private DiscordRpcPatch() { }

    public static String getApplicationId() {
        return context == null ? DEFAULT_APPLICATION_ID
                : context.getSharedPreferences("dyskal_discord_settings", Context.MODE_PRIVATE)
                        .getString("application_id", DEFAULT_APPLICATION_ID);
    }

    public static void onSetMetadata(MediaMetadata value) {
        metadata = value;
    }

    public static void onSetPlaybackState(PlaybackState value) {
        playback = value;
    }

    /** Injected into MusicActivity.onCreate; no dependency on the official patches' extensions. */
    public static synchronized void initialize(Context value) {
        if (initialized) return;
        context = value.getApplicationContext();
        initialized = true;
        worker.scheduleWithFixedDelay(() -> {
            try { tick(); }
            catch (Exception ignored) { status = "Discord presence unavailable; reconnect in settings"; }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public static String status() { return status; }

    public static boolean isSharingEnabled() {
        return context != null && context.getSharedPreferences("dyskal_discord_settings", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    public static void setSharingEnabled(boolean enabled) {
        if (context != null) context.getSharedPreferences("dyskal_discord_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply();
    }

    private static void runOnMainThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }

    /** Connect saves the selected application ID and starts a fresh authorization for it. */
    public static void authorize(Context context, String applicationId) {
        worker.execute(() -> {
            try {
                String appId = applicationId.trim();
                String state = DiscordOAuth.randomSecret();
                String verifier = DiscordOAuth.randomSecret();
                String url = DiscordOAuth.authorizeUrl(appId, state, verifier);
                synchronized (credentialsLock) {
                    stopGateway();
                    setSharingEnabled(false);
                    context.getSharedPreferences("dyskal_discord_settings", Context.MODE_PRIVATE)
                            .edit().putString("application_id", appId).apply();
                    // Replaces any credentials or pending authorization belonging to the old application.
                    new DiscordTokenStore(context).write(new JSONObject().put("app_id", appId)
                            .put("state", state).put("verifier", verifier).put("pending_at", System.currentTimeMillis()));
                }
                status = "Waiting for Discord authorization";
                runOnMainThread(() -> {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { status = "Cannot open browser"; }
                });
            } catch (Exception ignored) { status = "Could not start OAuth. Check the application ID in Discord settings."; }
        });
    }

    public static void callback(Context context, Uri uri) {
        if (uri == null || !"ytmdiscord".equals(uri.getScheme()) || !"oauth2".equals(uri.getHost())
                || !"/callback".equals(uri.getPath())) return;
        worker.execute(() -> {
            synchronized (credentialsLock) {
                try {
                    DiscordTokenStore store = new DiscordTokenStore(context);
                    JSONObject pending = store.read();
                    if (!getApplicationId().equals(pending.optString("app_id"))) {
                        status = "OAuth callback belongs to a different Discord application";
                        return;
                    }
                    String expected = pending.optString("state");
                    String actual = uri.getQueryParameter("state");
                    long age = System.currentTimeMillis() - pending.optLong("pending_at");
                    if (expected.isEmpty() || actual == null || age < 0 || age > 300_000
                            || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                        status = "OAuth callback rejected: expired or invalid state";
                        return;
                    }
                    String appId = pending.getString("app_id");
                    String verifier = pending.getString("verifier");
                    // Consume once before contacting Discord; a duplicate callback cannot reuse it.
                    store.write(new JSONObject().put("app_id", appId));
                    String code = uri.getQueryParameter("code");
                    if (code == null || uri.getQueryParameter("error") != null) {
                        status = "Discord authorization cancelled";
                        return;
                    }
                    JSONObject result = new DiscordOAuth().exchange(appId, code, verifier);
                    saveTokens(store, appId, result, "");
                    setSharingEnabled(true);
                    status = "Authorized; play a track to share presence";
                } catch (Exception ignored) { status = "OAuth failed. Check Social SDK and redirect configuration."; }
            }
        });
    }

    public static void disconnect(Context context) {
        // Disable immediately so queued callbacks/ticks cannot reopen a connection.
        setSharingEnabled(false);
        worker.execute(() -> {
            synchronized (credentialsLock) {
                setSharingEnabled(false);
                new DiscordTokenStore(context).clear();
                stopGateway();
                status = "Not connected";
            }
        });
    }

    private static void saveTokens(DiscordTokenStore store, String appId, JSONObject result, String oldRefresh) throws Exception {
        store.write(new JSONObject().put("app_id", appId).put("access_token", result.getString("access_token"))
                .put("refresh_token", result.optString("refresh_token", oldRefresh))
                .put("expires_at", System.currentTimeMillis() + result.getLong("expires_in") * 1000));
    }

    private static String accessToken() throws Exception {
        synchronized (credentialsLock) {
            DiscordTokenStore store = new DiscordTokenStore(context);
            JSONObject saved = store.read();
            if (saved.optLong("expires_at") < System.currentTimeMillis() + 60_000) {
                String refresh = saved.optString("refresh_token");
                if (refresh.isEmpty()) throw new IllegalStateException("Reconnect required");
                JSONObject result = new DiscordOAuth().refresh(getApplicationId(), refresh);
                saveTokens(store, getApplicationId(), result, refresh);
                return result.getString("access_token");
            }
            return saved.getString("access_token");
        }
    }

    private static void tick() throws Exception {
        if (!isSharingEnabled()) { stopGateway(); return; }
        MediaMetadata track = metadata;
        PlaybackState state = playback;
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING
                && track != null && track.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
        long now = SystemClock.elapsedRealtime();
        long duration = track == null ? 0 : track.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? -1 : state.getPosition();
        if (playing && position >= 0 && state.getLastPositionUpdateTime() > 0) {
            position += (long) (Math.max(0, now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        if (duration > 0 && position >= duration) playing = false;
        if (!playing) {
            if (gateway != null) {
                gateway.update(DiscordPresence.update(null), "clear");
                if (idleSince == 0) idleSince = now;
                if (now - idleSince > 60_000) stopGateway();
            }
            return;
        }
        idleSince = 0;
        if (gateway == null) {
            synchronized (credentialsLock) {
                JSONObject saved = new DiscordTokenStore(context).read();
                if (saved.optString("access_token").isEmpty()) return;
            }
            long epoch = ++connectionEpoch;
            gateway = new DiscordGateway(getApplicationId(), DiscordRpcPatch::accessToken, value -> {
                if (epoch == connectionEpoch) status = value;
            });
            gateway.start();
        }
        String title = track.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = track.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String id = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        long wallNow = System.currentTimeMillis();
        JSONObject activity = DiscordPresence.track(getApplicationId(), title, artist, id,
                duration, position, state.getPlaybackSpeed(), wallNow);
        String trackKey = title + '\n' + artist + '\n' + id + '\n' + duration + '\n' + state.getPlaybackSpeed();
        long start = position < 0 ? -1 : wallNow - position;
        if (!trackKey.equals(previousTrackKey) || Math.abs(start - previousStart) > 2000) {
            previousTrackKey = trackKey;
            previousStart = start;
        }
        String key = trackKey + '\n' + previousStart;
        gateway.update(DiscordPresence.update(activity), key);
    }

    private static void stopGateway() {
        if (gateway != null) {
            ++connectionEpoch;
            gateway.close(); gateway = null;
            status = "Disconnected";
        }
        idleSince = 0;
    }
}
