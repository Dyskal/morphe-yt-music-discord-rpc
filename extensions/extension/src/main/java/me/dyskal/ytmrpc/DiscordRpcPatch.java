package me.dyskal.ytmrpc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Application-level hooks cover both foreground and service-only playback. */
public final class DiscordRpcPatch {
    public static final String DEFAULT_APPLICATION_ID = "1559278040175878146";
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final CopyOnWriteArrayList<WeakReference<Runnable>> settingsListeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final DiscordMetadataState trackMetadata = new DiscordMetadataState();
    private static volatile String status = "Sharing is off";
    private static volatile boolean initialized;
    private static Context applicationContext;
    private static SharedPreferences preferences;
    private static MediaMetadata metadata;
    private static PlaybackState playback;
    private static volatile DiscordLocalRpc localRpc;
    private static volatile long connectionEpoch;
    private static ScheduledFuture<?> ticker, idleClose;
    private static String previousTrackKey;
    private static long previousStart;

    private DiscordRpcPatch() { }

    public static String getApplicationId() {
        return preferences == null ? DEFAULT_APPLICATION_ID : preferences.getString("application_id", DEFAULT_APPLICATION_ID);
    }
    public static String status() { return status; }
    public static boolean usesArtistHeadline() { return preferences == null || "artist".equals(preferences.getString("headline", "artist")); }
    public static boolean isSharingEnabled() { return preferences != null && preferences.getBoolean("enabled", false); }
    public static void addSettingsListener(Runnable listener) {
        for (WeakReference<Runnable> entry : settingsListeners) if (entry.get() == null) settingsListeners.remove(entry);
        settingsListeners.add(new WeakReference<>(listener));
    }
    private static void notifySettings() {
        main.post(() -> {
            for (WeakReference<Runnable> entry : settingsListeners) {
                Runnable listener = entry.get();
                if (listener == null) settingsListeners.remove(entry);
                else listener.run();
            }
        });
    }
    private static void setStatus(String value) { status = value; notifySettings(); }

    public static synchronized void initialize(Context value) {
        if (initialized) return;
        DiscordDiagnostics.enableAndroidLogging();
        applicationContext = value.getApplicationContext();
        // A fresh local-only preference store keeps v1's sharing flag and credentials out of v2.
        preferences = applicationContext.getSharedPreferences("dyskal_discord_local_settings", Context.MODE_PRIVATE);
        initialized = true;
        worker.execute(() -> {
            setStatus(isSharingEnabled() ? "Waiting for playback; open Discord and sign in there" : "Sharing is off");
            reconcileSafely();
        });
    }

    public static void onSetMetadata(MediaMetadata value) {
        worker.execute(() -> {
            metadata = value;
            trackMetadata.metadata(value == null ? null : DiscordTrack.from(value));
            if (initialized && isSharingEnabled()) reconcileSafely();
        });
    }
    public static void onSetPlaybackState(PlaybackState value) {
        worker.execute(() -> {
            playback = value;
            if (initialized && isSharingEnabled()) reconcileSafely();
        });
    }
    public static void onNativeVideoId(String videoId) {
        if (DiscordTrack.videoId(videoId) == null) return;
        worker.execute(() -> {
            if (trackMetadata.videoId(videoId) && initialized && isSharingEnabled()) reconcileSafely();
        });
    }
    public static void onNativeQueueMetadata(byte[] renderer) {
        if (renderer == null || renderer.length > 1_048_576) return;
        worker.execute(() -> {
            try {
                DiscordNativeMetadata data = DiscordNativeMetadata.parse(renderer);
                if (data == null) return;
                if (trackMetadata.nativeMetadata(data)) DiscordDiagnostics.info("Native track metadata: video=true, artist=" + (data.artistId != null)
                        + ", album=" + (data.albumId != null) + ", cover=" + (data.artworkUrl != null));
                if (initialized && isSharingEnabled()) reconcileSafely();
            } catch (Exception error) { DiscordDiagnostics.failure("Decode native track metadata", error); }
        });
    }

    public static void setSharingEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean("enabled", enabled).apply(); notifySettings();
        if (!enabled) closeActiveConnection();
        worker.execute(() -> {
            reconcileSafely();
            if (!isSharingEnabled()) setStatus("Sharing is off");
        });
    }

    public static void reconnect() {
        if (preferences == null) return;
        preferences.edit().putBoolean("enabled", true).apply(); notifySettings();
        worker.execute(() -> {
            stopAll(); setStatus("Waiting for playback; open Discord and sign in there"); reconcileSafely();
        });
    }

    public static boolean setApplicationId(String value) {
        String id = value == null ? "" : value.trim();
        if (!DiscordLocalRpc.isValidApplicationId(id) || preferences == null) return false;
        if (id.equals(getApplicationId())) return true;
        preferences.edit().putString("application_id", id).putBoolean("enabled", false).apply();
        closeActiveConnection(); notifySettings();
        worker.execute(() -> { stopAll(); setStatus("Application ID saved; enable sharing to reconnect"); });
        return true;
    }

    public static void setArtistHeadline(boolean artist) {
        if (preferences == null) return;
        preferences.edit().putString("headline", artist ? "artist" : "application").apply();
        notifySettings(); worker.execute(DiscordRpcPatch::reconcileSafely);
    }

    private static void reconcileSafely() {
        try { reconcile(); }
        catch (Exception error) { report("Presence update", error); }
    }

    private static void reconcile() throws Exception {
        if (!isSharingEnabled()) { stopAll(); return; }
        if (!trackMetadata.isCurrent()) {
            stopTicker();
            if (localRpc != null) localRpc.update(null, "clear");
            return;
        }
        MediaMetadata track = metadata;
        PlaybackState state = playback;
        String title = track == null ? null : track.getString(MediaMetadata.METADATA_KEY_TITLE);
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING
                && title != null && !title.trim().isEmpty();
        long duration = track == null ? 0 : track.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? -1 : state.getPosition();
        if (playing && position >= 0 && state.getLastPositionUpdateTime() > 0) {
            position += (long) (Math.max(0, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        if (duration > 0 && position >= duration) playing = false;
        if (!playing) {
            stopTicker();
            if (localRpc != null) {
                localRpc.update(null, "clear");
                if (idleClose == null) idleClose = worker.schedule(() -> {
                    stopConnection(); idleClose = null; setStatus("Waiting for playback");
                }, 60, TimeUnit.SECONDS);
            }
            return;
        }
        cancel(idleClose); idleClose = null;
        // Clear an expired track even if the player never delivers another callback.
        if (ticker == null) ticker = worker.scheduleWithFixedDelay(DiscordRpcPatch::reconcileSafely, 1, 1, TimeUnit.SECONDS);
        if (localRpc == null) {
            long epoch = ++connectionEpoch;
            localRpc = new DiscordLocalRpc(applicationContext, getApplicationId(), new DiscordLocalRpc.StatusListener() {
                @Override public void changed(String value) { if (epoch == connectionEpoch) setStatus(value); }
                @Override public void failed(DiscordFailure error) {
                    worker.execute(() -> {
                        if (epoch != connectionEpoch) return;
                        preferences.edit().putBoolean("enabled", false).apply();
                        stopAll(); report("Discord connection", error);
                    });
                }
            });
            localRpc.start();
        }
        DiscordTrack snapshot = trackMetadata.snapshot();
        long wallNow = System.currentTimeMillis();
        JSONObject activity = DiscordPresence.track(snapshot, usesArtistHeadline(), duration, position, state.getPlaybackSpeed(), wallNow);
        String trackKey = snapshot.key() + '\n' + usesArtistHeadline() + '\n' + duration + '\n' + state.getPlaybackSpeed();
        long start = activity.has("timestamps") ? wallNow - position : -1;
        if (!trackKey.equals(previousTrackKey) || Math.abs(start - previousStart) > 2000) { previousTrackKey = trackKey; previousStart = start; }
        localRpc.update(activity, trackKey + '\n' + previousStart);
    }

    private static void closeActiveConnection() { DiscordLocalRpc active = localRpc; if (active != null) active.close(); }
    private static void stopConnection() {
        if (localRpc != null) { ++connectionEpoch; localRpc.close(); localRpc = null; }
    }
    private static void stopAll() { stopConnection(); stopTicker(); cancel(idleClose); idleClose = null; }
    private static void stopTicker() { cancel(ticker); ticker = null; }
    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    private static void report(String operation, Throwable error) {
        DiscordDiagnostics.failure(operation, error); setStatus(operation + ": " + DiscordFailure.describe(error));
    }
}
