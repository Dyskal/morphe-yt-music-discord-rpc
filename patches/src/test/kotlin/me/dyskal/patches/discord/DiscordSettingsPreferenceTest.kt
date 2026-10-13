package me.dyskal.patches.discord

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscordSettingsPreferenceTest {
    @Test
    fun `native settings entry preserves existing rows and targets the cloned app package`() {
        val xml = """<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"><Preference android:key="existing" android:title="Existing"/></PreferenceScreen>"""
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        addDiscordSettingsPreference(document, "app.personal.music")
        val row = document.documentElement.firstChild as Element
        assertEquals("dyskal_discord_rpc_settings", row.getAttribute("android:key"))
        assertEquals("Discord rich presence", row.getAttribute("android:title"))
        val intent = row.firstChild as Element
        assertEquals("app.personal.music", intent.getAttribute("android:targetPackage"))
        assertEquals("me.dyskal.ytmrpc.DiscordSettingsActivity", intent.getAttribute("android:targetClass"))
        assertEquals("existing", (row.nextSibling as Element).getAttribute("android:key"))

        // Updating the destination must not duplicate the settings row.
        addDiscordSettingsPreference(document, "com.google.android.apps.youtube.music")
        assertEquals(2, document.documentElement.childNodes.length)
        assertEquals("com.google.android.apps.youtube.music", (row.firstChild as Element).getAttribute("android:targetPackage"))
    }

    @Test
    fun `unexpected settings schema fails instead of silently producing an inaccessible entry`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream("<resources/>".toByteArray()))
        assertFailsWith<IllegalArgumentException> { addDiscordSettingsPreference(document, "app.music") }
    }
}
