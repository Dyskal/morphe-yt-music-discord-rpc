# Dyskal YouTube Music Discord RPC

A [Morphe](https://morphe.software) add-on that shares what you're listening to on YouTube Music as your Discord activity.

## Experimental v2: local Discord RPC

This branch publishes **2.0.0-alpha.N** prereleases of the local-only prototype. In Morphe's patch sources, add this JSON URL as a separate source:

```text
https://raw.githubusercontent.com/Dyskal/morphe-yt-music-discord-rpc/refs/heads/experiment/local-rpc-v2/patches-bundle.json
```

Disable the existing Discord RPC source while testing this one; do not apply both versions together. Apply this source alongside official Morphe Settings, then:

1. Open the official Discord Android app and sign in there. This add-on does not ask for account permissions in local mode.
2. In YTM → **Settings → Morphe → Misc → Discord rich presence**, enable **Share currently playing music** and play a song.
3. Check **Connection status** for connection or rejection errors; use **Connect / retry local Discord** to retry.

Sharing starts off and must be enabled explicitly. Only the official `com.discord` app is supported. Artwork, listening activities, links, and background behavior still need real-device verification. OAuth, browser sign-in, token storage, and Gateway connections have been removed; there is no fallback if local RPC is unavailable.

## Features

- Song title, artist, album name, and cover art.
- Click the title to open the song, the artist to open their page, or the cover to open the album.
- Playback progress at normal speed.
- Choose the artist or **YouTube Music** as your profile headline.
- Clears your activity when playback pauses or stops, and reconnects automatically when you listen again.

## Install

1. Add the experimental JSON source above to Morphe. The normal repository source still follows the stable/dev channels, not this branch.
2. Apply it alongside **official Morphe Patches 2.0.0 or later**, including the **Settings** patch.
3. Select **Discord rich presence**, patch a supported YouTube Music APK, and install it.

This direct JSON source follows the experimental branch automatically; the normal source's prerelease toggle does not select it.

## Connect to Discord

1. Open YouTube Music → **Settings → Morphe → Misc → Discord rich presence**.
2. Open the official Discord Android app and sign in there, then enable **Share currently playing music** in YTM.
3. Enable activity sharing in Discord and play some music.

The default Discord application is ready to use; no application ID or developer account is required.

### Settings

- **Share currently playing music:** turn sharing on, or turn it off to clear presence and disconnect.
- **Profile headline:** show the artist name or **YouTube Music**.
- **Connect / retry local Discord:** enable sharing and retry the local connection.
- **Discord application ID:** optionally use your own application; see [custom application setup](CONTRIBUTING.md#custom-discord-application).

Changes take effect without repatching. This add-on never signs in to your account or reads/stores Discord credentials. V2 uses separate settings, so v1's sharing flag does not automatically enable local sharing.

## Troubleshooting

Check **Connection status** if sharing stops working. Open/update the official Discord app, confirm you are signed in there and activity sharing is enabled, then tap **Connect / retry local Discord**. A connection acknowledgement does not guarantee that Discord has published the activity.

Activity changes can take a few seconds to appear, and link behavior can vary between Discord clients.

## 🩹 Patches list

<!-- PATCHES_START -->
> **[v2.0.0-alpha.1](https://github.com/Dyskal/morphe-yt-music-discord-rpc/releases/tag/v2.0.0-alpha.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`experiment/local-rpc-v2`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Package name:** `com.google.android.apps.youtube.music`

**🎯 Supported versions:**

| 🧪&nbsp;9.43.51 | 🧪&nbsp;9.42.52 | 🧪&nbsp;9.41.52 | 9.30.53 | 9.20.53 | 9.15.51 |
| :---: | :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Discord rich presence](#discord-rich-presence) | Adds Discord rich presence, cover art, and clickable music links. Configure it under Morphe → Misc → Discord rich presence. Requires official Morphe Settings (2.0.0+). |  |

</details>

<!-- PATCHES_END -->

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, diagnostics, and the release workflow.

## License and attribution

[GPLv3](LICENSE), with the additional terms in [NOTICE](NOTICE). Based on the [official Morphe patches template](https://github.com/MorpheApp/morphe-patches-template). Upstream fingerprints retain their [copyright and notice](UPSTREAM-NOTICE). Dependencies and source references are documented in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
