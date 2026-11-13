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
    fun `YouTube Music compatibility matches official Morphe Patches 2_0_0`() {
        val compatibility = discordRpcPatch.compatibility!!.single()
        assertEquals("com.google.android.apps.youtube.music", compatibility.packageName)
        assertEquals(app.morphe.patcher.patch.ApkFileType.APK_REQUIRED, compatibility.apkFileType)
        val targets = compatibility.targets.associateBy { it.version }
        assertEquals(setOf("9.43.51", "9.42.52", "9.41.52", "9.30.53", "9.20.53", "9.15.51"), targets.keys)
        assertEquals(setOf("9.43.51", "9.42.52", "9.41.52"), targets.values.filter { it.isExperimental }.map { it.version }.toSet())
        assertTrue(targets.values.all { it.minSdk == 26 })
    }

    @Test
    fun `packaged extension retains local hooks and settings without old transport dependencies`() {
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream, "The bundle must include its own Android extension")
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val classes = dex.classes.associateBy { it.type }
        for (type in listOf("DiscordOAuthActivity", "DiscordOAuth", "DiscordAuthorization", "DiscordAuthLauncher",
            "DiscordTokenStore", "DiscordGateway", "DiscordExternalAssets", "Http")) {
            assertTrue(!classes.containsKey("Lme/dyskal/ytmrpc/$type;"), "Local-only extension must not retain $type")
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
        assertTrue(classes.keys.none { it.startsWith("Lokhttp3/") || it.startsWith("Lokio/") || it.startsWith("Landroidx/browser/") })
        assertTrue(classes.keys.none { it.startsWith("Lkotlin/") }, "Unused Kotlin runtime must be stripped from the Java-only extension")
        for (type in listOf("DiscordLocalRpc", "DiscordLocalBinder", "DiscordPresence")) {
            val local = classes["Lme/dyskal/ytmrpc/$type;"]
            assertNotNull(local, "R8 must retain the local RPC prototype")
            assertTrue(local.methods.none { com.android.tools.smali.dexlib2.AccessFlags.NATIVE.isSet(it.accessFlags) })
        }
        assertTrue(classes.keys.none { it.startsWith("Lcom/discord/socialsdk/") }, "Do not bundle proprietary SDK classes")
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
