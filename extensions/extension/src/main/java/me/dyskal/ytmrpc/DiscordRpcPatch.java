package me.dyskal.ytmrpc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Application-level initialization covers normal and service-only playback starts. */
public final class DiscordRpcPatch {
    public static final String DEFAULT_APPLICATION_ID = "1559278040175878146";
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService authorizationIO = Executors.newSingleThreadExecutor();
    private static final DiscordAuthorization authorization = new DiscordAuthorization();
    private static final AtomicReference<DiscordOAuth> exchangeRequest = new AtomicReference<>();
    private static final Object credentialsLock = new Object();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile String status = "Not connected";
    private static volatile MediaMetadata metadata;
    private static volatile PlaybackState playback;
    private static volatile boolean initialized, authorized;
    private static SharedPreferences preferences;
    private static DiscordTokenStore tokenStore;
    private static JSONObject credentials = new JSONObject();
    private static long credentialsEpoch;
    private static volatile DiscordGateway gateway;
    private static volatile long connectionEpoch;
    private static ScheduledFuture<?> ticker, idleClose, authorizationTimeout;
    private static String previousTrackKey;
    private static long previousStart;

    private DiscordRpcPatch() { }

    public static String getApplicationId() {
        return preferences == null ? DEFAULT_APPLICATION_ID : preferences.getString("application_id", DEFAULT_APPLICATION_ID);
    }
    public static String status() { return status; }
    public static boolean isAuthorized() { return authorized; }
    public static boolean isAuthorizing() { return authorization.isBusy(); }
    public static boolean isSharingEnabled() { return preferences != null && preferences.getBoolean("enabled", false); }

    public static synchronized void initialize(Context value) {
        if (initialized) return;
        DiscordDiagnostics.enableAndroidLogging();
        Context context = value.getApplicationContext();
        preferences = context.getSharedPreferences("dyskal_discord_settings", Context.MODE_PRIVATE);
        tokenStore = new DiscordTokenStore(context);
        initialized = true;
        worker.execute(() -> {
            try {
                JSONObject saved;
                synchronized (credentialsLock) {
                    credentials = tokenStore.read();
                    updateAuthorized();
                    saved = copyCredentials();
                }
                DiscordAuthorization.Attempt restored = authorization.restore(saved.optJSONObject("pending"), System.currentTimeMillis());
                if (restored != null) {
                    scheduleAuthorizationTimeout(restored);
                    status = "Waiting for Discord authorization";
                } else {
                    if (saved.has("pending") && !authorization.isBusy()) removePending(null);
                    status = authorized ? "Signed in; waiting for playback" : "Not connected";
                }
                reconcileSafely();
            } catch (Exception error) { report("Load Discord credentials", error); }
        });
    }

    public static void onSetMetadata(MediaMetadata value) {
        metadata = value;
        if (initialized && isSharingEnabled()) worker.execute(DiscordRpcPatch::reconcileSafely);
    }
    public static void onSetPlaybackState(PlaybackState value) {
        playback = value;
        if (initialized && isSharingEnabled()) worker.execute(DiscordRpcPatch::reconcileSafely);
    }

    public static void setSharingEnabled(boolean enabled) {
        if (preferences == null) return;
        preferences.edit().putBoolean("enabled", enabled && authorized).apply();
        if (!enabled) {
            DiscordGateway active = gateway;
            if (active != null) active.close();
        }
        worker.execute(() -> {
            reconcileSafely();
            if (!enabled) status = authorized ? "Signed in; sharing is off" : "Not connected";
        });
    }

    /** Duplicate taps are ignored immediately; an existing grant reconnects without browser login. */
    public static void authorize(Context context, String applicationId) {
        initialize(context);
        String appId = applicationId.trim();
        if (!DiscordOAuth.isValidApplicationId(appId)) {
            report("Connect", new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "Enter a valid 17–20 digit application ID")); return;
        }
        DiscordAuthorization.Attempt attempt = authorization.begin(appId, System.currentTimeMillis());
        if (attempt == null) return;
        worker.execute(() -> {
            try {
                if (!authorization.isCurrent(attempt)) return;
                boolean reusable;
                synchronized (credentialsLock) {
                    reusable = appId.equals(getApplicationId()) && appId.equals(credentials.optString("app_id"))
                            && !credentials.optString("access_token").isEmpty()
                            && (credentials.optLong("expires_at") > System.currentTimeMillis() || !credentials.optString("refresh_token").isEmpty());
                }
                if (reusable) {
                    synchronized (authorization) {
                        if (!authorization.isCurrent(attempt)) return;
                        removePending(null);
                        authorization.finish(attempt);
                        preferences.edit().putBoolean("enabled", true).apply();
                    }
                    if (gateway == null) status = "Signed in; waiting for playback";
                    reconcileSafely();
                    return;
                }
                String url = DiscordOAuth.authorizeUrl(appId, attempt.state, attempt.verifier);
                synchronized (authorization) {
                    if (!authorization.isCurrent(attempt)) return;
                    synchronized (credentialsLock) {
                        JSONObject next = copyCredentials().put("pending", attempt.toJson());
                        tokenStore.write(next);
                        credentials = next;
                    }
                    preferences.edit().putBoolean("enabled", false).apply();
                    stopGateway(); stopTicker(); cancel(idleClose); idleClose = null;
                    scheduleAuthorizationTimeout(attempt);
                }
                status = "Waiting for Discord authorization";
                main.post(() -> {
                    if (!authorization.isCurrent(attempt)) return;
                    try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (ActivityNotFoundException error) {
                        worker.execute(() -> finishAuthorization(attempt,
                                new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "No browser is available for Discord sign-in")));
                    } catch (Exception error) { worker.execute(() -> finishAuthorization(attempt, error)); }
                });
            } catch (Exception error) { finishAuthorization(attempt, error); }
        });
    }

    public static void callback(Context context, Uri uri) {
        initialize(context);
        if (uri == null || !"ytmdiscord".equals(uri.getScheme()) || !"oauth2".equals(uri.getHost()) || !"/callback".equals(uri.getPath())) return;
        worker.execute(() -> {
            DiscordAuthorization.Attempt attempt;
            try { attempt = authorization.claim(uri.getQueryParameter("state"), System.currentTimeMillis()); }
            catch (Exception error) { report("OAuth callback", error); return; }
            String code = uri.getQueryParameter("code"), errorCode = uri.getQueryParameter("error");
            if (errorCode != null || code == null) {
                finishAuthorization(attempt, new DiscordFailure("invalid_scope".equals(errorCode)
                        ? DiscordFailure.Kind.CONFIGURATION : DiscordFailure.Kind.CANCELLED,
                        "invalid_scope".equals(errorCode) ? "Discord denied the presence scope; check Social SDK configuration" : "Discord authorization cancelled"));
                return;
            }
            authorizationIO.execute(() -> {
                DiscordOAuth request = new DiscordOAuth();
                exchangeRequest.set(request);
                try {
                    if (!authorization.isCurrent(attempt)) { request.cancel(); return; }
                    JSONObject result = request.exchange(attempt.appId, code, attempt.verifier);
                    worker.execute(() -> completeAuthorization(attempt, result));
                } catch (Exception error) { worker.execute(() -> finishAuthorization(attempt, error)); }
                finally { exchangeRequest.compareAndSet(request, null); }
            });
        });
    }

    public static void cancelAuthorization() {
        DiscordAuthorization.Attempt attempt = authorization.cancel();
        DiscordOAuth request = exchangeRequest.getAndSet(null);
        if (request != null) request.cancel();
        if (attempt == null) return;
        worker.execute(() -> {
            try {
                if (removePending(attempt)) { cancel(authorizationTimeout); status = "Authorization cancelled"; }
            } catch (Exception error) { report("Cancel authorization", error); }
        });
    }

    public static void disconnect(Context context) {
        initialize(context);
        authorization.cancel();
        DiscordOAuth request = exchangeRequest.getAndSet(null);
        if (request != null) request.cancel();
        DiscordGateway active = gateway;
        if (active != null) active.close();
        preferences.edit().putBoolean("enabled", false).apply();
        synchronized (credentialsLock) { ++credentialsEpoch; }
        worker.execute(() -> {
            stopGateway(); stopTicker(); cancel(idleClose); idleClose = null; cancel(authorizationTimeout);
            try {
                synchronized (credentialsLock) {
                    tokenStore.clear(); credentials = new JSONObject(); updateAuthorized();
                }
                status = "Not connected";
            } catch (Exception error) { report("Forget Discord account", error); }
        });
    }

    private static void completeAuthorization(DiscordAuthorization.Attempt attempt, JSONObject result) {
        try {
            synchronized (authorization) {
                if (!authorization.isCurrent(attempt)) return;
                synchronized (credentialsLock) {
                    JSONObject next = tokenData(attempt.appId, result, "");
                    tokenStore.write(next);
                    preferences.edit().putString("application_id", attempt.appId).putBoolean("enabled", true).apply();
                    credentials = next; ++credentialsEpoch; updateAuthorized();
                }
                authorization.finish(attempt);
            }
            cancel(authorizationTimeout);
            status = "Signed in; play a track to share presence";
            reconcileSafely();
        } catch (Exception error) { finishAuthorization(attempt, error); }
    }

    private static void finishAuthorization(DiscordAuthorization.Attempt attempt, Throwable error) {
        if (!authorization.finish(attempt)) return;
        cancel(authorizationTimeout);
        try { removePending(attempt); }
        catch (Exception storageError) { report("Clear pending authorization", storageError); }
        report("Discord sign-in", error);
    }

    private static void scheduleAuthorizationTimeout(DiscordAuthorization.Attempt attempt) {
        cancel(authorizationTimeout);
        long remaining = Math.max(1, DiscordAuthorization.TIMEOUT_MILLIS - (System.currentTimeMillis() - attempt.startedAt));
        authorizationTimeout = worker.schedule(() -> {
            if (!authorization.isCurrent(attempt)) return;
            DiscordOAuth request = exchangeRequest.getAndSet(null);
            if (request != null) request.cancel();
            finishAuthorization(attempt, new DiscordFailure(DiscordFailure.Kind.CANCELLED, "Authorization expired; connect again"));
        }, remaining, TimeUnit.MILLISECONDS);
    }

    private static boolean removePending(DiscordAuthorization.Attempt attempt) throws Exception {
        synchronized (credentialsLock) {
            JSONObject pending = credentials.optJSONObject("pending");
            if (pending == null || (attempt != null && !attempt.state.equals(pending.optString("state")))) return false;
            JSONObject next = copyCredentials(); next.remove("pending");
            tokenStore.write(next); credentials = next; updateAuthorized(); return true;
        }
    }

    private static JSONObject copyCredentials() throws Exception { return new JSONObject(credentials.toString()); }
    private static void updateAuthorized() {
        authorized = getApplicationId().equals(credentials.optString("app_id")) && !credentials.optString("access_token").isEmpty();
    }
    private static JSONObject tokenData(String appId, JSONObject result, String oldRefresh) throws Exception {
        return new JSONObject().put("app_id", appId).put("access_token", result.getString("access_token"))
                .put("refresh_token", result.optString("refresh_token", oldRefresh))
                .put("expires_at", System.currentTimeMillis() + result.getLong("expires_in") * 1000);
    }

    /** Called by the gateway's token I/O executor. No lock is held while HTTP runs. */
    private static String accessToken(boolean forceRefresh, AtomicReference<DiscordOAuth> requests) throws Exception {
        if (!isSharingEnabled() || Thread.currentThread().isInterrupted()) {
            throw new DiscordFailure(DiscordFailure.Kind.CANCELLED, "Token request cancelled");
        }
        JSONObject snapshot;
        long epoch;
        synchronized (credentialsLock) { snapshot = copyCredentials(); epoch = credentialsEpoch; }
        String appId = snapshot.optString("app_id");
        if (!appId.equals(getApplicationId()) || snapshot.optString("access_token").isEmpty()) {
            throw new DiscordFailure(DiscordFailure.Kind.AUTHORIZATION, "No saved Discord authorization; connect again");
        }
        if (!forceRefresh && snapshot.optLong("expires_at") > System.currentTimeMillis() + 60_000) return snapshot.getString("access_token");
        String refresh = snapshot.optString("refresh_token");
        if (refresh.isEmpty()) throw new DiscordFailure(DiscordFailure.Kind.AUTHORIZATION, "Discord authorization expired; sign in again");
        DiscordOAuth request = new DiscordOAuth();
        requests.set(request);
        try {
            JSONObject result = request.refresh(appId, refresh);
            synchronized (credentialsLock) {
                if (epoch != credentialsEpoch || request.isCancelled() || !isSharingEnabled() || Thread.currentThread().isInterrupted()) {
                    throw new DiscordFailure(DiscordFailure.Kind.CANCELLED, "Token refresh cancelled");
                }
                JSONObject next = tokenData(appId, result, refresh);
                if (credentials.has("pending")) next.put("pending", credentials.getJSONObject("pending"));
                tokenStore.write(next); credentials = next; ++credentialsEpoch; updateAuthorized();
            }
            return result.getString("access_token");
        } finally { requests.compareAndSet(request, null); }
    }

    private static void reconcileSafely() {
        try { reconcile(); }
        catch (Exception error) { report("Presence update", error); }
    }

    private static void reconcile() throws Exception {
        if (!isSharingEnabled() || !authorized) {
            stopTicker(); cancel(idleClose); idleClose = null; stopGateway(); return;
        }
        MediaMetadata track = metadata;
        PlaybackState state = playback;
        String title = track == null ? null : track.getString(MediaMetadata.METADATA_KEY_TITLE);
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING
                && title != null && !title.trim().isEmpty();
        long now = SystemClock.elapsedRealtime();
        long duration = track == null ? 0 : track.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? -1 : state.getPosition();
        if (playing && position >= 0 && state.getLastPositionUpdateTime() > 0) {
            position += (long) (Math.max(0, now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        if (duration > 0 && position >= duration) playing = false;
        if (!playing) {
            stopTicker();
            if (gateway != null) {
                gateway.update(DiscordPresence.update(null), "clear");
                if (idleClose == null) idleClose = worker.schedule(() -> {
                    stopGateway(); idleClose = null; status = "Signed in; waiting for playback";
                }, 60, TimeUnit.SECONDS);
            }
            return;
        }
        cancel(idleClose); idleClose = null;
        if (ticker == null) ticker = worker.scheduleWithFixedDelay(DiscordRpcPatch::reconcileSafely, 1, 1, TimeUnit.SECONDS);
        if (gateway == null) {
            long epoch = ++connectionEpoch;
            gateway = new DiscordGateway(getApplicationId(), new DiscordGateway.TokenProvider() {
                private final AtomicReference<DiscordOAuth> requests = new AtomicReference<>();
                @Override public String get(boolean forceRefresh) throws Exception { return accessToken(forceRefresh, requests); }
                @Override public void cancel() {
                    DiscordOAuth request = requests.getAndSet(null);
                    if (request != null) request.cancel();
                }
            }, new DiscordGateway.StatusListener() {
                @Override public void changed(String value) { if (epoch == connectionEpoch) status = value; }
                @Override public void failed(DiscordFailure error) {
                    worker.execute(() -> {
                        if (epoch != connectionEpoch) return;
                        preferences.edit().putBoolean("enabled", false).apply();
                        stopGateway(); stopTicker(); cancel(idleClose); idleClose = null;
                        if (error.kind == DiscordFailure.Kind.AUTHORIZATION) {
                            try {
                                synchronized (credentialsLock) {
                                    JSONObject next = copyCredentials();
                                    for (String key : new String[]{"app_id", "access_token", "refresh_token", "expires_at"}) next.remove(key);
                                    tokenStore.write(next); credentials = next; ++credentialsEpoch; updateAuthorized();
                                }
                            } catch (Exception storageError) { report("Clear rejected authorization", storageError); }
                        }
                        report("Discord connection", error);
                    });
                }
            });
            gateway.start();
        }
        String artist = track.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String id = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        long wallNow = System.currentTimeMillis();
        JSONObject activity = DiscordPresence.track(getApplicationId(), title, artist, id, duration, position, state.getPlaybackSpeed(), wallNow);
        String trackKey = title + '\n' + artist + '\n' + id + '\n' + duration + '\n' + state.getPlaybackSpeed();
        boolean timed = activity.has("timestamps");
        long start = timed ? wallNow - position : -1;
        if (!trackKey.equals(previousTrackKey) || Math.abs(start - previousStart) > 2000) { previousTrackKey = trackKey; previousStart = start; }
        gateway.update(DiscordPresence.update(activity), trackKey + '\n' + previousStart);
    }

    private static void stopGateway() {
        if (gateway != null) { ++connectionEpoch; gateway.close(); gateway = null; }
    }
    private static void stopTicker() { cancel(ticker); ticker = null; }
    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    private static void report(String operation, Throwable error) {
        DiscordDiagnostics.failure(operation, error); status = operation + ": " + DiscordFailure.describe(error);
    }
}
