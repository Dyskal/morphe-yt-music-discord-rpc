package me.dyskal.ytmrpc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscordPresenceTest {
    private static final String APP = "1559278040175878146";

    @Test public void artistHeadlineAndTitleArtistAlbumLinksUseGatewayFields() throws Exception {
        DiscordTrack track = new DiscordTrack("Song", "Artist & Guest", "Album", "dQw4w9WgXcQ", null);
        JSONObject activity = DiscordPresence.track(APP, track, true, "mp:external/cover", 180_000, 30_000, 1, 1_000_000);
        assertEquals("Artist & Guest", activity.getString("name"));
        assertEquals(2, activity.getInt("type"));
        assertEquals(0, activity.getInt("status_display_type"));
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", activity.getString("details_url"));
        assertTrue(activity.getString("state_url").contains("Artist+%26+Guest"));
        JSONObject assets = activity.getJSONObject("assets");
        assertEquals("mp:external/cover", assets.getString("large_image"));
        assertEquals("Album", assets.getString("large_text"));
        assertTrue(assets.getString("large_url").contains("Album+Artist+%26+Guest"));
        assertEquals(970_000, activity.getJSONObject("timestamps").getLong("start"));
        assertEquals(1_150_000, activity.getJSONObject("timestamps").getLong("end"));
    }

    @Test public void applicationHeadlineAndMissingArtistHaveSafeFallbacks() throws Exception {
        DiscordTrack track = new DiscordTrack("Song", null, null, null, "content://local/bitmap");
        JSONObject activity = DiscordPresence.track(APP, track, true, null, 1000, 0, 2, 0);
        assertEquals("YouTube Music", activity.getString("name"));
        assertFalse(activity.has("state_url")); assertFalse(activity.has("assets")); assertFalse(activity.has("timestamps"));
        assertTrue(activity.getString("details_url").contains("search?q=Song"));
        assertNull(track.artworkUrl);
        assertEquals("YouTube Music", DiscordPresence.track(APP,
                new DiscordTrack("Song", "Artist", "Album", null, null), false, null, 0, -1, 1, 0).getString("name"));
    }

    @Test public void youtubeIdsAndPublicArtworkUrlsAreResolvedWithoutSendingLocalFiles() {
        DiscordTrack track = new DiscordTrack("Song", "Artist", "Album",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=other", "https://lh3.googleusercontent.com/cover");
        assertEquals("dQw4w9WgXcQ", track.videoId);
        assertEquals("https://lh3.googleusercontent.com/cover", track.artworkUrl);
        assertEquals("dQw4w9WgXcQ", DiscordTrack.videoId("https://youtu.be/dQw4w9WgXcQ"));
        assertNull(DiscordTrack.videoId("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"));
        assertNull(DiscordTrack.publicUrl("file:///local/cover.jpg"));
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                new DiscordTrack("Song", "Artist", "Album", "dQw4w9WgXcQ", null).artworkUrl);
    }

    @Test public void textTruncationPreservesUnicodeAndClearHasNoActivity() throws Exception {
        JSONObject activity = DiscordPresence.track(APP, new DiscordTrack("🎵".repeat(150), "Artist", "Album", null, null),
                false, null, 0, -1, 1, 0);
        String title = activity.getString("details"); assertEquals(128, title.codePointCount(0, title.length()));
        assertEquals(0, new JSONObject(DiscordPresence.update(null)).getJSONObject("d").getJSONArray("activities").length());
    }
}
