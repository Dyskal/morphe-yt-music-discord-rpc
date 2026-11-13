package me.dyskal.ytmrpc;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DiscordLocalRpcTest {
    private static final String APP = "1559278040175878146";

    @Test public void applicationIdsMustFitTheUnsignedBinderValue() {
        assertTrue(DiscordLocalRpc.isValidApplicationId(APP));
        assertFalse(DiscordLocalRpc.isValidApplicationId(null));
        assertFalse(DiscordLocalRpc.isValidApplicationId("not-an-id"));
        assertFalse(DiscordLocalRpc.isValidApplicationId("99999999999999999999"));
    }

    @Test public void waitsForReadyCoalescesUntilAcknowledgedAndClearsOnClose() throws Exception {
        Host host = new Host();
        try (DiscordLocalRpc client = host.client()) {
            client.update(new JSONObject().put("details", "first"), "first"); client.start();
            IBinder callback = host.callbacks.poll(3, TimeUnit.SECONDS); assertNotNull(callback);
            assertNull(host.frames.poll(150, TimeUnit.MILLISECONDS));
            frame(callback, new JSONObject().put("evt", "READY"));
            JSONObject first = take(host.frames);
            assertEquals("first", first.getJSONObject("args").getJSONObject("activity").getString("details"));
            client.update(new JSONObject().put("details", "intermediate"), "intermediate");
            client.update(new JSONObject().put("details", "latest"), "latest");
            frame(callback, new JSONObject().put("cmd", "SET_ACTIVITY").put("nonce", "wrong-nonce"));
            assertNull(host.frames.poll(150, TimeUnit.MILLISECONDS));
            acknowledge(callback, first);
            JSONObject latest = take(host.frames);
            assertEquals("latest", latest.getJSONObject("args").getJSONObject("activity").getString("details"));
            acknowledge(callback, latest);
        }
        JSONObject cleared = take(host.frames);
        assertTrue(cleared.getJSONObject("args").isNull("activity"));
        assertTrue(host.unbound.await(3, TimeUnit.SECONDS));
        assertTrue(host.disconnected.await(3, TimeUnit.SECONDS));
        assertNull(host.failures.poll());
    }

    @Test public void reconnectIgnoresOldCallbacksAndReplaysTheLatestActivity() throws Exception {
        Host host = new Host();
        try (DiscordLocalRpc client = host.client()) {
            client.update(new JSONObject().put("details", "track"), "track"); client.start();
            IBinder old = host.callbacks.poll(3, TimeUnit.SECONDS); assertNotNull(old);
            ServiceConnection connection = host.bindings.poll(3, TimeUnit.SECONDS); assertNotNull(connection);
            connection.onServiceDisconnected(new ComponentName("com.discord", "Rpc"));
            IBinder next = host.callbacks.poll(3, TimeUnit.SECONDS); assertNotNull(next);
            frame(old, new JSONObject().put("evt", "ERROR").put("data", new JSONObject().put("code", 4000)));
            frame(next, new JSONObject().put("evt", "READY"));
            JSONObject replay = take(host.frames);
            assertEquals("track", replay.getJSONObject("args").getJSONObject("activity").getString("details"));
            acknowledge(next, replay);
            assertNull(host.failures.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void remoteErrorsExposeOnlyTheCodeNotUntrustedText() throws Exception {
        Host host = new Host();
        try (DiscordLocalRpc client = host.client()) {
            client.start();
            IBinder callback = host.callbacks.poll(3, TimeUnit.SECONDS); assertNotNull(callback);
            frame(callback, new JSONObject().put("evt", "ERROR")
                    .put("data", new JSONObject().put("code", 4000).put("message", "PRIVATE DATA")));
            DiscordFailure failure = host.failures.poll(3, TimeUnit.SECONDS); assertNotNull(failure);
            assertEquals(DiscordFailure.Kind.CONFIGURATION, failure.kind);
            assertTrue(failure.getMessage().contains("4000")); assertFalse(failure.getMessage().contains("PRIVATE"));
            assertTrue(host.unbound.await(3, TimeUnit.SECONDS));
        }
    }

    @Test public void failedClearStillDisconnectsAndUnbinds() throws Exception {
        Host host = new Host(); host.rejectClear = true;
        try (DiscordLocalRpc client = host.client()) {
            client.start(); assertNotNull(host.callbacks.poll(3, TimeUnit.SECONDS));
        }
        assertTrue(host.disconnected.await(3, TimeUnit.SECONDS));
        assertTrue(host.unbound.await(3, TimeUnit.SECONDS));
    }

    private static JSONObject take(BlockingQueue<JSONObject> frames) throws Exception {
        JSONObject value = frames.poll(6, TimeUnit.SECONDS); assertNotNull("Missing RPC frame", value); return value;
    }

    private static void acknowledge(IBinder callback, JSONObject request) throws Exception {
        frame(callback, new JSONObject().put("cmd", "SET_ACTIVITY").put("nonce", request.getString("nonce")));
    }

    private static void frame(IBinder callback, JSONObject value) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DiscordLocalBinder.CALLBACK); data.writeString(value.toString());
            assertTrue(callback.transact(1, data, reply, 0)); reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static final class Host extends ContextWrapper {
        final BlockingQueue<IBinder> callbacks = new LinkedBlockingQueue<>();
        final BlockingQueue<ServiceConnection> bindings = new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> frames = new LinkedBlockingQueue<>();
        final BlockingQueue<DiscordFailure> failures = new LinkedBlockingQueue<>();
        final CountDownLatch disconnected = new CountDownLatch(1), unbound = new CountDownLatch(1);
        boolean rejectClear;

        Host() {
            super(RuntimeEnvironment.getApplication());
            ResolveInfo info = new ResolveInfo(); info.serviceInfo = new ServiceInfo();
            info.serviceInfo.packageName = "com.discord"; info.serviceInfo.name = "Rpc"; info.serviceInfo.exported = true;
            info.serviceInfo.applicationInfo = new ApplicationInfo(); info.serviceInfo.applicationInfo.uid = Process.myUid();
            Shadows.shadowOf(getPackageManager()).addResolveInfoForIntent(
                    new Intent(DiscordLocalBinder.SERVICE).setPackage("com.discord"), info);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection callback, int flags) {
            bindings.add(callback);
            callback.onServiceConnected(intent.getComponent(), new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    data.enforceInterface(DiscordLocalBinder.SERVICE);
                    assertEquals(Long.parseLong(APP), data.readLong()); assertEquals("1", data.readString());
                    callbacks.add(data.readStrongBinder());
                    reply.writeNoException(); reply.writeStrongBinder(new Binder() {
                        @Override protected boolean onTransact(int command, Parcel input, Parcel output, int options) throws RemoteException {
                            input.enforceInterface(DiscordLocalBinder.CONNECTION);
                            if (command == 1) {
                                JSONObject frame;
                                try { frame = new JSONObject(input.readString()); }
                                catch (Exception error) { throw new AssertionError(error); }
                                if (rejectClear && frame.optJSONObject("args") != null && frame.optJSONObject("args").isNull("activity")) {
                                    throw new RemoteException("Simulated failed clear");
                                }
                                frames.add(frame);
                            } else if (command == 2) disconnected.countDown();
                            else return false;
                            output.writeNoException(); return true;
                        }
                    });
                    return true;
                }
            });
            return true;
        }
        @Override public void unbindService(ServiceConnection connection) { unbound.countDown(); }

        DiscordLocalRpc client() {
            return new DiscordLocalRpc(this, APP, new DiscordLocalRpc.StatusListener() {
                @Override public void changed(String value) { }
                @Override public void failed(DiscordFailure error) { failures.add(error); }
            });
        }
    }
}
