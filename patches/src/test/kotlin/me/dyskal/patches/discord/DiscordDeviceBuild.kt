package me.dyskal.patches.discord

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Optional real-APK check. This bundle adds RPC only; apply official playback/GmsCore patches separately. */
fun main(arguments: Array<String>) {
    require(arguments.size in 1..2) { "Usage: DiscordDeviceBuildKt <input-apk> [unsigned-output-apk]" }
    val input = File(arguments[0]).canonicalFile
    require(input.isFile) { "Input APK does not exist: $input" }
    val output = arguments.getOrNull(1)?.let(::File)?.canonicalFile
    output?.let { require(it.parentFile.isDirectory) { "Output directory must exist: ${it.parent}" } }
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set ANDROID_HOME for SDK DEX verification")
    val workspace = Files.createTempDirectory("discord-device-build")
    Patcher(PatcherConfig(input, workspace.toFile(), verifier = SdkDexVerifier(File(sdk)))).use { patcher ->
        patcher += setOf(discordRpcPatch)
        runBlocking {
            patcher().collect { result ->
                result.exception?.let { throw it }
                println("Applied: ${result.patch.name}")
            }
        }
        val patched = patcher.get()
        if (output == null) println("Discord patch applied and DEX verified: $workspace")
        else {
            patched.applyTo(output)
            println("Unsigned APK: $output")
        }
    }
}
