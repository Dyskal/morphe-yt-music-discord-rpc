package app.dyskal.patches.discord

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiscordBundleTest {
    @Test
    fun `packaged extension retains runtime hooks settings callback and WebSocket dependency`() {
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream, "The bundle must include its own Android extension")
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val classes = dex.classes.associateBy { it.type }
        for (type in listOf("DiscordOAuthActivity", "DiscordSettingsActivity")) {
            val activity = classes["Lapp/dyskal/ytmrpc/$type;"]
            assertNotNull(activity, "R8 must retain manifest-injected $type")
            assertTrue(activity.methods.any { it.name == "onCreate" })
        }
        val hooks = classes["Lapp/dyskal/ytmrpc/DiscordRpcPatch;"]
        assertNotNull(hooks)
        for (name in listOf("initialize", "onSetMetadata", "onSetPlaybackState")) {
            assertTrue(hooks.methods.any { it.name == name })
        }
        assertTrue(classes.containsKey("Lokhttp3/OkHttpClient;"))
        assertTrue(classes.keys.none { it.startsWith("Lapp/morphe/extension/") }, "Standalone bundle must not rely on upstream runtime classes")
    }
}
