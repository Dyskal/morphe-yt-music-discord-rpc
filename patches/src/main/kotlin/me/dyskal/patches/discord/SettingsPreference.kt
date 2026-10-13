package me.dyskal.patches.discord

import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PREFERENCE_KEY = "dyskal_discord_rpc_settings"

/** Intent-backed native preference, matching the official YouTube Music settings integration. */
internal fun addDiscordSettingsPreference(document: Document, packageName: String) {
    require(packageName.isNotBlank()) { "Missing target app package name" }
    val root = document.documentElement
    require(root.tagName == "PreferenceScreen" || root.tagName == "androidx.preference.PreferenceScreen") {
        "Unexpected YouTube Music settings_headers root: ${root.tagName}"
    }
    val existing = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        .filterIsInstance<Element>().firstOrNull { it.getAttribute("android:key") == PREFERENCE_KEY }
    val preference = existing ?: document.createElement("Preference").also { root.insertBefore(it, root.firstChild) }
    preference.setAttribute("android:key", PREFERENCE_KEY)
    preference.setAttribute("android:title", "Discord rich presence")
    preference.setAttribute("android:summary", "Connect Discord and share currently playing music")
    preference.setAttribute("android:persistent", "false")

    while (preference.hasChildNodes()) preference.removeChild(preference.firstChild)
    preference.appendChild(document.createElement("intent").apply {
        setAttribute("android:action", "android.intent.action.VIEW")
        setAttribute("android:targetPackage", packageName)
        setAttribute("android:targetClass", "me.dyskal.ytmrpc.DiscordSettingsActivity")
    })
}
