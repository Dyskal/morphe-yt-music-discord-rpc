package me.dyskal.ytmrpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscordNativeMetadataTest {
    private static final String VIDEO = "9itENA7hVe0";
    private static final String ARTIST = "UC-clMkTZa7k-FxmNgMjoCgQ";
    private static final String ALBUM = "MPREb_kXl9gRPqufi";
    private static byte[] message(byte[]... fields) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); for (byte[] field : fields) output.write(field); return output.toByteArray();
    }
    private static void varint(ByteArrayOutputStream output, long value) {
        while (value >= 128) { output.write((int) (value & 127) | 128); value >>>= 7; } output.write((int) value);
    }
    private static byte[] field(int number, byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); varint(output, (long) number << 3 | 2); varint(output, value.length); output.write(value); return output.toByteArray();
    }
    private static byte[] text(int number, String value) throws Exception { return field(number, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] scalar(int number, int value) { ByteArrayOutputStream output = new ByteArrayOutputStream(); varint(output, (long) number << 3); varint(output, value); return output.toByteArray(); }
    private static byte[] run(String text, String browseId) throws Exception {
        byte[] endpoint = browseId == null ? new byte[0] : field(5, field(48687626, text(2, browseId)));
        return field(1, message(text(1, text), endpoint));
    }

    @Test public void nativeRendererSuppliesRealIdsAndLargestCover() throws Exception {
        byte[] renderer = message(text(10, VIDEO), field(1, run("1-800-hot-n-fun", null)),
                field(12, message(run("LE SSERAFIM", ARTIST), run(" • ", null), run("CRAZY", ALBUM))),
                field(3, message(field(1, message(text(1, "https://lh3.googleusercontent.com/small"), scalar(2, 64))),
                        field(1, message(text(1, "https://lh3.googleusercontent.com/large"), scalar(2, 640))))));
        DiscordNativeMetadata nativeData = DiscordNativeMetadata.parse(renderer);
        assertNotNull(nativeData); assertEquals(VIDEO, nativeData.videoId);
        assertEquals("LE SSERAFIM", nativeData.artist); assertEquals(ARTIST, nativeData.artistId);
        assertEquals("CRAZY", nativeData.album); assertEquals(ALBUM, nativeData.albumId);
        assertTrue(nativeData.artworkUrl.endsWith("/large"));
        DiscordTrack enriched = new DiscordTrack("projected title", "projected artist", "", null, null).enrich(nativeData, VIDEO);
        assertEquals("1-800-hot-n-fun", enriched.title);
        assertEquals("https://music.youtube.com/channel/" + ARTIST, enriched.artistUrl);
        assertEquals("https://music.youtube.com/browse/" + ALBUM, enriched.albumUrl);
        assertEquals("https://music.youtube.com/watch?v=" + VIDEO, enriched.songUrl);
    }

    @Test public void unrelatedMenuIdsAndStaleQueueDetailsAreNotUsed() throws Exception {
        byte[] renderer = message(text(10, VIDEO), field(1, run("Song", null)), field(99, text(2, ARTIST)));
        DiscordNativeMetadata parsed = DiscordNativeMetadata.parse(renderer);
        assertNull(parsed.artistId);
        DiscordTrack track = new DiscordTrack("Other song", "Other artist", "Other album", "dQw4w9WgXcQ", null).enrich(parsed, "dQw4w9WgXcQ");
        assertEquals("Other song", track.title); assertNull(track.artistUrl); assertNull(track.albumUrl);
        assertFalse(track.songUrl.contains("search"));
    }

    @Test public void malformedOrOversizedMetadataIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DiscordNativeMetadata.parse(new byte[]{10, 100, 1}));
        assertThrows(IllegalArgumentException.class, () -> DiscordNativeMetadata.parse(new byte[1_048_577]));
        assertNull(DiscordNativeMetadata.parse(text(10, "invalid-id")));
    }
}
