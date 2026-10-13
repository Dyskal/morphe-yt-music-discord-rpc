# Dyskal YouTube Music Discord RPC

Experimental standalone YouTube Music patch, **compatible with Morphe**, based on the [official patches template](https://github.com/MorpheApp/morphe-patches-template). This is Dyskal's project, not an official Morphe release.

Implements the OAuth2 + PKCE approach suggested in [Morphe issue #810](https://github.com/MorpheApp/morphe-patches/issues/810). Like current Metrolist, it authenticates with the Social SDK presence scope over the Discord Gateway. It does not embed the native Social SDK or request an account token.

**Build, protocol regression tests, and bundle/settings checks pass; live Discord presence and real APK/device compatibility remain unverified.**

## Install

[Add this patch source to Morphe](https://morphe.software/add-source?github=Dyskal/morphe-yt-music-discord-rpc).

Development releases are published from `dev`; enable prereleases in your patch-source settings. Select **Discord rich presence** when patching a supported YouTube Music APK. The in-app **Discord application ID** field defaults to Dyskal's application, `1559278040175878146`; no manual ID entry is needed. This bundle adds RPC only: use the official bundle for GmsCore, ad removal, and background playback as needed.

## Streamlined setup

1. **Patch and install:** select **Discord rich presence** in Manager.
2. **Connect once:** open YouTube Music → **Settings → Discord rich presence → Connect with Discord**. Authorize in the browser; returning enables sharing automatically.
3. **Listen:** enable activity visibility in Discord and play music. Later playback automatically reconnects using the saved credentials.

If you already have a saved login for the selected application, **Connect** reconnects without opening the browser. New sign-ins ignore duplicate taps, time out after two minutes, and can be stopped with **Cancel sign-in**. Cancelled or failed sign-ins retain your previous credentials; sharing stays off until you enable it or reconnect.

### One-time setup for the application owner

In the Discord Developer Portal, application `1559278040175878146` must have Social SDK/public-client OAuth enabled, access to `sdk.social_layer_presence`, and exactly `ytmdiscord://oauth2/callback` registered as an OAuth2 redirect URI. Users of the default application do not need to create their own Discord application.

To use another application, configure it the same way and enter its ID in **YouTube Music → Settings → Discord rich presence → Discord application ID**. Tap **Connect with Discord** to save the ID and authorize that application.

The entry is added to YouTube Music's native settings page using the same intent-backed preference approach as the official settings patch. It opens this patch's login and sharing controls.

Only one installed copy should handle the callback scheme. Credentials and pending PKCE state are encrypted using Android Keystore AES-GCM. Disabling sharing retains login; **Disconnect and forget account** deletes credentials.

The application ID is stored in ordinary app preferences. No repatching is needed to change it. A different application disconnects current presence while you authorize it; its ID and credentials replace the saved login only after authorization succeeds.

## Behavior

- Song title, artist, listening activity, progress at 1× playback speed, and a YouTube Music link when metadata supplies a video ID.
- Clears on pause, buffering, stop, or track end. Rate-limited changes may take roughly five seconds.
- Delay-based heartbeats, transient reconnects, latest-track replay, and token refresh on subsequent identification. Reconnects use backoff and stop after seven unsuccessful attempts; rate limits respect `Retry-After`.
- A gateway authentication rejection triggers one forced token refresh. Revoked/invalid refresh grants stop reconnecting and ask you to sign in again; configuration failures show a separate error.
- Disconnects after one minute without active playback.
- Application-level initialization supports service-only playback starts. One-second progress polling runs only while sharing is enabled and playback is active; disabled/paused playback does not keep an idle poller running.
- Artwork resolution is not implemented.
- Long notices are available under **About and licenses**, rather than filling the connection screen.

## 🩹 Patches list

<!-- PATCHES_START -->
> **[v1.0.0-dev.1](https://github.com/Dyskal/morphe-yt-music-discord-rpc/releases/tag/v1.0.0-dev.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;9.40.51 | 🧪&nbsp;9.39.52 | 🧪&nbsp;9.38.51 | 🧪&nbsp;9.20.53 | 🧪&nbsp;9.15.51 |
| :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Discord rich presence](#discord-rich-presence) | Shares music on Discord with OAuth2 + PKCE. Open the Discord RPC app shortcut to configure your own Discord application. |  |

</details>

<!-- PATCHES_END -->

## Build and verify

Requirements: JDK 21, Android SDK platform/build tools 36, and GitHub Packages credentials with `read:packages`. Set `GITHUB_ACTOR`/`GITHUB_TOKEN`, or `gpr.user`/`gpr.key` in your user Gradle properties. Do not commit credentials.

CI uses the workflow's built-in `GITHUB_TOKEN` to download published dependencies from GitHub Packages, following the official template.

```powershell
.\gradlew.bat :patches:buildAndroid :patches:test
```

The bundle is `patches/build/libs/patches-*.mpp`. `:patches:test` also runs the extension's normal Android-module JVM tests (`:extensions:extension:testDebugUnitTest`). Local HTTP/WebSocket tests cover OAuth forms, error classification, timeouts/cancellation, duplicate and stale authorization attempts, forced refresh, heartbeat delays, reconnects, and presence coalescing. Bundle/settings tests check R8 retention and the native settings entry. These tests do not exercise Android Keystore, real preference rendering, or Discord's live acceptance of the OAuth gateway flow.

Optional real-APK validation and unsigned output:

```powershell
.\gradlew.bat :patches:validateDiscord "-PdiscordApk=C:\path\music.apk"
.\gradlew.bat :patches:validateDiscord "-PdiscordApk=C:\path\music.apk" "-PdiscordOutput=C:\existing-folder\music-discord-unsigned.apk"
```

Device checks still needed: browser return, actual visible presence, refresh, background playback, skips/seeks, pause/resume, network recovery, and disabling sharing. If OAuth identification is rejected, verify application scope/public-client setup before considering a personal-token fallback.

Diagnostics use controlled error categories, HTTP/gateway status codes, and exception type names. Tokens, authorization codes, raw server bodies, and gateway frames are not logged:

```text
adb logcat -s DyskalDiscordRPC AndroidRuntime
```

## Development and releases

Use `dev` for development and semantic commits (`feat:`, `fix:`, `chore:`). The template's existing `release.yml` and `.releaserc` generate releases, metadata, and changelogs. Merge `dev` into `main` when ready for a stable release; do not manually edit release-generated files.

## License and attribution

[GPLv3](LICENSE), with the additional terms in [NOTICE](NOTICE). Upstream MediaSession fingerprints retain their original copyright and [notice](UPSTREAM-NOTICE). Runtime dependencies and source references are documented in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
