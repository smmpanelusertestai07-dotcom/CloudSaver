package com.pocketide.update

/** What Android reads from an APK (or from this installed app): who it is, which build, who signed it. */
internal data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    /** SHA-256 of each signing certificate, lower-case hex. */
    val signers: Set<String>,
)

/**
 * An update is installed only when it is this app, a newer build, and signed with exactly the
 * key this app was signed with: the same package and key is also how 3.0 installs over 2.6.0.
 * [pinned] is the certificate the release was built for, when the build knows it.
 */
internal object UpdateRules {

    /** Null when [candidate] may replace [self]; otherwise why not, in one sentence. */
    fun problem(candidate: ApkFacts?, self: ApkFacts, pinned: String?): String? = when {
        candidate == null -> "The downloaded file is not an app Android can read."
        candidate.packageName != self.packageName -> "The downloaded file is a different app (${candidate.packageName.take(MAX_NAME)})."
        candidate.versionCode <= self.versionCode -> "The downloaded file is not newer than the app you have."
        candidate.signers.isEmpty() -> "The downloaded file is not signed."
        candidate.signers != self.signers -> "The downloaded file is not signed with PocketIDE's key, so it was deleted."
        !pinned.isNullOrBlank() && pinned.lowercase() !in candidate.signers -> "The downloaded file is not signed with PocketIDE's release key, so it was deleted."
        else -> null
    }

    private const val MAX_NAME = 80
}
