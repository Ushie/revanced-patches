package app.revanced.patches.lightroom.misc.login

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val disableMandatoryLoginPatch = bytecodePatch(
    name = "Disable mandatory login",
) {
    compatibleWith("com.adobe.lrmobile")

    val isLoggedInResult by isLoggedInFingerprint

    execute {
        isLoggedInResult.mutableMethod.apply {
            val index = implementation!!.instructions.lastIndex - 1
            // Set isLoggedIn = true.
            replaceInstruction(index, "const/4 v0, 0x1")
        }
    }
}
