# Dyskal YouTube Music Discord RPC

YouTube Music add-on patch, **compatible with Morphe**, based on the [official patches template](https://github.com/MorpheApp/morphe-patches-template). This is Dyskal's project, not an official Morphe release.

Implements the OAuth2 + PKCE approach suggested in [Morphe issue #810](https://github.com/MorpheApp/morphe-patches/issues/810). Like current Metrolist, it authenticates with the Social SDK presence scope over the Discord Gateway. It does not embed the native Social SDK or request an account token.

**The core OAuth/presence flow was confirmed by the author on YouTube Music 9.20.53 with Morphe Patches 1.47.0. Native metadata hooks now apply and pass SDK DEX/hierarchy validation on the unpatched 9.20.53 APK. The richer cover/ID data and explicit browser return still need verification in an updated device build.**

## Install

[Add this patch source to Morphe](https://morphe.software/add-source?github=Dyskal/morphe-yt-music-discord-rpc).

Development releases are published from `dev`; enable prereleases in your patch-source settings. Apply this add-on **together with the official Morphe Patches bundle, version 1.47.0 or later, including its Settings patch**. Select **Discord rich presence** when patching a supported YouTube Music APK. The in-app application ID defaults to Dyskal's application, `1559278040175878146`; no manual ID entry is needed. The official bundle provides the settings framework, GmsCore, ad removal, and background playback.

## Streamlined setup

1. **Patch and install:** select **Discord rich presence** in Manager.
2. **Connect once:** open YouTube Music → **Settings → Morphe → Misc → Discord rich presence → Connect with Discord**. Authorize in the browser; the callback brings Morphe settings back to the foreground and enables sharing after successful exchange.
3. **Listen:** enable activity visibility in Discord and play music. Later playback automatically reconnects using the saved credentials.

If you already have a saved login for the selected application, **Connect** reconnects without opening the browser. New sign-ins ignore duplicate taps, time out after two minutes, and can be stopped with **Cancel sign-in**. Cancelled or failed sign-ins retain your previous credentials; sharing stays off until you enable it or reconnect.

### One-time setup for the application owner

In the Discord Developer Portal, application `1559278040175878146` must have Social SDK/public-client OAuth enabled, access to `sdk.social_layer_presence`, and exactly `ytmdiscord://oauth2/callback` registered as an OAuth2 redirect URI. Users of the default application do not need to create their own Discord application.

To use another application, configure it the same way and edit **Discord application ID** in the Discord preference screen. Saving a different ID disconnects the current login; tap **Connect with Discord** to authorize the new application.

Morphe's add-on preference API inserts a nested screen into **Misc**, using standard `PreferenceScreen`, `EditTextPreference`, `SwitchPreference`, and `ListPreference` components. There is no custom settings activity or extra top-level Settings entry. The rows are self-managed and stored in this add-on's private preferences, outside Morphe's settings import/export.

Only one installed copy should handle the callback scheme. Credentials and pending PKCE state are encrypted using Android Keystore AES-GCM. Disabling sharing retains login; **Disconnect and forget account** deletes credentials.

No repatching is needed to change the application ID or appearance. Same-application reconnects reuse saved credentials; new credentials are committed only after successful OAuth.

## Behavior

- Song title, artist, listening activity, progress at 1× playback speed, and direct title/artist/album cover links. The add-on captures Music's current native queue renderer and player video ID, rather than relying exclusively on MediaSession metadata (9.20.53 omits IDs and artwork URI there).
- Queue byline navigation endpoints supply real artist channel IDs and album browse IDs; title links use the current player video ID. Missing IDs omit a hyperlink instead of substituting a name search. Upcoming/unrelated queue/menu entries are not used to guess an artist or album.
- The native renderer supplies the largest public cover URL. MediaSession image URIs and a YouTube-thumbnail fallback remain secondary sources. Discord's external-assets API resolves URLs into `mp:external` references. A bounded per-application cache avoids repeated lookups; pause, skip, logout, and account changes cancel stale work. Artwork errors leave text presence working and are shown in the connection summary.
- **Profile headline** defaults to **Artist**, replacing “YouTube Music” under your Discord name. Select **YouTube Music** to use the application name. Missing artist metadata falls back to the application name.
- Clears on pause, buffering, stop, or track end. Rate-limited changes may take roughly five seconds.
- Delay-based heartbeats, transient reconnects, latest-track replay, and token refresh on subsequent identification. Reconnects use backoff and stop after seven unsuccessful attempts; rate limits respect `Retry-After`.
- A gateway authentication rejection triggers one forced token refresh. Revoked/invalid refresh grants stop reconnecting and ask you to sign in again; configuration failures show a separate error.
- Disconnects after one minute without active playback.
- Application-level initialization supports service-only playback starts. One-second progress polling runs only while sharing is enabled and playback is active; disabled/paused playback does not keep an idle poller running.
- Long notices are available under **About and licenses**, rather than filling the connection screen.

## 🩹 Patches list

<!-- PATCHES_START -->
> **[v1.0.0-dev.4](https://github.com/Dyskal/morphe-yt-music-discord-rpc/releases/tag/v1.0.0-dev.4)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;9.40.51 | 🧪&nbsp;9.39.52 | 🧪&nbsp;9.38.51 | 🧪&nbsp;9.20.53 | 🧪&nbsp;9.15.51 |
| :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Discord rich presence](#discord-rich-presence) | Adds Discord rich presence, cover art, and clickable music links. Configure it under Morphe → Misc → Discord rich presence. Requires official Morphe Settings (1.47.0+). |  |

</details>

<!-- PATCHES_END -->

## Build and verify

Requirements: JDK 21, Android SDK platform/build tools 36, and GitHub Packages credentials with `read:packages`. Set `GITHUB_ACTOR`/`GITHUB_TOKEN`, or `gpr.user`/`gpr.key` in your user Gradle properties. Do not commit credentials.

CI uses the workflow's built-in `GITHUB_TOKEN` to download published dependencies from GitHub Packages, following the official template.

```powershell
.\gradlew.bat :patches:buildAndroid :patches:test
```

The bundle is `patches/build/libs/patches-*.mpp`. `:patches:test` also runs the extension's normal Android-module JVM tests (`:extensions:extension:testDebugUnitTest`). Tests cover OAuth, cancellation, refresh, heartbeats, reconnects, presence coalescing, artwork/cache isolation, native renderer decoding, direct hyperlinks, and headline selection. Bundle/settings tests check R8 retention and the Morphe add-on declaration. Real-APK validation checks the generated native projections and cross-DEX class hierarchy. These checks do not substitute for Android Keystore, preference rendering, or Discord clients' cover/hyperlink rendering tests.

Optional real-APK validation and unsigned output:

SDK DEX/hierarchy checks do not run ART's full register-type verifier. A regression test checks the generated queue helper's instance receiver register; an updated-device launch remains required.

```powershell
.\gradlew.bat :patches:validateDiscord "-PdiscordApk=C:\path\music.apk" "-PmorpheBundle=C:\path\official-patches.mpp"
.\gradlew.bat :patches:validateDiscord "-PdiscordApk=C:\path\music.apk" "-PmorpheBundle=C:\path\official-patches.mpp" "-PdiscordOutput=C:\existing-folder\music-discord-unsigned.apk"
```

Device checks for this update: OAuth's foreground return, native metadata capability flags, cover rendering, direct title/artist/album URLs, and rapid track skips. `Native track metadata: video=..., artist=..., album=..., cover=...` logs presence of fields without dumping renderer data. If cover lookup starts but fails, **Connection status** reports its Discord artwork HTTP error separately. Discord clients can differ in hyperlink rendering.

Diagnostics use controlled error categories, HTTP/gateway status codes, and exception type names. Tokens, authorization codes, raw server bodies, and gateway frames are not logged:

```text
adb logcat -s DyskalDiscordRPC AndroidRuntime
```

## Development and releases

Use `dev` for development and semantic commits (`feat:`, `fix:`, `chore:`). The template's existing `release.yml` and `.releaserc` generate releases, metadata, and changelogs. Merge `dev` into `main` when ready for a stable release; do not manually edit release-generated files.

## License and attribution

[GPLv3](LICENSE), with the additional terms in [NOTICE](NOTICE). Upstream MediaSession fingerprints retain their original copyright and [notice](UPSTREAM-NOTICE). Runtime dependencies and source references are documented in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
