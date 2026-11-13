package me.dyskal.ytmrpc;

/** Only controlled messages cross the IPC/UI boundary. */
public final class DiscordFailure extends Exception {
    public enum Kind { CONFIGURATION, TRANSIENT, PROTOCOL }
    public final Kind kind;
    public DiscordFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static String describe(Throwable error) {
        return error instanceof DiscordFailure ? error.getMessage() : "Operation failed (" + error.getClass().getSimpleName() + ")";
    }
}
