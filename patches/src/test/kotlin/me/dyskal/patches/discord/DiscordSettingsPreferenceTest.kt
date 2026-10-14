package me.dyskal.patches.discord

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscordSettingsPreferenceTest {
    @Test
    fun `add-on declaration targets Morphe Misc and uses standard preference components`() {
        val xml = """<morphe-add-on-preferences xmlns:android="http://schemas.android.com/apk/res/android"><screen key="other"><Preference android:key="other_addon"/></screen></morphe-add-on-preferences>"""
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        addDiscordPreferences(document)
        assertEquals(2, document.documentElement.childNodes.length)
        val target = document.documentElement.lastChild as Element
        assertEquals("morphe_settings_music_screen_9_misc", target.getAttribute("key"))
        val screen = target.firstChild as Element
        assertEquals("PreferenceScreen", screen.tagName)
        assertEquals("dyskal_discord_rpc_settings", screen.getAttribute("android:key"))
        assertEquals(1, screen.getElementsByTagName("EditTextPreference").length)
        assertEquals(1, screen.getElementsByTagName("SwitchPreference").length)
        assertEquals(1, screen.getElementsByTagName("ListPreference").length)
        assertEquals(0, screen.getElementsByTagName("intent").length)
        for (i in 0 until screen.childNodes.length) {
            assertEquals("false", (screen.childNodes.item(i) as Element).getAttribute("android:persistent"))
        }
        addDiscordPreferences(document)
        assertEquals(2, document.documentElement.childNodes.length)
        assertEquals("other_addon", ((document.documentElement.firstChild as Element).firstChild as Element).getAttribute("android:key"))
    }

    @Test
    fun `unexpected declaration schema fails clearly`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream("<resources/>".toByteArray()))
        assertFailsWith<IllegalArgumentException> { addDiscordPreferences(document) }
    }
}
