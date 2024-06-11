package app.revanced.patches.youtube.layout.miniplayer

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.removeInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.mapping.get
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.shared.misc.mapping.resourceMappings
import app.revanced.patches.shared.misc.settings.preference.*
import app.revanced.patches.youtube.misc.integrations.integrationsPatch
import app.revanced.patches.youtube.misc.settings.PreferenceScreen
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.util.*
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val INTEGRATIONS_CLASS_DESCRIPTOR = "Lapp/revanced/integrations/youtube/patches/MiniplayerPatch;"

@Suppress("unused")
val miniplayerPatch = bytecodePatch(
    name = "Miniplayer",
    description = "Adds options to change the in app minimized player, " +
        "and if patching target 19.16+ adds options to use modern miniplayers.",

) {
    dependsOn(
        integrationsPatch,
        settingsPatch,
        addResourcesPatch,
        miniplayerResourcePatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "18.32.39",
            "18.37.36",
            "18.38.44",
            "18.43.45",
            "18.44.41",
            "18.45.43",
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
            // 19.14 is left out, as it has incomplete miniplayer code and missing some UI resources.
            // It's simpler to not bother with supporting this single old version.
            // 19.15 has a different code for handling sub title texts,
            // and also probably not worth making changes just to support this single old version.
            "19.16.39", // Earliest supported version with modern miniplayers.
        ),
    )

    val miniplayerDimensionsCalculatorParentResult by miniplayerDimensionsCalculatorParentFingerprint
    val miniplayerResponseModelSizeCheckResult by miniplayerResponseModelSizeCheckFingerprint
    val miniplayerOverrideResult by miniplayerOverrideFingerprint
    val miniplayerModernConstructorResult by miniplayerModernConstructorFingerprint
    val miniplayerModernViewParentResult by miniplayerModernViewParentFingerprint
    val youTubePlayerOverlaysLayoutResult by youTubePlayerOverlaysLayoutFingerprint

    execute { context ->
        addResources("youtube", "layout.miniplayer.miniplayerPatch")

        // Modern mini player is only present and functional in 19.15+.
        // Resource is not present in older versions. Using it to determine, if patching an old version.
        val isPatchingOldVersion = ytOutlinePictureInPictureWhite24 < 0

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "revanced_miniplayer_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences =
                if (isPatchingOldVersion) {
                    setOf(
                        ListPreference(
                            "revanced_miniplayer_type",
                            summaryKey = null,
                            entriesKey = "revanced_miniplayer_type_legacy_entries",
                            entryValuesKey = "revanced_miniplayer_type_legacy_entry_values",
                        ),
                    )
                } else {
                    setOf(
                        ListPreference(
                            "revanced_miniplayer_type",
                            summaryKey = null,
                            entriesKey = "revanced_miniplayer_type_19_15_entries",
                            entryValuesKey = "revanced_miniplayer_type_19_15_entry_values",
                        ),
                        SwitchPreference("revanced_miniplayer_hide_expand_close"),
                        SwitchPreference("revanced_miniplayer_hide_subtext"),
                        SwitchPreference("revanced_miniplayer_hide_rewind_forward"),
                        TextPreference("revanced_miniplayer_opacity", inputType = InputType.NUMBER),
                    )
                },
            ),
        )

        fun Method.findReturnIndicesReversed() = findOpcodeIndicesReversed(Opcode.RETURN)

        fun MutableMethod.insertBooleanOverride(index: Int, methodName: String) {
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstructions(
                index,
                """
                invoke-static {v$register}, $INTEGRATIONS_CLASS_DESCRIPTOR->$methodName(Z)Z
                move-result v$register
            """,
            )
        }

        /**
         * Adds an override to force legacy tablet miniplayer to be used or not used.
         */
        fun MutableMethod.insertLegacyTabletMiniplayerOverride(index: Int) {
            insertBooleanOverride(index, "getLegacyTabletMiniplayerOverride")
        }

        /**
         * Adds an override to force modern miniplayer to be used or not used.
         */
        fun MutableMethod.insertModernMiniplayerOverride(index: Int) {
            insertBooleanOverride(index, "getModernMiniplayerOverride")
        }

        /**
         * Adds an override to specify which modern miniplayer is used.
         */
        fun MutableMethod.insertModernMiniplayerTypeOverride(iPutIndex: Int) {
            val targetInstruction = getInstruction<TwoRegisterInstruction>(iPutIndex)
            val targetReference = (targetInstruction as ReferenceInstruction).reference

            addInstructions(
                iPutIndex + 1,
                """
                invoke-static { v${targetInstruction.registerA} }, $INTEGRATIONS_CLASS_DESCRIPTOR->getModernMiniplayerOverrideType(I)I
                move-result v${targetInstruction.registerA}
                # Original instruction
                iput v${targetInstruction.registerA}, v${targetInstruction.registerB}, $targetReference 
            """,
            )
            removeInstruction(iPutIndex)
        }

        fun MethodFingerprint.hookInflatedView(
            literalValue: Long,
            hookedClassType: String,
            integrationsMethodName: String,
        ) {
            resultOrThrow().mutableMethod.apply {
                val imageViewIndex = indexOfFirstInstructionOrThrow(
                    indexOfFirstWideLiteralInstructionValueOrThrow(literalValue),
                ) {
                    opcode == Opcode.CHECK_CAST && getReference<TypeReference>()?.type == hookedClassType
                }

                val register = getInstruction<OneRegisterInstruction>(imageViewIndex).registerA
                addInstruction(
                    imageViewIndex + 1,
                    "invoke-static { v$register }, $integrationsMethodName",
                )
            }
        }

        // region Enable tablet miniplayer.

        miniplayerOverrideNoContextFingerprint.apply {
            resolve(
                context,
                miniplayerDimensionsCalculatorParentResult.classDef,
            )
        }.resultOrThrow().mutableMethod.apply {
            findReturnIndicesReversed().forEach { index -> insertLegacyTabletMiniplayerOverride(index) }
        }

        // endregion

        // region Legacy tablet Miniplayer hooks.

        val appNameStringIndex = miniplayerOverrideResult.scanResult.stringsScanResult!!.matches.first().index + 2
        miniplayerOverrideResult.mutableMethod.apply {
            val method = context.navigate(this)
                .at(appNameStringIndex)
                .mutable()

            method.apply {
                findReturnIndicesReversed().forEach { index -> insertLegacyTabletMiniplayerOverride(index) }
            }
        }

        miniplayerResponseModelSizeCheckResult.mutableMethod.insertLegacyTabletMiniplayerOverride(
            miniplayerResponseModelSizeCheckResult.scanResult.patternScanResult!!.endIndex,
        )

        if (isPatchingOldVersion) {
            // Return here, as patch below is only intended for new versions of the app.
            return@execute
        }

        // endregion

        // region Enable modern miniplayer.

        miniplayerModernConstructorResult.mutableClass.methods.forEach {
            it.apply {
                if (AccessFlags.CONSTRUCTOR.isSet(accessFlags)) {
                    val iPutIndex = indexOfFirstInstructionOrThrow {
                        this.opcode == Opcode.IPUT && this.getReference<FieldReference>()?.type == "I"
                    }

                    insertModernMiniplayerTypeOverride(iPutIndex)
                } else {
                    findReturnIndicesReversed().forEach { index -> insertModernMiniplayerOverride(index) }
                }
            }
        }

        // endregion

        // region Fix 19.16 using mixed up drawables for tablet modern.
        // YT fixed this mistake in 19.17.
        // Fix this, by swapping the drawable resource values with each other.

        miniplayerModernExpandCloseDrawablesFingerprint.apply {
            resolve(
                context,
                miniplayerModernViewParentResult.classDef,
            )
        }.resultOrThrow().mutableMethod.apply {
            listOf(
                ytOutlinePictureInPictureWhite24 to ytOutlineXWhite24,
                ytOutlineXWhite24 to ytOutlinePictureInPictureWhite24,
            ).forEach { (originalResource, replacementResource) ->
                val imageResourceIndex = indexOfFirstWideLiteralInstructionValueOrThrow(originalResource)
                val register = getInstruction<OneRegisterInstruction>(imageResourceIndex).registerA

                replaceInstruction(imageResourceIndex, "const v$register, $replacementResource")
            }
        }

        // endregion

        // region Add hooks to hide tablet modern miniplayer buttons.

        listOf(
            Triple(miniplayerModernExpandButtonFingerprint, modernMiniplayerExpand, "hideMiniplayerExpandClose"),
            Triple(miniplayerModernCloseButtonFingerprint, modernMiniplayerClose, "hideMiniplayerExpandClose"),
            Triple(
                miniplayerModernRewindButtonFingerprint,
                modernMiniplayerRewindButton,
                "hideMiniplayerRewindForward",
            ),
            Triple(
                miniplayerModernForwardButtonFingerprint,
                modernMiniplayerForwardButton,
                "hideMiniplayerRewindForward",
            ),
            Triple(miniplayerModernOverlayViewFingerprint, scrimOverlay, "adjustMiniplayerOpacity"),
        ).forEach { (fingerprint, literalValue, methodName) ->
            fingerprint.apply {
                resolve(
                    context,
                    miniplayerModernViewParentResult.classDef,
                )
            }.hookInflatedView(
                literalValue,
                "Landroid/widget/ImageView;",
                "$INTEGRATIONS_CLASS_DESCRIPTOR->$methodName(Landroid/widget/ImageView;)V",
            )
        }

        miniplayerModernAddViewListenerFingerprint.apply {
            resolve(
                context,
                miniplayerModernViewParentResult.classDef,
            )
        }.resultOrThrow().mutableMethod.addInstruction(
            0,
            "invoke-static { p1 }, $INTEGRATIONS_CLASS_DESCRIPTOR->" +
                "hideMiniplayerSubTexts(Landroid/view/View;)V",
        )

        // Modern 2 has a broken overlay subtitle view that is always present.
        // Modern 2 uses the same overlay controls as the regular video player,
        // and the overlay views are added at runtime.
        // Add a hook to the overlay class, and pass the added views to integrations.
        youTubePlayerOverlaysLayoutResult.mutableClass.methods.add(
            ImmutableMethod(
                YOUTUBE_PLAYER_OVERLAYS_LAYOUT_CLASS_NAME,
                "addView",
                listOf(
                    ImmutableMethodParameter("Landroid/view/View;", null, null),
                    ImmutableMethodParameter("I", null, null),
                    ImmutableMethodParameter("Landroid/view/ViewGroup\$LayoutParams;", null, null),
                ),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructions(
                    """
                        invoke-super { p0, p1, p2, p3 }, Landroid/view/ViewGroup;->addView(Landroid/view/View;ILandroid/view/ViewGroup${'$'}LayoutParams;)V
                        invoke-static { p1 }, $INTEGRATIONS_CLASS_DESCRIPTOR->playerOverlayGroupCreated(Landroid/view/View;)V
                        return-void
                    """,
                )
            },
        )

        // endregion
    }
}

var floatyBarButtonTopMargin = -1L
    private set

// Only available in 19.15 and upwards.
var ytOutlineXWhite24 = -1L
    private set
var ytOutlinePictureInPictureWhite24 = -1L
    private set
var scrimOverlay = -1L
    private set
var modernMiniplayerClose = -1L
    private set
var modernMiniplayerExpand = -1L
    private set
var modernMiniplayerRewindButton = -1L
    private set
var modernMiniplayerForwardButton = -1L
    private set
var playerOverlays = -1L
    private set

internal val miniplayerResourcePatch = resourcePatch {
    dependsOn(resourceMappingPatch)

    execute {
        floatyBarButtonTopMargin = resourceMappings[
            "dimen",
            "floaty_bar_button_top_margin",
        ]

        try {
            ytOutlinePictureInPictureWhite24 = resourceMappings[
                "drawable",
                "yt_outline_picture_in_picture_white_24",
            ]
        } catch (exception: PatchException) {
            // Ignore, and assume the app is 19.14 or earlier.
            return@execute
        }

        ytOutlineXWhite24 = resourceMappings[
            "drawable",
            "yt_outline_x_white_24",
        ]

        scrimOverlay = resourceMappings[
            "id",
            "scrim_overlay",
        ]

        modernMiniplayerClose = resourceMappings[
            "id",
            "modern_miniplayer_close",
        ]

        modernMiniplayerExpand = resourceMappings[
            "id",
            "modern_miniplayer_expand",
        ]

        modernMiniplayerRewindButton = resourceMappings[
            "id",
            "modern_miniplayer_rewind_button",
        ]

        modernMiniplayerForwardButton = resourceMappings[
            "id",
            "modern_miniplayer_forward_button",
        ]

        playerOverlays = resourceMappings[
            "layout",
            "player_overlays",
        ]

        // Resource id is not used during patching, but is used by integrations.
        // Verify the resource is present while patching.
        resourceMappings[
            "id",
            "modern_miniplayer_subtitle_text",
        ]
    }
}