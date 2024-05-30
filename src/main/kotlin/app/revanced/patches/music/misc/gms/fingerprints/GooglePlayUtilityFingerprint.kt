package app.revanced.patches.music.misc.gms.fingerprints

import app.revanced.patcher.fingerprint.methodFingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val googlePlayUtilityFingerprint = methodFingerprint {
    returns("I")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    strings(
        "This should never happen.",
        "MetadataValueReader",
        "GooglePlayServicesUtil",
        "com.android.vending",
        "android.hardware.type.embedded",
    )
}
