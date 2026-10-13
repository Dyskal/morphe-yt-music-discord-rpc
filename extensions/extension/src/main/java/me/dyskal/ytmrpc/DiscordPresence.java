package me.dyskal.ytmrpc;

import org.json.JSONArray;
import org.json.JSONObject;

/** Gateway timestamps are milliseconds; RPC's desktop seconds must not be used here. */
public final class DiscordPresence {
    private DiscordPresence() { }

    public static JSONObject track(String appId, String title, String artist,
                                   String videoId, long duration, long position, float speed, long now) throws Exception {
        JSONObject activity = new JSONObject().put("application_id", appId)
                .put("name", "YouTube Music").put("type", 2).put("details", truncate(title));
        if (artist != null && !artist.isBlank()) activity.put("state", truncate(artist));
        if (videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")) {
            String url = "https://music.youtube.com/watch?v=" + videoId;
            activity.put("buttons", new JSONArray().put("Listen on YouTube Music"));
            activity.put("metadata", new JSONObject().put("button_urls", new JSONArray().put(url)));
        }
        // Discord cannot represent a non-1x progress bar accurately. Omit it in that case.
        if (duration > 0 && position >= 0 && position < duration && Math.abs(speed - 1f) < 0.001f) {
            activity.put("timestamps", new JSONObject().put("start", now - position)
                    .put("end", now + duration - position));
        }
        return activity;
    }

    public static String update(JSONObject activity) throws Exception {
        return new JSONObject().put("op", 3).put("d", new JSONObject()
                .put("since", JSONObject.NULL).put("afk", false).put("status", "online")
                .put("activities", activity == null ? new JSONArray() : new JSONArray().put(activity))).toString();
    }

    private static String truncate(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= 128 ? text : text.substring(0, text.offsetByCodePoints(0, 128));
    }
}
