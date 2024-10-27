package app.revanced.patches.backdrops.misc.pro

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val proUnlockPatch = bytecodePatch(
    name = "Pro unlock",
) {
    compatibleWith("com.backdrops.wallpapers")

    execute {
        val registerIndex = proUnlockMatch.patternMatch!!.endIndex - 1

        proUnlockMatch.mutableMethod.apply {
            val register = getInstruction<OneRegisterInstruction>(registerIndex).registerA
            addInstruction(
                proUnlockMatch.patternMatch!!.endIndex,
                "const/4 v$register, 0x1",
            )
        }
    }
}
