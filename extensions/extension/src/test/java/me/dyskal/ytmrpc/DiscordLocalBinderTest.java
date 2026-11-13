package me.dyskal.ytmrpc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DiscordLocalBinderTest {
    @Test public void handshakeAndConnectionTransactionsMatchTheSdkWireContract() throws Exception {
        List<String> frames = new ArrayList<>();
        boolean[] disconnected = {false};
        IBinder connection = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface(DiscordLocalBinder.CONNECTION);
                assertEquals(0, flags);
                if (code == 1) frames.add(data.readString());
                else if (code == 2) disconnected[0] = true;
                else return false;
                reply.writeNoException(); return true;
            }
        };
        IBinder callback = new Binder();
        IBinder service = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                assertEquals(1, code); assertEquals(0, flags);
                data.enforceInterface(DiscordLocalBinder.SERVICE);
                assertEquals(1559278040175878146L, data.readLong());
                assertEquals("1", data.readString()); assertSame(callback, data.readStrongBinder());
                reply.writeNoException(); reply.writeStrongBinder(connection); return true;
            }
        };
        assertSame(connection, DiscordLocalBinder.connect(service, 1559278040175878146L, callback));
        DiscordLocalBinder.sendFrame(connection, "{\"cmd\":\"SET_ACTIVITY\"}");
        DiscordLocalBinder.disconnect(connection);
        assertEquals(List.of("{\"cmd\":\"SET_ACTIVITY\"}"), frames); assertTrue(disconnected[0]);
    }

    @Test public void callbackEnforcesDescriptorAndDiscordUid() throws Exception {
        List<String> frames = new ArrayList<>();
        DiscordLocalBinder.Callback callback = new DiscordLocalBinder.Callback(Process.myUid()) {
            @Override void frame(String value) { frames.add(value); }
            @Override void closed(int code) { fail("Unexpected close"); }
        };
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DiscordLocalBinder.CALLBACK); data.writeString("ready"); data.setDataPosition(0);
            assertTrue(callback.onTransact(1, data, reply, 0));
            assertEquals(List.of("ready"), frames);
        } finally { data.recycle(); reply.recycle(); }
        DiscordLocalBinder.Callback wrongUid = new DiscordLocalBinder.Callback(Process.myUid() + 1) {
            @Override void frame(String value) { fail("Wrong UID accepted"); }
            @Override void closed(int code) { fail("Wrong UID accepted"); }
        };
        Parcel rejected = Parcel.obtain();
        try { assertThrows(SecurityException.class, () -> wrongUid.onTransact(1, rejected, null, 0)); }
        finally { rejected.recycle(); }
    }

    @Test public void unsupportedBinderTransactionsFailInsteadOfPretendingToConnect() {
        assertThrows(RemoteException.class, () -> DiscordLocalBinder.connect(new Binder(), 1, new Binder()));
    }
}
