# Third-party notices

This project is a separately branded derivative of the official Morphe patches template. MediaSession fingerprints are adapted from Morphe Patches (`e54643954`), copyright 2026 Morphe, under GPLv3 with the additional conditions in `UPSTREAM-NOTICE`.

The local-only Android extension declares no third-party runtime dependencies. Android framework APIs and `org.json` are provided by the host platform. No Discord SDK code or native binaries are redistributed.

Upstream Morphe notices are included in [`patches/src/main/resources/discord/notices.txt`](patches/src/main/resources/discord/notices.txt), copied into the patched app's assets, and displayed under **About and licenses**.

The profile-headline approach was informed by https://github.com/kernoeb/mac-now-playing. Its Swift source was not copied. Artwork uses the original player's public image URL or a YouTube thumbnail rather than fetching third-party catalog matches.

The local Binder wire contract was inspected in Discord Social SDK 1.10.19337 and Discord Android 353.11. The standalone adapter is original code, not a copy of the SDK's Java implementation; see CONTRIBUTING.md for the observed contract and compatibility limitations.

Test-only dependencies (JUnit, Robolectric and its Android framework shadows, and org.json) are not bundled into patched apps.
