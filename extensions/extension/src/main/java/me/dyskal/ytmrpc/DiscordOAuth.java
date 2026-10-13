package me.dyskal.ytmrpc;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Public-client OAuth with a whole-call timeout and explicit cancellation. */
public final class DiscordOAuth {
    public static final String REDIRECT_URI = "ytmdiscord://oauth2/callback";
    public static final String SCOPES = "openid sdk.social_layer_presence";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final String tokenEndpoint;
    private final OkHttpClient http;
    private final AtomicReference<Call> active = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public DiscordOAuth() { this("https://discord.com/api/v10/oauth2/token"); }
    DiscordOAuth(String tokenEndpoint) { this(tokenEndpoint, HTTP); }
    DiscordOAuth(String tokenEndpoint, OkHttpClient http) { this.tokenEndpoint = tokenEndpoint; this.http = http; }

    public void cancel() {
        cancelled.set(true);
        Call call = active.get();
        if (call != null) call.cancel();
    }

    public boolean isCancelled() { return cancelled.get(); }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static boolean isValidApplicationId(String appId) { return appId != null && appId.matches("[0-9]{17,20}"); }

    public static String authorizeUrl(String appId, String state, String verifier) throws Exception {
        if (!isValidApplicationId(appId)) throw new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "Enter a valid 17–20 digit application ID");
        return "https://discord.com/oauth2/authorize?client_id=" + appId
                + "&response_type=code&redirect_uri=" + encode(REDIRECT_URI)
                + "&scope=" + encode(SCOPES) + "&state=" + encode(state)
                + "&code_challenge_method=S256&code_challenge=" + challenge(verifier);
    }

    public JSONObject exchange(String appId, String code, String verifier) throws Exception {
        return request(new FormBody.Builder().add("client_id", appId).add("grant_type", "authorization_code")
                .add("code", code).add("redirect_uri", REDIRECT_URI).add("code_verifier", verifier).build());
    }

    public JSONObject refresh(String appId, String refreshToken) throws Exception {
        return request(new FormBody.Builder().add("client_id", appId).add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken).build());
    }

    private JSONObject request(FormBody form) throws Exception {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw cancelledFailure();
        Call call = http.newCall(new Request.Builder().url(tokenEndpoint).post(form).build());
        active.set(call);
        if (cancelled.get()) call.cancel();
        try (Response response = call.execute()) {
            if (cancelled.get()) throw cancelledFailure();
            if (response.body() == null) throw new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Discord returned an empty OAuth response");
            String body;
            try (var input = response.body().byteStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65_536) throw new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Discord OAuth response is too large");
                    output.write(buffer, 0, count);
                }
                body = output.toString("UTF-8");
            }
            if (!response.isSuccessful()) throw httpFailure(response.code(), body, response.header("Retry-After"));
            JSONObject result;
            try { result = new JSONObject(body); }
            catch (Exception error) { throw new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Discord returned invalid OAuth JSON"); }
            if (!"Bearer".equalsIgnoreCase(result.optString("token_type"))
                    || result.optString("access_token").isEmpty() || result.optLong("expires_in") <= 0) {
                throw new DiscordFailure(DiscordFailure.Kind.PROTOCOL, "Discord returned an invalid OAuth token response");
            }
            // OAuth may omit an unchanged scope on refresh; an explicitly returned scope must include presence.
            if (result.has("scope") && !(" " + result.optString("scope") + " ").contains(" sdk.social_layer_presence ")) {
                throw new DiscordFailure(DiscordFailure.Kind.CONFIGURATION, "Discord presence scope not granted; check Social SDK configuration");
            }
            return result;
        } catch (DiscordFailure error) {
            throw error;
        } catch (IOException error) {
            throw cancelled.get() || Thread.currentThread().isInterrupted() ? cancelledFailure() : DiscordFailure.network(error);
        } finally { active.compareAndSet(call, null); }
    }

    private static DiscordFailure httpFailure(int status, String body, String retryAfter) {
        String code = "";
        try {
            String value = new JSONObject(body).optString("error");
            if (java.util.Arrays.asList("invalid_grant", "invalid_token", "invalid_client", "unauthorized_client",
                    "invalid_scope", "access_denied", "unsupported_grant_type").contains(value)) code = value;
        } catch (Exception ignored) { }
        DiscordFailure.Kind kind;
        if ("invalid_client".equals(code) || "unauthorized_client".equals(code) || "invalid_scope".equals(code)
                || "access_denied".equals(code) || "unsupported_grant_type".equals(code)) kind = DiscordFailure.Kind.CONFIGURATION;
        else if ("invalid_grant".equals(code) || "invalid_token".equals(code) || status == 401) kind = DiscordFailure.Kind.AUTHORIZATION;
        else if (status == 429 || status >= 500) kind = DiscordFailure.Kind.TRANSIENT;
        else kind = DiscordFailure.Kind.CONFIGURATION;
        long delay = 0;
        try { delay = Math.max(0, (long) (Double.parseDouble(retryAfter) * 1000)); }
        catch (Exception ignored) { }
        return new DiscordFailure(kind, "Discord OAuth HTTP " + status + (code.isEmpty() ? "" : " (" + code + ")"), status, delay);
    }

    private static DiscordFailure cancelledFailure() { return new DiscordFailure(DiscordFailure.Kind.CANCELLED, "Authorization cancelled"); }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
}
