package me.dyskal.ytmrpc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscordPresenceTest {
    @Test public void artistHeadlineAndSongArtistCoverLinksUseLocalRpcFields() throws Exception {
        DiscordTrack track = new DiscordTrack("Song", "Artist & Guest", "Album", "dQw4w9WgXcQ", null,
                "UCs-QBT4qkj_YiQw1ZntDO3g", "MPREb_exampleAlbum");
        JSONObject activity = DiscordPresence.track(track, true, 180_000, 30_000, 1, 1_000_000);
        assertEquals("Artist & Guest", activity.getString("name"));
        assertEquals(2, activity.getInt("type"));
        assertEquals(0, activity.getInt("status_display_type"));
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", activity.getString("details_url"));
        assertEquals("https://music.youtube.com/channel/UCs-QBT4qkj_YiQw1ZntDO3g", activity.getString("state_url"));
        JSONObject assets = activity.getJSONObject("assets");
        assertEquals(track.artworkUrl, assets.getString("large_image"));
        assertEquals("Album", assets.getString("large_text"));
        assertEquals(track.albumUrl, assets.getString("large_url"));
        assertEquals(1, activity.getJSONArray("buttons").length());
        assertEquals(track.songUrl, activity.getJSONArray("buttons").getJSONObject(0).getString("url"));
        assertFalse(activity.has("application_id")); assertFalse(activity.has("metadata"));
        assertEquals(970_000, activity.getJSONObject("timestamps").getLong("start"));
        assertEquals(1_150_000, activity.getJSONObject("timestamps").getLong("end"));
    }

    @Test public void albumCoverLinkDoesNotRequireAVideoId() throws Exception {
        DiscordTrack track = new DiscordTrack("Song", "Artist", "Album", null, "https://example.com/cover", null, "MPREb_exampleAlbum");
        JSONObject activity = DiscordPresence.track(track, false, 0, -1, 1, 0);
        assertEquals("Album", activity.getJSONObject("assets").getString("large_text"));
        assertEquals(track.albumUrl, activity.getJSONObject("assets").getString("large_url"));
        assertFalse(activity.has("details_url"));
        assertFalse(activity.has("buttons")); assertFalse(activity.has("metadata"));
        JSONObject missingAlbum = DiscordPresence.track(
                new DiscordTrack("Song", "Artist", "Album", "dQw4w9WgXcQ", null), false, 0, -1, 1, 0);
        assertFalse(missingAlbum.getJSONObject("assets").has("large_url"));
    }

    @Test public void applicationHeadlineAndMissingArtistHaveSafeFallbacks() throws Exception {
        DiscordTrack track = new DiscordTrack("Song", null, null, null, "content://local/bitmap");
        JSONObject activity = DiscordPresence.track(track, true, 1000, 0, 2, 0);
        assertEquals("YouTube Music", activity.getString("name"));
        assertFalse(activity.has("state_url")); assertFalse(activity.has("assets")); assertFalse(activity.has("timestamps"));
        assertFalse(activity.has("details_url"));
        assertNull(track.artworkUrl);
        assertEquals("YouTube Music", DiscordPresence.track(
                new DiscordTrack("Song", "Artist", "Album", null, null), false, 0, -1, 1, 0).getString("name"));
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
        JSONObject activity = DiscordPresence.track(new DiscordTrack("🎵".repeat(150), "Artist", "Album", null, null),
                false, 0, -1, 1, 0);
        String title = activity.getString("details"); assertEquals(128, title.codePointCount(0, title.length()));
        JSONObject cleared = new JSONObject(DiscordPresence.frame(null, 7, "clear"));
        assertTrue(cleared.getJSONObject("args").has("activity"));
        assertTrue(cleared.getJSONObject("args").isNull("activity"));
    }

    @Test public void frameIncludesTheProcessIdNonceAndActivity() throws Exception {
        JSONObject activity = new JSONObject().put("details", "Song");
        JSONObject frame = new JSONObject(DiscordPresence.frame(activity, 123, "nonce"));
        assertEquals("SET_ACTIVITY", frame.getString("cmd"));
        assertEquals("nonce", frame.getString("nonce"));
        assertEquals(123, frame.getJSONObject("args").getInt("pid"));
        assertEquals("Song", frame.getJSONObject("args").getJSONObject("activity").getString("details"));
    }

    @Test public void enrichmentReplacesOnlyStaleDerivedCovers() throws Exception {
        DiscordTrack old = new DiscordTrack("Song", "Artist", "Album", "dQw4w9WgXcQ", null);
        String next = "9itENA7hVe0", expected = "https://i.ytimg.com/vi/" + next + "/hqdefault.jpg";
        assertEquals(expected, old.enrich(null, next).artworkUrl);
        assertEquals(expected, old.enrich(DiscordNativeMetadataTest.nativeTrack("dQw4w9WgXcQ", "Song"), next).artworkUrl);
        assertEquals(expected, old.enrich(DiscordNativeMetadataTest.nativeTrack(next, "Song"), next).artworkUrl);
        DiscordTrack publicCover = new DiscordTrack("Song", "Artist", "Album", "dQw4w9WgXcQ", "https://lh3.googleusercontent.com/album");
        assertEquals(publicCover.artworkUrl, publicCover.enrich(null, next).artworkUrl);
        assertEquals(old.artworkUrl, old.enrich(null, old.videoId).artworkUrl);
    }
}
