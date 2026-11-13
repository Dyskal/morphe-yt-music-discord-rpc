package me.dyskal.ytmrpc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Wire-compatible Binder adapter observed in Social SDK 1.10.19337; no SDK classes or binaries. */
final class DiscordLocalBinder {
    static final String SERVICE = "com.discord.socialsdk.rpc.IDiscordRpcService";
    static final String CONNECTION = "com.discord.socialsdk.rpc.IDiscordRpcConnection";
    static final String CALLBACK = "com.discord.socialsdk.rpc.IDiscordRpcCallback";

    private DiscordLocalBinder() { }

    static IBinder connect(IBinder service, long applicationId, IBinder callback) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            data.writeLong(applicationId);
            data.writeString("1");
            data.writeStrongBinder(callback);
            transact(service, 1, data, reply);
            return reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
    }

    static void sendFrame(IBinder connection, String frame) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONNECTION);
            data.writeString(frame);
            transact(connection, 1, data, reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    static void disconnect(IBinder connection) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONNECTION);
            transact(connection, 2, data, reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void transact(IBinder binder, int code, Parcel data, Parcel reply) throws RemoteException {
        if (!binder.transact(code, data, reply, 0)) throw new RemoteException("Discord RPC transaction unsupported");
        reply.readException();
    }

    abstract static class Callback extends Binder implements IInterface {
        private final int uid;
        Callback(int uid) { this.uid = uid; attachInterface(this, CALLBACK); }
        @Override public IBinder asBinder() { return this; }
        abstract void frame(String value);
        abstract void closed(int code);

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK); return true; }
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
            if (Binder.getCallingUid() != uid) throw new SecurityException("Unexpected Discord RPC callback UID");
            data.enforceInterface(CALLBACK);
            if (code == 1) frame(data.readString());
            else {
                int status = data.readInt();
                data.readString(); // The server's diagnostic text is not safe to expose in logs or settings.
                closed(status);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }
}
