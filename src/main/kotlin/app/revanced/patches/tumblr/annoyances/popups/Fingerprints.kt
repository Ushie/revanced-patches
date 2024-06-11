package app.revanced.patches.tumblr.annoyances.popups

import com.android.tools.smali.dexlib2.AccessFlags
import app.revanced.patcher.fingerprint.methodFingerprint

// This method is responsible for loading and displaying the visual Layout of the Gift Message Popup.
internal val showGiftMessagePopupFingerprint = methodFingerprint {
    accessFlags(AccessFlags.FINAL, AccessFlags.PUBLIC)
    returns("V")
    strings("activity", "anchorView", "textMessage")
}