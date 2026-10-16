package me.dyskal.ytmrpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded UTF-8 reads and numeric Retry-After parsing shared by network callers. */
final class Http {
    private Http() { }
    static String readLimited(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > max - output.size()) throw new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Response exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }
    static long retryAfterMillis(String header, long min) {
        try { return Math.max(min, (long) (Double.parseDouble(header) * 1000)); }
        catch (Exception ignored) { return min; }
    }
}
