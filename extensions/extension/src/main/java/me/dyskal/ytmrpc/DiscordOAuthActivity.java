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
        DiscordRpcPatch.callback(getApplicationContext(), uri, accepted -> {
            // Wait for a valid state claim before changing the foreground task.
            if (accepted && !isFinishing() && !isDestroyed()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("morphe_settings_intent"))
                            .setClassName(getPackageName(), "com.google.android.gms.common.api.GoogleApiActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (Exception error) { DiscordDiagnostics.failure("Return to Morphe settings", error); }
            }
            finish();
        });
    }
}
