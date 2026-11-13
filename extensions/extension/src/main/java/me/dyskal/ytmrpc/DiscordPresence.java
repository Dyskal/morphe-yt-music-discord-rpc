package me.dyskal.ytmrpc;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local RPC activities; the observed Android service accepts millisecond timestamps. */
public final class DiscordPresence {
    private DiscordPresence() { }

    public static JSONObject track(DiscordTrack track, boolean artistHeadline,
                                   long duration, long position, float speed, long now) throws Exception {
        JSONObject activity = new JSONObject().put("name", truncate(artistHeadline && !track.artist.isEmpty() ? track.artist : "YouTube Music"))
                .put("status_display_type", 0).put("type", 2).put("details", truncate(track.title));
        if (track.songUrl != null) {
            activity.put("details_url", track.songUrl);
            activity.put("buttons", new JSONArray().put(new JSONObject().put("label", "Listen on YouTube Music").put("url", track.songUrl)));
        }
        if (!track.artist.isEmpty()) activity.put("state", truncate(track.artist));
        if (track.artistUrl != null) activity.put("state_url", track.artistUrl);
        if (track.artworkUrl != null) {
            activity.put("assets", new JSONObject().put("large_image", track.artworkUrl)
                    .put("large_text", truncate(track.album.isEmpty() ? track.title : track.album)));
            // The third listening-activity line is large_text; Discord has no independent URL for it.
            if (track.albumUrl != null) activity.getJSONObject("assets").put("large_url", track.albumUrl);
        }
        // Discord cannot represent a non-1x progress bar accurately. Omit it in that case.
        if (duration > 0 && position >= 0 && position < duration && Math.abs(speed - 1f) < 0.001f) {
            activity.put("timestamps", new JSONObject().put("start", now - position)
                    .put("end", now + duration - position));
        }
        return activity;
    }

    static String frame(JSONObject activity, int pid, String nonce) throws Exception {
        return new JSONObject().put("cmd", "SET_ACTIVITY").put("nonce", nonce)
                .put("args", new JSONObject().put("pid", pid).put("activity", activity == null ? JSONObject.NULL : activity)).toString();
    }

    private static String truncate(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= 128 ? text : text.substring(0, text.offsetByCodePoints(0, 128));
    }
}
