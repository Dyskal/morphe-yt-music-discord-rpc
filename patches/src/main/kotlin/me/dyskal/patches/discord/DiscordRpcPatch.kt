package me.dyskal.patches.discord

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lme/dyskal/ytmrpc/DiscordRpcPatch;"

private val discordResourcesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0)
            val settings = manifest.createElement("activity").apply {
                setAttribute("android:name", "me.dyskal.ytmrpc.DiscordSettingsActivity")
                setAttribute("android:exported", "false")
                setAttribute("android:label", "Discord RPC settings")
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault")
            }
            application.appendChild(settings)
            val activity = manifest.createElement("activity").apply {
                setAttribute("android:name", "me.dyskal.ytmrpc.DiscordOAuthActivity")
                setAttribute("android:exported", "true")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            }
            val filter = manifest.createElement("intent-filter")
            fun child(tag: String, attribute: String, value: String) {
                filter.appendChild(manifest.createElement(tag).apply { setAttribute(attribute, value) })
            }
            child("action", "android:name", "android.intent.action.VIEW")
            child("category", "android:name", "android.intent.category.DEFAULT")
            child("category", "android:name", "android.intent.category.BROWSABLE")
            filter.appendChild(manifest.createElement("data").apply {
                setAttribute("android:scheme", "ytmdiscord")
                setAttribute("android:host", "oauth2")
                setAttribute("android:path", "/callback")
            })
            activity.appendChild(filter)
            application.appendChild(activity)
        }
    }

    finalize {
        // Like the official settings patch, use Music's native settings_headers preference screen.
        // Read the manifest after execution so GmsCore/clone patches' package changes are reflected.
        val packageName = document("AndroidManifest.xml").use { it.documentElement.getAttribute("package") }
        document("res/xml/settings_headers.xml").use { addDiscordSettingsPreference(it, packageName) }
    }
}

@Suppress("unused")
val discordRpcPatch = bytecodePatch(
    name = "Discord rich presence",
    description = "Shares music on Discord with OAuth2 + PKCE. Uses Dyskal's Discord application by default; sign in from YouTube Music Settings → Discord rich presence.",
    default = true
) {
    dependsOn(discordResourcesPatch)
    compatibleWith(Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000,
        // Semantic media-session hooks are inherited from upstream. All targets remain experimental until APK/device validation.
        targets = listOf("9.40.51", "9.39.52", "9.38.51", "9.20.53", "9.15.51")
            .map { AppTarget(version = it, isExperimental = true) }
    ))
    extendWith("extensions/discord.mpe")
    execute {
        MusicActivityOnCreateFingerprint.method.addInstruction(
            0, "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->initialize(Landroid/content/Context;)V"
        )
        MediaSessionSetMetadataFingerprint.let {
            val index = it.instructionMatches.first().index
            val register = it.method.getInstruction<FiveRegisterInstruction>(index).registerD
            it.method.addInstruction(index, "invoke-static {v$register}, $EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V")
        }
        MediaSessionSetPlaybackStateFingerprint.clearMatch()
        MediaSessionSetPlaybackStateFingerprint.let {
            val index = it.instructionMatches.first().index
            val register = it.method.getInstruction<FiveRegisterInstruction>(index).registerD
            it.method.addInstruction(index, "invoke-static {v$register}, $EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V")
        }
    }
}
