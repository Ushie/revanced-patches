package app.revanced.patches.youtube.layout.sponsorblock

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.mapping.get
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.shared.misc.mapping.resourceMappings
import app.revanced.patches.shared.misc.settings.preference.IntentPreference
import app.revanced.patches.youtube.misc.integrations.integrationsPatch
import app.revanced.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.revanced.patches.youtube.misc.playercontrols.playerControlsPatch
import app.revanced.patches.youtube.misc.playercontrols.showPlayerControlsFingerprintResult
import app.revanced.patches.youtube.misc.playertype.playerTypeHookPatch
import app.revanced.patches.youtube.misc.settings.newIntent
import app.revanced.patches.youtube.misc.settings.preferences
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.patches.youtube.shared.*
import app.revanced.patches.youtube.video.information.playerControllerOnCreateHook
import app.revanced.patches.youtube.video.information.videoInformationPatch
import app.revanced.patches.youtube.video.information.videoTimeHook
import app.revanced.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.revanced.patches.youtube.video.videoid.videoIdPatch
import app.revanced.util.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private val sponsorBlockResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        resourceMappingPatch,
        addResourcesPatch,
    )

    execute { context ->
        addResources("youtube", "layout.sponsorblock.sponsorBlockResourcePatch")

        preferences += IntentPreference(
            key = "revanced_settings_screen_10",
            titleKey = "revanced_sb_settings_title",
            summaryKey = null,
            intent = newIntent("revanced_sb_settings_intent"),
        )

        arrayOf(
            ResourceGroup(
                "layout",
                "revanced_sb_inline_sponsor_overlay.xml",
                "revanced_sb_new_segment.xml",
                "revanced_sb_skip_sponsor_button.xml",
            ),
            ResourceGroup(
                // required resource for back button, because when the base APK is used, this resource will not exist
                "drawable",
                "revanced_sb_adjust.xml",
                "revanced_sb_backward.xml",
                "revanced_sb_compare.xml",
                "revanced_sb_edit.xml",
                "revanced_sb_forward.xml",
                "revanced_sb_logo.xml",
                "revanced_sb_publish.xml",
                "revanced_sb_voting.xml",
            ),
            ResourceGroup(
                // required resource for back button, because when the base APK is used, this resource will not exist
                "drawable-xxxhdpi",
                "quantum_ic_skip_next_white_24.png",
            ),
        ).forEach { resourceGroup ->
            context.copyResources("sponsorblock", resourceGroup)
        }

        // copy nodes from host resources to their real xml files

        val hostingResourceStream =
            inputStreamFromBundledResource(
                "sponsorblock",
                "host/layout/youtube_controls_layout.xml",
            )!!

        var modifiedControlsLayout = false
        val document = context.document["res/layout/youtube_controls_layout.xml"]
        "RelativeLayout".copyXmlNode(
            context.document[hostingResourceStream],
            document,
        ).also {
            val children = document.getElementsByTagName("RelativeLayout").item(0).childNodes

            // Replace the startOf with the voting button view so that the button does not overlap
            for (i in 1 until children.length) {
                val view = children.item(i)

                // Replace the attribute for a specific node only
                if (!(
                        view.hasAttributes() &&
                            view.attributes.getNamedItem(
                                "android:id",
                            ).nodeValue.endsWith("live_chat_overlay_button")
                        )
                ) {
                    continue
                }

                // voting button id from the voting button view from the youtube_controls_layout.xml host file
                val votingButtonId = "@+id/revanced_sb_voting_button"

                view.attributes.getNamedItem("android:layout_toStartOf").nodeValue = votingButtonId

                modifiedControlsLayout = true
                break
            }
        }.close()

        if (!modifiedControlsLayout) throw PatchException("Could not modify controls layout")
    }
}

private const val INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/revanced/integrations/youtube/sponsorblock/SegmentPlaybackController;"
private const val INTEGRATIONS_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/revanced/integrations/youtube/sponsorblock/ui/CreateSegmentButtonController;"
private const val INTEGRATIONS_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/revanced/integrations/youtube/sponsorblock/ui/VotingButtonController;"
private const val INTEGRATIONS_SPONSORBLOCK_VIEW_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/revanced/integrations/youtube/sponsorblock/ui/SponsorBlockViewController;"

@Suppress("unused")
val sponsorBlockPatch = bytecodePatch(
    name = "SponsorBlock",
    description = "Adds options to enable and configure SponsorBlock, which can skip undesired video segments such as sponsored content.",
) {
    dependsOn(
        integrationsPatch,
        videoIdPatch,
        // Required to skip segments on time.
        videoInformationPatch,
        // Used to prevent SponsorBlock from running on Shorts because SponsorBlock does not yet support Shorts.
        playerTypeHookPatch,
        playerControlsPatch,
        sponsorBlockResourcePatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "18.48.39",
            "18.49.37",
            "19.01.34",
            "19.02.39",
            "19.03.36",
            "19.04.38",
            "19.05.36",
            "19.06.39",
            "19.07.40",
            "19.08.36",
            "19.09.38",
            "19.10.39",
            "19.11.43",
            "19.12.41",
            "19.13.37",
            "19.14.43",
            "19.15.36",
            "19.16.39",
        ),
    )

    val seekbarFingerprintResult by seekbarFingerprint
    val appendTimeFingerprintResult by appendTimeFingerprint
    val layoutConstructorFingerprintResult by layoutConstructorFingerprint
    val autoRepeatParentFingerprintResult by autoRepeatParentFingerprint

    execute { context ->
        /*
         * Hook the video time methods
         */
        videoTimeHook(
            INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR,
            "setVideoTime",
        )

        /*
         * Set current video id.
         */
        hookBackgroundPlayVideoId(
            "$INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->" +
                "setCurrentVideoId(Ljava/lang/String;)V",
        )

        /*
         * Seekbar drawing
         */
        val seekbarSignatureFingerprintResult =
            seekbarOnDrawFingerprint.apply { resolve(context, seekbarFingerprintResult.mutableClass) }.resultOrThrow()
        val seekbarMethod = seekbarSignatureFingerprintResult.mutableMethod
        val seekbarMethodInstructions = seekbarMethod.implementation!!.instructions

        /*
         * Get left and right of seekbar rectangle
         */
        val moveRectangleToRegisterIndex = seekbarMethodInstructions.indexOfFirst {
            it.opcode == Opcode.MOVE_OBJECT_FROM16
        }

        seekbarMethod.addInstruction(
            moveRectangleToRegisterIndex + 1,
            "invoke-static/range {p0 .. p0}, " +
                "$INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->setSponsorBarRect(Ljava/lang/Object;)V",
        )

        for ((index, instruction) in seekbarMethodInstructions.withIndex()) {
            if (instruction.opcode != Opcode.INVOKE_STATIC) continue

            val invokeInstruction = instruction as Instruction35c
            if ((invokeInstruction.reference as MethodReference).name != "round") continue

            val insertIndex = index + 2

            // set the thickness of the segment
            seekbarMethod.addInstruction(
                insertIndex,
                "invoke-static {v${invokeInstruction.registerC}}, " +
                    "$INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->setSponsorBarThickness(I)V",
            )

            break
        }

        /*
         * Draw segment
         */
        // Find the drawCircle call and draw the segment before it
        for (i in seekbarMethodInstructions.size - 1 downTo 0) {
            val invokeInstruction = seekbarMethodInstructions[i] as? ReferenceInstruction ?: continue
            if ((invokeInstruction.reference as MethodReference).name != "drawCircle") continue

            val (canvasInstance, centerY) = (invokeInstruction as FiveRegisterInstruction).let {
                it.registerC to it.registerE
            }
            seekbarMethod.addInstruction(
                i,
                "invoke-static {v$canvasInstance, v$centerY}, " +
                    "$INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->" +
                    "drawSponsorTimeBars(Landroid/graphics/Canvas;F)V",
            )

            break
        }

        /*
         * Voting & Shield button
         */
        val controlsMethodFingerprintResult = showPlayerControlsFingerprintResult

        val controlsLayoutStubResourceId = resourceMappings["id", "controls_layout_stub"]
        val zoomOverlayResourceId = resourceMappings["id", "video_zoom_overlay_stub"]

        methods@ for (method in controlsMethodFingerprintResult.mutableClass.methods) {
            val instructions = method.implementation?.instructions!!
            instructions@ for ((index, instruction) in instructions.withIndex()) {
                // search for method which inflates the controls layout view
                if (instruction.opcode != Opcode.CONST) continue@instructions

                when ((instruction as NarrowLiteralInstruction).wideLiteral) {
                    controlsLayoutStubResourceId -> {
                        // replace the view with the YouTubeControlsOverlay
                        val moveResultInstructionIndex = index + 5
                        val inflatedViewRegister =
                            (instructions[moveResultInstructionIndex] as OneRegisterInstruction).registerA
                        // initialize with the player overlay object
                        method.addInstructions(
                            moveResultInstructionIndex + 1, // insert right after moving the view to the register and use that register
                            """
                                invoke-static {v$inflatedViewRegister}, $INTEGRATIONS_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->initialize(Landroid/view/View;)V
                                invoke-static {v$inflatedViewRegister}, $INTEGRATIONS_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->initialize(Landroid/view/View;)V
                            """,
                        )
                    }

                    zoomOverlayResourceId -> {
                        val invertVisibilityMethod =
                            context.navigate(method).at(index - 6).mutable()
                        // change visibility of the buttons
                        invertVisibilityMethod.addInstructions(
                            0,
                            """
                                invoke-static {p1}, $INTEGRATIONS_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->changeVisibilityNegatedImmediate(Z)V
                                invoke-static {p1}, $INTEGRATIONS_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->changeVisibilityNegatedImmediate(Z)V
                            """.trimIndent(),
                        )
                    }
                }
            }
        }

        // change visibility of the buttons
        injectVisibilityCheckCall("$INTEGRATIONS_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->changeVisibility(Z)V")
        injectVisibilityCheckCall("$INTEGRATIONS_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR->changeVisibility(Z)V")

        // append the new time to the player layout
        val appendTimePatternScanStartIndex = appendTimeFingerprintResult.scanResult.patternScanResult!!.startIndex
        val targetRegister =
            (appendTimeFingerprintResult.method.implementation!!.instructions.elementAt(appendTimePatternScanStartIndex + 1) as OneRegisterInstruction).registerA

        appendTimeFingerprintResult.mutableMethod.addInstructions(
            appendTimePatternScanStartIndex + 2,
            """
                invoke-static {v$targetRegister}, $INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->appendTimeWithoutSegments(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$targetRegister
            """,
        )

        // initialize the player controller
        playerControllerOnCreateHook(INTEGRATIONS_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR, "initialize")

        // initialize the sponsorblock view
        controlsOverlayFingerprint.apply {
            resolve(context, layoutConstructorFingerprintResult.classDef)
        }.resultOrThrow().let {
            val startIndex = it.scanResult.patternScanResult!!.startIndex
            it.mutableMethod.apply {
                val frameLayoutRegister = (getInstruction(startIndex + 2) as OneRegisterInstruction).registerA
                addInstruction(
                    startIndex + 3,
                    "invoke-static {v$frameLayoutRegister}, $INTEGRATIONS_SPONSORBLOCK_VIEW_CONTROLLER_CLASS_DESCRIPTOR->initialize(Landroid/view/ViewGroup;)V",
                )
            }
        }

        // get rectangle field name
        rectangleFieldInvalidatorFingerprint.resolve(context, seekbarSignatureFingerprintResult.classDef)
        val rectangleFieldInvalidatorInstructions =
            rectangleFieldInvalidatorFingerprint.result!!.method.implementation!!.instructions
        val rectangleFieldName =
            ((rectangleFieldInvalidatorInstructions.elementAt(rectangleFieldInvalidatorInstructions.count() - 3) as ReferenceInstruction).reference as FieldReference).name

        // replace the "replaceMeWith*" strings
        context
            .proxy(context.classes.first { it.type.endsWith("SegmentPlaybackController;") })
            .mutableClass
            .methods
            .find { it.name == "setSponsorBarRect" }
            ?.let { method ->
                fun MutableMethod.replaceStringInstruction(index: Int, instruction: Instruction, with: String) {
                    val register = (instruction as OneRegisterInstruction).registerA
                    this.replaceInstruction(
                        index,
                        "const-string v$register, \"$with\"",
                    )
                }
                for ((index, it) in method.implementation!!.instructions.withIndex()) {
                    if (it.opcode.ordinal != Opcode.CONST_STRING.ordinal) continue

                    when (((it as ReferenceInstruction).reference as StringReference).string) {
                        "replaceMeWithsetSponsorBarRect" -> method.replaceStringInstruction(
                            index,
                            it,
                            rectangleFieldName,
                        )
                    }
                }
            } ?: throw PatchException("Could not find the method which contains the replaceMeWith* strings")

        // The vote and create segment buttons automatically change their visibility when appropriate,
        // but if buttons are showing when the end of the video is reached then they will not automatically hide.
        // Add a hook to forcefully hide when the end of the video is reached.
        autoRepeatFingerprint.also {
            it.resolve(context, autoRepeatParentFingerprintResult.classDef)
        }.resultOrThrow().mutableMethod.addInstruction(
            0,
            "invoke-static {}, $INTEGRATIONS_SPONSORBLOCK_VIEW_CONTROLLER_CLASS_DESCRIPTOR->endOfVideoReached()V",
        )

        // TODO: isSBChannelWhitelisting implementation
    }
}
