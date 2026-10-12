package app.dyskal.ytmrpc;

import android.app.Activity;
import android.os.Bundle;

/** Browser callback only. No UI or credentials in intents returned to other applications. */
public final class DiscordOAuthActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DiscordRpcPatch.initialize(getApplicationContext());
        DiscordRpcPatch.callback(getApplicationContext(), getIntent().getData());
        finish();
    }
}
