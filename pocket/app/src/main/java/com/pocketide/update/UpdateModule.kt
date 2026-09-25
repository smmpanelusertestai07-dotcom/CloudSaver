package com.pocketide.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.pocketide.AppGraph
import com.pocketide.BuildConfig
import com.pocketide.core.Http
import com.pocketide.github.createPublicReleases
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.security.MessageDigest

fun createAppUpdater(graph: AppGraph): AppUpdater {
    val env = GraphUpdaterEnv(graph)
    val releases = createPublicReleases()
    return SelfUpdater(
        env = env,
        releases = GitHubReleases(
            client = Http.client,
            list = { releases.list(BuildConfig.RELEASES_REPO) },
            tagPrefix = BuildConfig.RELEASE_TAG_PREFIX,
        ),
        fetcher = ApkFetcher(
            client = Http.downloads,
            allow = { bytes -> graph.dataBudget.allow(bytes, DATA_KIND, big = true) },
            record = { bytes -> graph.dataBudget.record(bytes, DATA_KIND) },
        ),
    )
}

private const val DATA_KIND = "app update"

private class GraphUpdaterEnv(private val graph: AppGraph) : UpdaterEnv {
    private val context: Context get() = graph.context
    private val installer = SessionInstaller(graph.context, leaving = { graph.appLock.leavingOnErrand() })

    override val scope: CoroutineScope get() = graph.scope
    override val apkFolder: File get() = File(graph.dirs.apk, "update")
    override val currentVersion: String get() = BuildConfig.VERSION_NAME
    override val pinnedSigner: String get() = BuildConfig.SIGNING_CERT_SHA256

    override fun self(): ApkFacts {
        val info = context.packageManager.getPackageInfo(context.packageName, SIGNERS)
        return facts(info)
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var passedOver: String?
        get() = prefs.getString(PASSED_OVER, null)
        set(tag) = prefs.edit { putString(PASSED_OVER, tag) }

    /**
     * GET_SIGNING_CERTIFICATES alone leaves signingInfo null for an archive on Android 10 and on
     * the first Android 13 release, so GET_SIGNATURES is asked for too (brief §4, risk R24).
     */
    override fun inspect(file: File): ApkFacts? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, SIGNERS)?.let(::facts)

    override fun mayInstall(activity: Activity): Boolean = installer.mayInstall(activity)

    override suspend fun install(activity: Activity, apk: File, done: (InstallResult) -> Unit) = installer.install(activity, apk, done)

    override fun schedule() = AppUpdateWorker.schedule(context)

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): ApkFacts {
        // The certificates that signed the contents now, not the ones a rotation proved it may replace.
        val certificates = info.signingInfo?.apkContentsSigners ?: info.signatures
        val signers = certificates.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        return ApkFacts(info.packageName, info.longVersionCode, info.versionName, signers)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS = "pocketide.update"
        const val PASSED_OVER = "passed_over_tag"

        @Suppress("DEPRECATION")
        val SIGNERS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
