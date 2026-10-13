package me.dyskal.patches.discord

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** Replace the packaged extension's constant-return stub with the Manager option value. */
internal fun embedDiscordApplicationId(method: MutableMethod, applicationId: String) {
    require(applicationId.matches(Regex("[0-9]{17,20}"))) { "Discord application ID must contain 17–20 digits" }
    val instruction = method.getInstruction<OneRegisterInstruction>(0)
    require(instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) {
        "Unexpected Discord application-ID extension stub"
    }
    method.replaceInstruction(0, "const-string v${instruction.registerA}, \"$applicationId\"")
}
