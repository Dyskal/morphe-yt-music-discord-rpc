# Third-party notices

This project is a separately branded derivative of the official Morphe patches template. MediaSession fingerprints are adapted from Morphe Patches (`e54643954`), copyright 2026 Morphe, under GPLv3 with the additional conditions in `UPSTREAM-NOTICE`.

Runtime libraries bundled into the Android extension:

| Component | Copyright / source | License |
| --- | --- | --- |
| OkHttp 4.12.0 | Square, Inc.; https://github.com/square/okhttp | Apache License 2.0 |
| Okio (OkHttp dependency) | Square, Inc.; https://github.com/square/okio | Apache License 2.0 |
| Kotlin standard library (OkHttp/Okio dependency) | JetBrains s.r.o. and contributors; https://github.com/JetBrains/kotlin | Apache License 2.0 |

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

The protocol design was informed by Metrolist's OAuth/Gateway implementation. Its Kotlin source was not copied into this project. The migrated Java implementation was written for this personal patch.

Test-only dependencies (JUnit, MockWebServer, org.json and Robolectric Android API classes) are not bundled into patched apps.
