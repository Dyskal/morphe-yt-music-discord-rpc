package me.dyskal.ytmrpc;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Public-client OAuth. Deliberately never accepts or requests a Discord account token. */
public final class DiscordOAuth {
    public static final String REDIRECT_URI = "ytmdiscord://oauth2/callback";
    public static final String SCOPES = "openid sdk.social_layer_presence";
    private final String tokenEndpoint;

    public DiscordOAuth() { this("https://discord.com/api/v10/oauth2/token"); }
    DiscordOAuth(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static String authorizeUrl(String appId, String state, String verifier) throws Exception {
        if (!appId.matches("[0-9]{17,20}")) throw new IOException("Invalid Discord application ID");
        return "https://discord.com/oauth2/authorize?client_id=" + appId
                + "&response_type=code&redirect_uri=" + encode(REDIRECT_URI)
                + "&scope=" + encode(SCOPES) + "&state=" + encode(state)
                + "&code_challenge_method=S256&code_challenge=" + challenge(verifier);
    }

    public JSONObject exchange(String appId, String code, String verifier) throws Exception {
        return request("client_id=" + encode(appId) + "&grant_type=authorization_code&code=" + encode(code)
                + "&redirect_uri=" + encode(REDIRECT_URI) + "&code_verifier=" + encode(verifier));
    }

    public JSONObject refresh(String appId, String refreshToken) throws Exception {
        return request("client_id=" + encode(appId) + "&grant_type=refresh_token&refresh_token=" + encode(refreshToken));
    }

    private JSONObject request(String form) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(tokenEndpoint).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) {
                output.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            // Never surface response bodies: they may contain credentials.
            if (status < 200 || status >= 300) throw new IOException("Discord OAuth HTTP " + status);
            JSONObject result;
            try (var input = connection.getInputStream()) {
                var output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65_536) throw new IOException("OAuth response too large");
                    output.write(buffer, 0, count);
                }
                result = new JSONObject(output.toString("UTF-8"));
            }
            if (!"Bearer".equalsIgnoreCase(result.optString("token_type"))
                    || result.optString("access_token").isEmpty()
                    || result.optLong("expires_in") <= 0) throw new IOException("Invalid OAuth response");
            String scope = " " + result.optString("scope") + " ";
            if (!scope.contains(" sdk.social_layer_presence ")) throw new IOException("Discord presence scope not granted");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
