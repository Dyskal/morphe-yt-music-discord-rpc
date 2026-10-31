package me.dyskal.patches.discord

import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val ADD_ON_PREFERENCES = "morphe_addon_prefs.xml"
internal const val MORPHE_TEXT_PREFERENCE = "app.morphe.extension.shared.settings.preference.ResettableEditTextPreference"
internal const val MORPHE_LIST_PREFERENCE = "app.morphe.extension.shared.settings.preference.CustomDialogListPreference"
private object ResourceAnchor
internal fun discordNoticesText(): String = requireNotNull(
    ResourceAnchor.javaClass.getResourceAsStream("/discord/notices.txt")
) { "Missing bundled Discord notices" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

/** Reuse Morphe's themed components through the official add-on merge. */
internal fun addDiscordPreferences(document: Document) {
    val root = document.documentElement
    require(root.tagName == "morphe-add-on-preferences") { "Unexpected Morphe add-on declaration root" }
    val alreadyAdded = document.getElementsByTagName("PreferenceScreen")
    if ((0 until alreadyAdded.length).any {
        (alreadyAdded.item(it) as Element).getAttribute("android:key") == "dyskal_discord_rpc_settings"
    }) return

    val target = document.createElement("screen").apply { setAttribute("key", "morphe_settings_music_screen_9_misc") }
    val screen = document.createElement("PreferenceScreen").apply {
        setAttribute("android:key", "dyskal_discord_rpc_settings")
        setAttribute("android:title", "Discord rich presence")
        setAttribute("android:summary", "Connection, cover art, and profile headline")
        setAttribute("android:persistent", "false")
    }
    fun row(tag: String, key: String, title: String): Element = document.createElement(tag).apply {
        setAttribute("android:key", key)
        setAttribute("android:title", title)
        setAttribute("android:persistent", "false")
        screen.appendChild(this)
    }
    row(MORPHE_TEXT_PREFERENCE, "dyskal_discord_application_id", "Discord application ID").apply {
        setAttribute("android:dialogTitle", "Discord application ID")
        setAttribute("android:inputType", "number")
        setAttribute("android:summary", "Defaults to Dyskal's application; changing it requires signing in again")
    }
    row("Preference", "dyskal_discord_connect", "Connect with Discord")
    row("Preference", "dyskal_discord_cancel", "Cancel sign-in")
    row("SwitchPreference", "dyskal_discord_enabled", "Share currently playing music")
    row(MORPHE_LIST_PREFERENCE, "dyskal_discord_headline", "Profile headline")
    row("Preference", "dyskal_discord_disconnect", "Disconnect and forget account")
    row("Preference", "dyskal_discord_about", "About and licenses")
    row("me.dyskal.ytmrpc.DiscordPreferences", "dyskal_discord_status", "Connection status").apply {
        setAttribute("android:selectable", "false")
    }
    target.appendChild(screen); root.appendChild(target)
}
