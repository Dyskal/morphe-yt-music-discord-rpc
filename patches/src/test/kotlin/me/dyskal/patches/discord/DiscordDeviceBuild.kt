package me.dyskal.patches.discord

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Optional real-APK check, applying the official framework and this add-on together. */
fun main(arguments: Array<String>) {
    require(arguments.size in 2..3) { "Usage: DiscordDeviceBuildKt <input-apk> <official-morphe-bundle> [unsigned-output-apk]" }
    val input = File(arguments[0]).canonicalFile
    require(input.isFile) { "Input APK does not exist: $input" }
    val officialBundle = File(arguments[1]).canonicalFile
    require(officialBundle.isFile) { "Official Morphe bundle does not exist: $officialBundle" }
    val official = loadPatchesFromJar(setOf(officialBundle)).filter {
        it.name in setOf("GmsCore support", "Hide ads", "Remove background playback restrictions")
                && it.compatibility?.any { app -> app.packageName == "com.google.android.apps.youtube.music" } == true
    }.toSet()
    require(official.isNotEmpty()) { "Official bundle has no selected YouTube Music patches" }
    val output = arguments.getOrNull(2)?.let(::File)?.canonicalFile
    output?.let { require(it.parentFile.isDirectory) { "Output directory must exist: ${it.parent}" } }
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set ANDROID_HOME for SDK DEX verification")
    val workspace = Files.createTempDirectory("discord-device-build")
    Patcher(PatcherConfig(input, workspace.toFile(), verifier = SdkDexVerifier(File(sdk)))).use { patcher ->
        patcher += official + discordRpcPatch
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
