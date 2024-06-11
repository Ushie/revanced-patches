package app.revanced.patches.youtube.video.speed.remember

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.smali.ExternalLabel
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.ListPreference
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.youtube.misc.integrations.integrationsPatch
import app.revanced.patches.youtube.misc.settings.PreferenceScreen
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.patches.youtube.video.information.*
import app.revanced.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val INTEGRATIONS_CLASS_DESCRIPTOR =
    "Lapp/revanced/integrations/youtube/patches/playback/speed/RememberPlaybackSpeedPatch;"

internal val rememberPlaybackSpeedPatch = bytecodePatch {
    dependsOn(
        integrationsPatch,
        settingsPatch,
        videoInformationPatch,
        customPlaybackSpeedPatch,
        addResourcesPatch,
    )

    val initializePlaybackSpeedValuesResult by initializePlaybackSpeedValuesFingerprint

    execute {
        addResources("youtube", "video.speed.remember.RememberPlaybackSpeedPatch")

        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("revanced_remember_playback_speed_last_selected"),
            ListPreference(
                key = "revanced_playback_speed_default",
                summaryKey = null,
                // Entries and values are set by Integrations code based on the actual speeds available.
                entriesKey = null,
                entryValuesKey = null,
            ),
        )

        playerControllerOnCreateHook(INTEGRATIONS_CLASS_DESCRIPTOR, "newVideoStarted")
        userSelectedPlaybackSpeedHook(
            INTEGRATIONS_CLASS_DESCRIPTOR,
            "userSelectedPlaybackSpeed",
        )

        /*
         * Hook the code that is called when the playback speeds are initialized, and sets the playback speed
         */
        initializePlaybackSpeedValuesResult.mutableMethod.apply {
            // Infer everything necessary for calling the method setPlaybackSpeed().
            val onItemClickListenerClassFieldReference = getInstruction<ReferenceInstruction>(0).reference

            // Registers are not used at index 0, so they can be freely used.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $INTEGRATIONS_CLASS_DESCRIPTOR->getPlaybackSpeedOverride()F
                    move-result v0
                    
                    # Check if the playback speed is not 1.0x.
                    const/high16 v1, 0x3f800000  # 1.0f
                    cmpg-float v1, v0, v1
                    if-eqz v1, :do_not_override
    
                    # Get the instance of the class which has the container class field below.
                    iget-object v1, p0, $onItemClickListenerClassFieldReference

                    # Get the container class field.
                    iget-object v1, v1, $setPlaybackSpeedContainerClassFieldReference  
                    
                    # Get the field from its class.
                    iget-object v2, v1, $setPlaybackSpeedClassFieldReference
                    
                    # Invoke setPlaybackSpeed on that class.
                    invoke-virtual {v2, v0}, $setPlaybackSpeedMethodReference
                """.trimIndent(),
                ExternalLabel("do_not_override", getInstruction(0)),
            )
        }
    }
}
