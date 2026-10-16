package me.dyskal.patches.discord

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DiscordBundleTest {
    @Test
    fun `packaged extension retains runtime hooks settings callback and WebSocket dependency`() {
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream, "The bundle must include its own Android extension")
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val classes = dex.classes.associateBy { it.type }
        for (type in listOf("DiscordOAuthActivity")) {
            val activity = classes["Lme/dyskal/ytmrpc/$type;"]
            assertNotNull(activity, "R8 must retain manifest-injected $type")
            assertTrue(activity.methods.any { it.name == "onCreate" })
        }
        val controls = classes["Lme/dyskal/ytmrpc/DiscordPreferences;"]
        assertNotNull(controls)
        assertEquals("Landroid/preference/Preference;", controls.superclass)
        assertTrue(controls.methods.any { it.name == "onAttachedToActivity" })
        assertTrue(!classes.containsKey("Lme/dyskal/ytmrpc/DiscordSettingsActivity;"))
        val hooks = classes["Lme/dyskal/ytmrpc/DiscordRpcPatch;"]
        assertNotNull(hooks)
        for (name in listOf("initialize", "getApplicationId", "onSetMetadata", "onSetPlaybackState", "onNativeVideoId", "onNativeQueueMetadata")) {
            assertTrue(hooks.methods.any { it.name == name })
        }
        assertTrue(classes.containsKey("Lokhttp3/OkHttpClient;"))
        assertTrue(classes.keys.none { it.startsWith("Lapp/morphe/extension/") }, "Add-on must reuse the host's themed UI, not bundle duplicate Morphe classes")
    }

    @Test
    fun `application ID is configured at runtime with the packaged Dyskal default`() {
        assertTrue(discordRpcPatch.options.isEmpty(), "The application ID must not require a Manager option")
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream)
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val runtime = dex.classes.single { it.type == "Lme/dyskal/ytmrpc/DiscordRpcPatch;" }
        val defaultId = runtime.fields.single { it.name == "DEFAULT_APPLICATION_ID" }.initialValue as StringEncodedValue
        assertEquals("1559278040175878146", defaultId.value)
    }
}
