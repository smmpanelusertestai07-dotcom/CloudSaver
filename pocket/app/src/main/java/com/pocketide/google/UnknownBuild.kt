package com.pocketide.google

import android.content.Context
import android.content.pm.PackageManager
import com.pocketide.docs.OwnerSetUp
import java.security.MessageDigest

/**
 * Google's DEVELOPER_ERROR, said for the owner, who builds and signs PocketIDE: their Google
 * Cloud project has no Android OAuth client for this build's package and signing certificate, or
 * one that does not match them.
 */
internal object UnknownBuild {
    /** [sha1] is null when Android could not say which certificate signed this build. */
    fun message(packageName: String, sha1: String?): String {
        val certificate = sha1?.let { "the signing certificate SHA-1 $it" } ?: "the SHA-1 of the certificate that signed this build"
        return "Google does not know this build of PocketIDE. Your Google Cloud project needs an OAuth client of type " +
            "Android for the package $packageName and $certificate. Help, \"${OwnerSetUp.GOOGLE_CLOUD_TITLE}\", has the steps."
    }

    /** The SHA-1 of the certificate that signs this app now, as Google Cloud asks for it; null when Android cannot say. */
    fun signingSha1(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha1Fingerprint)
    }.getOrNull()

    /** Upper-case hex pairs joined by colons, "A9:99:…", the form Google Cloud shows. */
    fun sha1Fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(certificate).joinToString(":") { "%02X".format(it) }
}
