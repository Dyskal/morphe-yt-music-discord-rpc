package me.dyskal.patches.discord

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscordBundleTest {
    @Test
    fun `packaged extension retains runtime hooks settings callback and WebSocket dependency`() {
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream, "The bundle must include its own Android extension")
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val classes = dex.classes.associateBy { it.type }
        for (type in listOf("DiscordOAuthActivity", "DiscordSettingsActivity")) {
            val activity = classes["Lme/dyskal/ytmrpc/$type;"]
            assertNotNull(activity, "R8 must retain manifest-injected $type")
            assertTrue(activity.methods.any { it.name == "onCreate" })
        }
        val hooks = classes["Lme/dyskal/ytmrpc/DiscordRpcPatch;"]
        assertNotNull(hooks)
        for (name in listOf("initialize", "getApplicationId", "onSetMetadata", "onSetPlaybackState")) {
            assertTrue(hooks.methods.any { it.name == name })
        }
        assertTrue(classes.containsKey("Lokhttp3/OkHttpClient;"))
        assertTrue(classes.keys.none { it.startsWith("Lapp/morphe/extension/") }, "Standalone bundle must not rely on upstream runtime classes")
    }

    @Test
    fun `Manager option is required and rejects malformed application IDs`() {
        val option = discordRpcPatch.options["discordApplicationId"]
        option.reset()
        try {
            assertTrue(option.required)
            assertFailsWith<OptionException.ValueRequiredException> { option.value }
            for (invalid in listOf("", "123", "123456789012345678901", "12345678901234567\"", "not-an-id")) {
                assertFailsWith<OptionException.ValueValidationException> {
                    discordRpcPatch.options["discordApplicationId"] = invalid
                }
            }
            discordRpcPatch.options["discordApplicationId"] = "123456789012345678"
            assertEquals("123456789012345678", option.value)
        } finally { option.reset() }
    }

    @Test
    fun `Manager application ID is embedded into the actual R8 packaged getter`() {
        val stream = javaClass.classLoader.getResourceAsStream("extensions/discord.mpe")
        assertNotNull(stream)
        val dex = stream.use { DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(it.readBytes())) }
        val getter = dex.classes.single { it.type == "Lme/dyskal/ytmrpc/DiscordRpcPatch;" }
            .methods.single { it.name == "getApplicationId" }
        val method = MutableMethod(getter)
        embedDiscordApplicationId(method, "123456789012345678")
        val instructions = method.implementation!!.instructions.toList()
        assertEquals("123456789012345678", ((instructions[0] as ReferenceInstruction).reference as StringReference).string)
        assertEquals(Opcode.RETURN_OBJECT, instructions[1].opcode)
        assertFailsWith<IllegalArgumentException> { embedDiscordApplicationId(method, "invalid") }
    }
}
