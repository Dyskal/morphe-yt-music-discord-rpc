package me.dyskal.ytmrpc;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standalone settings, launched by the dynamic app shortcut without upstream settings classes. */
public final class DiscordSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CheckBox enabled;
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(DiscordRpcPatch.status());
            enabled.setChecked(DiscordRpcPatch.isSharingEnabled());
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
        instructions.setText("Your Discord application ID is configured in Morphe Manager's patch options. "
                + "Tap Connect to authorize Discord. Changing the application ID requires repatching.");
        content.addView(instructions);

        enabled = new CheckBox(this);
        enabled.setText("Share currently playing music");
        enabled.setChecked(DiscordRpcPatch.isSharingEnabled());
        enabled.setOnClickListener(view -> DiscordRpcPatch.setSharingEnabled(enabled.isChecked()));
        content.addView(enabled);

        Button connect = new Button(this);
        connect.setText("Connect with Discord");
        connect.setOnClickListener(view -> DiscordRpcPatch.authorize(getApplicationContext()));
        content.addView(connect);

        Button disconnect = new Button(this);
        disconnect.setText("Disconnect and forget account");
        disconnect.setOnClickListener(view -> DiscordRpcPatch.disconnect(getApplicationContext()));
        content.addView(disconnect);

        status = new TextView(this);
        content.addView(status);
        TextView notices = new TextView(this);
        notices.setText("Experimental personal patch by Dyskal. Live Discord compatibility is not yet verified.\n\n"
                + "Uses OkHttp, Okio and the Kotlin standard library, licensed under Apache License 2.0. "
                + "Source and full dependency notices: github.com/Dyskal/morphe-yt-music-discord-rpc");
        content.addView(notices);
        TextView upstreamNotice = new TextView(this);
        upstreamNotice.setText("""
                Morphe NOTICE

                https://github.com/MorpheApp/morphe-patches

                =============

                GPLv3 Section 7b: Preservation of User Notices

                All derivative works (source code and binaries) must include this
                NOTICE text and provide it to the software user.

                Portions of this software are provided "AS IS" by the Morphe software project.
                Any express or implied warranties, including the implied warranties of
                merchantability and fitness for a particular purpose, are disclaimed.


                GPLv3 Section 7c: Prohibiting Misrepresentation of Origin

                You are prohibited from misrepresenting the origin of the Program,
                and modified versions of the Program must be identified and marked in
                reasonable ways as different from the original version so as not to cause
                confusion regarding their origin.


                GPLv3 Section 7e: Declining Grant of Trademark Rights

                This License does not grant any rights or permission under trademark law
                to use the name "Morphe" or any of the Program's trade names, trademarks,
                service marks, or logos.
                """);
        content.addView(upstreamNotice);
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
