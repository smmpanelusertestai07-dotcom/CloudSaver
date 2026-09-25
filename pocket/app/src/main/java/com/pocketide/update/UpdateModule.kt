package com.pocketide.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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

    override fun self(): ApkFacts = apkFacts(context.packageManager.getPackageInfo(context.packageName, APK_FACTS_FLAGS))

    override fun inspect(file: File): ApkFacts? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, APK_FACTS_FLAGS)?.let(::apkFacts)

    override fun mayInstall(activity: Activity): Boolean = installer.mayInstall(activity)

    override suspend fun install(activity: Activity, apk: File, done: (InstallResult) -> Unit) = installer.install(activity, apk, done)

    override fun schedule() = AppUpdateWorker.schedule(context)
}

/**
 * What the update rules need from Android's reading of an APK or of this app. GET_SIGNING_CERTIFICATES
 * alone leaves signingInfo null for an archive on Android 10 and on the first Android 13 release,
 * so GET_SIGNATURES is asked for too (brief §4, risk R24).
 */
@Suppress("DEPRECATION")
internal val APK_FACTS_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
internal fun apkFacts(info: PackageInfo): ApkFacts = ApkFacts(
    packageName = info.packageName,
    versionCode = info.longVersionCode,
    versionName = info.versionName,
    signers = signerDigests(
        contentsSigners = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() },
        signatures = info.signatures?.map { it.toByteArray() },
    ),
)

/**
 * SHA-256 of each certificate, as lower-case hex: the form of BuildConfig.SIGNING_CERT_SHA256.
 * The certificates that sign the contents now, never the ones a key rotation proved it may
 * replace (signingCertificateHistory); the old signatures only where Android gave no signingInfo.
 */
internal fun signerDigests(contentsSigners: List<ByteArray>?, signatures: List<ByteArray>?): Set<String> =
    (contentsSigners ?: signatures).orEmpty().map(::sha256Hex).toSet()

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
