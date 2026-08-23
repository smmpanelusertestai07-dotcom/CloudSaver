package app.cloudsaver.util

import android.content.Context
import android.content.pm.PackageManager
import app.cloudsaver.BuildConfig
import java.security.MessageDigest

/**
 * Tamper evidence, not tamper "protection" (that does not exist on Android):
 * at startup the APK signing-cert SHA-256 is compared to the constant embedded
 * at CI build time. On mismatch the app shows a permanent "modified copy"
 * banner and disables the Free-up tool and all deletions.
 * Dev builds (no embedded constant) skip the check.
 */
object TamperCheck {

    @Volatile
    private var cached: Boolean? = null

    fun isModified(context: Context): Boolean {
        cached?.let { return it }
        val result = compute(context)
        cached = result
        return result
    }

    private fun compute(context: Context): Boolean {
        val expected = BuildConfig.EXPECTED_CERT_SHA256
        if (expected.isBlank()) return false
        val actual = certSha256(context) ?: return true
        return !actual.equals(expected, ignoreCase = true)
    }

    fun certSha256(context: Context): String? = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
        val first = signers?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        digest.joinToString("") { b -> "%02x".format(b) }
    } catch (e: Exception) {
        null
    }
}
