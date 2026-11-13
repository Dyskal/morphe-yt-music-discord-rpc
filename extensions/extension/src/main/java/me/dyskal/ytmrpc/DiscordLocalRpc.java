package me.dyskal.ytmrpc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Process;

import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental SDK-compatible local RPC. Binder I/O and mutable state stay off the main thread. */
public final class DiscordLocalRpc implements AutoCloseable {
    public interface StatusListener {
        void changed(String value);
        void failed(DiscordFailure error);
    }

    private final Context context;
    private final long applicationId;
    private final StatusListener status;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Discord local RPC"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private Session session;
    private int attempts;
    private boolean started, ready;
    private JSONObject desired;
    private String desiredKey, sentKey, pendingKey, pendingNonce;
    private long lastSentNanos;
    private ScheduledFuture<?> retry, watchdog, flush;

    public DiscordLocalRpc(Context context, String applicationId, StatusListener status) {
        this.context = context.getApplicationContext();
        if (!isValidApplicationId(applicationId)) throw new IllegalArgumentException("Invalid Discord application ID");
        // Discord snowflakes are unsigned 64-bit integers; Parcel carries their raw bits as a long.
        this.applicationId = Long.parseUnsignedLong(applicationId);
        this.status = status;
    }

    public static boolean isValidApplicationId(String value) {
        if (value == null || !value.matches("[0-9]{17,20}")) return false;
        try { Long.parseUnsignedLong(value); return true; }
        catch (NumberFormatException ignored) { return false; }
    }

    public void start() {
        dispatch(() -> { if (!started) { started = true; connect(); } });
    }

    public void update(JSONObject activity, String key) {
        dispatch(() -> { desired = activity; desiredKey = key; sendLatest(); });
    }

    private void dispatch(Runnable action) {
        if (closed.get()) return;
        try { worker.execute(() -> { if (!closed.get()) action.run(); }); }
        catch (RejectedExecutionException ignored) { }
    }

    private void connect() {
        if (closed.get()) return;
        status.changed("Connecting to the local Discord app");
        try {
            Intent intent = new Intent(DiscordLocalBinder.SERVICE).setPackage("com.discord");
            ResolveInfo resolved = context.getPackageManager().resolveService(intent, 0);
            if (resolved == null || resolved.serviceInfo == null || !resolved.serviceInfo.exported
                    || !"com.discord".equals(resolved.serviceInfo.packageName)) {
                fatal(new DiscordFailure(DiscordFailure.Kind.CONFIGURATION,
                        "Install or update the official Discord app; its local RPC service was not found"));
                return;
            }
            intent.setComponent(new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name));
            Session next = new Session(resolved.serviceInfo.applicationInfo.uid);
            session = next;
            next.bound = context.bindService(intent, next, Context.BIND_AUTO_CREATE);
            if (!next.bound) { failed(next, "Discord local service binding failed"); return; }
            watchdog = worker.schedule(() -> failed(next, "Discord local handshake timed out"), 20, TimeUnit.SECONDS);
        } catch (Exception error) {
            fatal(new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "Discord local RPC unavailable (" + error.getClass().getSimpleName() + ")"));
        }
    }

    private final class Session implements ServiceConnection {
        final DiscordLocalBinder.Callback callback;
        final IBinder.DeathRecipient death = () -> dispatch(() -> failed(this, "Discord local connection died"));
        IBinder connection;
        boolean bound;

        Session(int uid) {
            callback = new DiscordLocalBinder.Callback(uid) {
                @Override void frame(String value) { dispatch(() -> received(Session.this, value)); }
                @Override void closed(int code) { dispatch(() -> failed(Session.this, "Discord local connection closed (" + code + ")")); }
            };
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            dispatch(() -> {
                if (session != this) return;
                try {
                    connection = DiscordLocalBinder.connect(service, applicationId, callback);
                    if (connection == null) { failed(this, "Discord local handshake rejected; open Discord and sign in"); return; }
                    connection.linkToDeath(death, 0);
                } catch (Exception error) { failed(this, "Discord local handshake failed"); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { dispatch(() -> failed(this, "Discord app disconnected")); }
        @Override public void onBindingDied(ComponentName name) { dispatch(() -> failed(this, "Discord local service binding died")); }
        @Override public void onNullBinding(ComponentName name) { dispatch(() -> failed(this, "Discord local service returned no connection")); }
    }

    private void received(Session source, String text) {
        if (session != source) return;
        try {
            if (text == null || text.length() > 1_048_576) throw new IllegalArgumentException();
            JSONObject frame = new JSONObject(text);
            if ("ERROR".equals(frame.optString("evt"))) {
                int code = frame.optJSONObject("data") == null ? 0 : frame.getJSONObject("data").optInt("code");
                fatal(new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "Discord local RPC rejected the request (" + code + ")"));
            } else if ("READY".equals(frame.optString("evt"))) {
                if (ready) return;
                ready = true; cancel(watchdog);
                status.changed("Local Discord connected; publishing presence"); sendLatest();
            } else if ("SET_ACTIVITY".equals(frame.optString("cmd")) && pendingNonce != null
                    && pendingNonce.equals(frame.optString("nonce"))) {
                sentKey = pendingKey; pendingKey = null; pendingNonce = null;
                attempts = 0; cancel(watchdog);
                status.changed("clear".equals(sentKey) ? "Local Discord connected; presence cleared"
                        : "Sharing through the local Discord app"); sendLatest();
            }
        } catch (Exception error) {
            fatal(new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Invalid Discord local RPC response"));
        }
    }

    private void sendLatest() {
        if (closed.get() || !ready || session == null || session.connection == null || desiredKey == null
                || pendingNonce != null || Objects.equals(desiredKey, sentKey)) return;
        long remaining = 4_100 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastSentNanos);
        if (lastSentNanos != 0 && remaining > 0) {
            if (flush == null || flush.isDone()) flush = worker.schedule(this::sendLatest, remaining, TimeUnit.MILLISECONDS);
            return;
        }
        try {
            pendingNonce = UUID.randomUUID().toString(); pendingKey = desiredKey;
            DiscordLocalBinder.sendFrame(session.connection, DiscordPresence.frame(desired, Process.myPid(), pendingNonce));
            lastSentNanos = System.nanoTime();
            Session current = session;
            watchdog = worker.schedule(() -> failed(current, "Discord local activity acknowledgement timed out"), 15, TimeUnit.SECONDS);
        } catch (Exception error) { failed(session, "Discord local activity send failed"); }
    }

    private void failed(Session source, String reason) {
        if (session != source || closed.get()) return;
        stopSession(false);
        if (attempts >= 7) {
            fatal(new DiscordFailure(DiscordFailure.Kind.TRANSIENT, reason + "; open Discord, then tap Connect to retry")); return;
        }
        long delay = Math.min(60, 1L << attempts++);
        status.changed(reason + "; retrying in " + delay + "s");
        retry = worker.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void fatal(DiscordFailure error) {
        stopSession(false); cancel(retry); status.failed(error);
    }

    private void stopSession(boolean clear) {
        Session previous = session; session = null; ready = false;
        pendingNonce = null; pendingKey = null; sentKey = null;
        cancel(watchdog); cancel(flush);
        if (previous == null) return;
        if (previous.connection != null) {
            if (clear) {
                try { DiscordLocalBinder.sendFrame(previous.connection,
                        DiscordPresence.frame(null, Process.myPid(), UUID.randomUUID().toString()));
                } catch (Exception ignored) { /* Still disconnect even if clearing failed. */ }
            }
            try { DiscordLocalBinder.disconnect(previous.connection); }
            catch (Exception ignored) { /* The remote process may already be gone. */ }
            try { previous.connection.unlinkToDeath(previous.death, 0); }
            catch (java.util.NoSuchElementException ignored) { /* Handshake may have failed before linking. */ }
        }
        if (previous.bound) {
            try { context.unbindService(previous); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { cancel(retry); stopSession(true); }
            finally { worker.shutdown(); }
        });
    }
}
