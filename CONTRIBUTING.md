# Development

## Build and test

Requirements: JDK 21, Android SDK platform/build tools 36, and GitHub Packages credentials with `read:packages`.

Set `GITHUB_ACTOR`/`GITHUB_TOKEN`, or store `gpr.user`/`gpr.key` in your **user-level** Gradle properties. Keep credentials out of the repository. CI uses its built-in `GITHUB_TOKEN`.

```powershell
.\gradlew.bat :patches:buildAndroid test :extensions:extension:lintRelease
```

The patch bundle is written to `patches/build/libs/patches-*.mpp`. On Linux/macOS, use `./gradlew`.

### Real-APK validation

Apply the add-on with an official Morphe Patches bundle and run SDK DEX/hierarchy checks:

The current baseline is Morphe Patches **2.0.0**, Patcher **2.1.0**, and patches Gradle plugin **2.1.0-dev.1**. Build tooling follows the official patches template; YTM target versions and experimental flags follow the Morphe Patches release's compatibility declaration.

```powershell
.\gradlew.bat :patches:validateDiscord "-PdiscordApk=C:\path\music.apk" "-PmorpheBundle=C:\path\official-patches.mpp"
```

To also produce an unsigned APK, add `"-PdiscordOutput=C:\existing-folder\music-discord-unsigned.apk"`.

After runtime changes, check background track changes, rapid skips, pause/resume after more than a minute, reconnects, application-ID changes, and Discord process termination/restart. SDK checks do not replace device testing.

## Diagnostics

The in-app **Connection status** reports connection and artwork errors. To read app diagnostics:

```text
adb logcat -s DyskalDiscordRPC
```

Logs contain controlled error categories and metadata capability flags, not raw IPC frames or server responses.

## Custom Discord application

The default application (`1559278040175878146`) needs no user setup. To use your own:

1. Register an application with Social SDK enabled in the Discord Developer Portal.
2. Set **Discord application ID** in the add-on's settings. Changing it turns sharing off; enable sharing again to reconnect.

The local handshake uses the application ID, not OAuth scopes, redirects, account tokens, or browser authorization. Preserve connection epochs and per-session callback guards when changing asynchronous code.

## Releases

### Local RPC v2 prototype

`experiment/local-rpc-v2` publishes `2.0.0-alpha.N` prereleases on the `local-rpc` release channel without changing `main` or `dev`. Semantic-release generates versions and the branch's `patches-bundle.json`; Morphe consumes it through the direct JSON source documented in the README. The repository source's normal prerelease toggle follows `dev`, not this experiment.

V2 uses only pure-Java Binder RPC: OAuth, Gateway, encrypted token storage, HTTP artwork resolution, AndroidX Browser, and OkHttp have been removed. The v1 implementation remains in Git history. V2 uses a separate preference store and never reads the old credentials or sharing flag. Existing encrypted v1 data is left untouched rather than adding migration code. This is an experimental prerelease, not a stable v2 release.

The wire contract was inspected in the supplied **Social SDK 1.10.19337** (`lib/release/discord_partner_sdk.aar`, `libs/discord_partner_sdk.jar`) and **Discord Android 353.11 / 353011** (`base.apk`):

- The exported service is `com.discord.socialrpc.DiscordRpcService`, resolved through action `com.discord.socialsdk.rpc.IDiscordRpcService`, restricted to package `com.discord`.
- Service transaction 1 takes the application ID as a `long`, protocol version `"1"`, and a callback Binder, then returns a connection Binder. It replaces the desktop socket handshake; no binary opcode/length framing is sent.
- Connection transactions 1 and 2 are `sendFrame(String)` and `disconnect()`. Callback transactions 1 and 2 are `onFrame(String)` and `onClose(int, String)`.
- Frames are JSON `SET_ACTIVITY` requests with a nonce; clearing uses an explicit null activity. The client waits for `READY`, matches acknowledgements, coalesces updates, and ignores callbacks from previous sessions.
- The observed Discord activity normalizer accepts milliseconds as well as seconds. Activities use millisecond timestamps and RPC `{label, url}` buttons. Cover URLs go directly to Discord rather than through an authenticated external-assets request.

`DiscordLocalBinder` is an original wire adapter, not redistributed SDK code. No proprietary `.aar`, `.jar`, or `.so` is bundled. This uses an internal SDK transport rather than an official Java API; future Discord versions can change it. Official documentation confirms Android unauthenticated RPC support, but does not guarantee that a standalone implementation is supported or establish its legal/ToS status:

- https://docs.discord.com/developers/discord-social-sdk/core-concepts/mobile
- https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence#rich-presence-without-authentication

Robolectric tests cover the Binder parcel contract, callback UID checks, READY gating, acknowledgement-based coalescing, reconnects/stale callbacks, sanitized errors, and clear/disconnect teardown. Pure JVM tests cover activity fields and framing. Bundle tests reject the old OAuth/HTTP classes and dependencies. Test dependencies do not ship in the extension.

Device checklist: install alongside Settings, enable sharing, and verify listening type, title/artist/album links, raw-URL artwork, progress, rapid skips, pause/resume, background playback, Discord termination/restart, sharing-off, application-ID changes, and persistence after YTM restarts. An RPC acknowledgement means the local service accepted the frame, not proof that Discord's servers published it.

### Release branches

Develop on `dev` using semantic commits (`feat:`, `fix:`, `chore:`). Merge `dev` into `main` for a stable release.

The release workflow generates versions, changelogs, patch-source metadata, and the README patch list. Update patch declarations rather than hand-editing generated JSON. Preserve the README's `PATCHES_START`/`PATCHES_END` markers.

Keep CI release commits in branch history. Publish fixes as new commits and use normal pushes; do not amend published commits or force-push release branches.
