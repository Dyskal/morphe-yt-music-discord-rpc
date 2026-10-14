package me.dyskal.ytmrpc;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;

/** Browser callback only. No UI or credentials in intents returned to other applications. */
public final class DiscordOAuthActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DiscordRpcPatch.initialize(getApplicationContext());
        Uri uri = getIntent().getData();
        DiscordDiagnostics.info("OAuth callback activity opened");
        DiscordRpcPatch.callback(getApplicationContext(), uri);
        if (uri != null && "ytmdiscord".equals(uri.getScheme()) && "oauth2".equals(uri.getHost()) && "/callback".equals(uri.getPath())) {
            // The callback was previously transparent and immediately finished, leaving the browser foregrounded.
            // Use the official Morphe settings intent and reuse the existing settings task/dialog where possible.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("morphe_settings_intent"))
                        .setClassName(getPackageName(), "com.google.android.gms.common.api.GoogleApiActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception error) { DiscordDiagnostics.failure("Return to Morphe settings", error); }
        }
        finish();
    }
}
