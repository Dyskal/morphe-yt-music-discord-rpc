/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Adapted MediaSession fingerprints for Dyskal's standalone Discord patch.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.dyskal.patches.discord

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal object MusicActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

internal object MediaSessionSetMetadataFingerprint : Fingerprint(
    filters = listOf(methodCall(
        definingClass = "Landroid/media/session/MediaSession;",
        name = "setMetadata",
        parameters = listOf("Landroid/media/MediaMetadata;")
    ))
)

internal object MediaSessionSetPlaybackStateFingerprint : Fingerprint(
    filters = listOf(methodCall(
        definingClass = "Landroid/media/session/MediaSession;",
        name = "setPlaybackState",
        parameters = listOf("Landroid/media/session/PlaybackState;")
    ))
)
