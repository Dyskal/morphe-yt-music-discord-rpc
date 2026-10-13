/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Adapted application and MediaSession fingerprints for Dyskal's standalone Discord patch.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package me.dyskal.patches.discord

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal object MusicApplicationOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("activity"))
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
