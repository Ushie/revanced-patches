package app.revanced.patches.cieid.restrictions.root

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val bypassRootChecksPatch = bytecodePatch(
    name = "Bypass root checks",
    description = "Removes the restriction to use the app with root permissions or on a custom ROM.",
) {
    compatibleWith("it.ipzs.cieid")

    val checkRootResult by checkRootFingerprint

    execute {
        checkRootResult.mutableMethod.addInstruction(1, "return-void")
    }
}
