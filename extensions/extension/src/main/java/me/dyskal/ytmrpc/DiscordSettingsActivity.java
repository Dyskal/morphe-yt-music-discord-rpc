package me.dyskal.ytmrpc;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import java.io.ByteArrayOutputStream;

/** Login and sharing controls opened from YouTube Music's native settings screen. */
public final class DiscordSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CheckBox enabled;
    private TextView status;
    private EditText appId;
    private Button connect, disconnect, cancel;
    private boolean wasAuthorizing;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DiscordRpcPatch.initialize(getApplicationContext());
        setTitle("Discord RPC — Dyskal");
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);

        TextView instructions = new TextView(this);
        instructions.setText("Leave the default ID to use Dyskal's application. Tap Connect to sign in or reconnect. "
                + "A different application ID is saved after you successfully authorize it.");
        content.addView(instructions);

        TextView appIdLabel = new TextView(this);
        appIdLabel.setText("Discord application ID");
        content.addView(appIdLabel);
        appId = new EditText(this);
        appId.setHint("17–20 digit application ID");
        appId.setSingleLine(true);
        appId.setInputType(InputType.TYPE_CLASS_NUMBER);
        appId.setText(DiscordRpcPatch.getApplicationId());
        content.addView(appId);

        enabled = new CheckBox(this);
        enabled.setText("Share currently playing music");
        enabled.setChecked(DiscordRpcPatch.isSharingEnabled());
        enabled.setOnClickListener(view -> { DiscordRpcPatch.setSharingEnabled(enabled.isChecked()); renderState(); });
        content.addView(enabled);

        connect = new Button(this);
        connect.setText("Connect with Discord");
        connect.setOnClickListener(view -> {
            String id = appId.getText().toString().trim();
            if (!DiscordOAuth.isValidApplicationId(id)) {
                appId.setError("Enter a valid 17–20 digit Discord application ID");
                return;
            }
            appId.setError(null);
            DiscordRpcPatch.authorize(getApplicationContext(), id);
            renderState();
        });
        content.addView(connect);

        cancel = new Button(this);
        cancel.setText("Cancel sign-in");
        cancel.setOnClickListener(view -> { DiscordRpcPatch.cancelAuthorization(); renderState(); });
        content.addView(cancel);

        disconnect = new Button(this);
        disconnect.setText("Disconnect and forget account");
        disconnect.setOnClickListener(view -> { DiscordRpcPatch.disconnect(getApplicationContext()); renderState(); });
        content.addView(disconnect);

        status = new TextView(this);
        content.addView(status);
        Button about = new Button(this);
        about.setText("About and licenses");
        about.setOnClickListener(view -> showAbout());
        content.addView(about);
    }

    private void renderState() {
        boolean busy = DiscordRpcPatch.isAuthorizing();
        if (wasAuthorizing && !busy) appId.setText(DiscordRpcPatch.getApplicationId());
        wasAuthorizing = busy;
        status.setText(DiscordRpcPatch.status());
        enabled.setChecked(DiscordRpcPatch.isSharingEnabled());
        enabled.setEnabled(DiscordRpcPatch.isAuthorized() && !busy);
        appId.setEnabled(!busy);
        connect.setEnabled(!busy);
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        disconnect.setEnabled(DiscordRpcPatch.isAuthorized() || busy);
    }

    private void showAbout() {
        String text;
        try (var input = getAssets().open("dyskal_discord_notices.txt")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            text = output.toString("UTF-8");
        } catch (Exception error) {
            DiscordDiagnostics.failure("Read notices", error);
            text = "Notices unavailable. Source: https://github.com/Dyskal/morphe-yt-music-discord-rpc";
        }
        new AlertDialog.Builder(this).setTitle("Dyskal Discord RPC — About")
                .setMessage(text).setPositiveButton(android.R.string.ok, null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
