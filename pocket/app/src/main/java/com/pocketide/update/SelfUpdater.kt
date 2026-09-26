package com.pocketide.update

import android.app.Activity
import com.pocketide.agents.SemVer
import com.pocketide.github.ReleasesMovedException
import com.pocketide.sync.NeedsMobileData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How Android's installer ended. */
internal sealed interface InstallResult {
    /** Android replaced the app (this process usually ends before anyone hears it). */
    data object Installed : InstallResult

    /** The owner closed Android's confirm screen. */
    data object Cancelled : InstallResult

    data class Failed(val why: String) : InstallResult
}

/** Android's side of an update: reading an APK, this app's own facts, and its installer. */
internal interface UpdaterEnv {
    val scope: CoroutineScope

    /** Where downloaded APKs are kept until installed. */
    val apkFolder: File

    /** This build's version name ("3.0.0"). */
    val currentVersion: String

    /** SHA-256 of the release signing certificate this build was made for, or blank. */
    val pinnedSigner: String

    fun self(): ApkFacts

    /** The tag of a release whose APK proved not newer than this app, kept across restarts; null when none. */
    var passedOver: String?

    /** Package, version and signers of the APK at [file], or null when Android cannot read it. */
    fun inspect(file: File): ApkFacts?

    /** False when the owner must first allow this app to install apps; the settings page is then open. */
    fun mayInstall(activity: Activity): Boolean

    /** Hands [apk] to Android's installer; [done] hears how it ended. */
    suspend fun install(activity: Activity, apk: File, done: (InstallResult) -> Unit)

    fun schedule()
}

/**
 * Checks PocketIDE's releases, downloads the newest APK (data rules first), keeps it only when
 * Android reads it as this app, newer, signed with this app's key, and installs it through
 * Android's installer on the owner's tap.
 */
internal class SelfUpdater(
    private val env: UpdaterEnv,
    private val releases: GitHubReleases,
    private val fetcher: ApkFetcher,
) : AppUpdater {

    private val mutable = MutableStateFlow<UpdateState>(UpdateState.UpToDate)
    override val state: StateFlow<UpdateState> = mutable.asStateFlow()
    private val lock = Mutex()

    /** The release the last check found, kept while the state moves through download and install. */
    @Volatile
    private var pending: AppRelease? = null

    override suspend fun check() = lock.withLock { checkLocked() }

    override suspend fun download() = lock.withLock {
        val release = pending ?: run {
            checkLocked()
            pending
        } ?: return@withLock
        if (mutable.value is UpdateState.Ready) return@withLock
        mutable.value = UpdateState.Downloading(0f)
        try {
            val file = fetcher.fetch(release, apkFile(release.version)) { fraction -> publishProgress(fraction) }
            mutable.value = withContext(Dispatchers.IO) { verified(release, file) }
        } catch (cancelled: CancellationException) {
            mutable.value = UpdateState.Available(release)
            throw cancelled
        } catch (waiting: UpdateWaits) {
            mutable.value = UpdateState.Available(release)
            throw waiting
        } catch (ask: NeedsMobileData) {
            mutable.value = UpdateState.Available(release)
            throw ask
        } catch (failed: IOException) {
            mutable.value = UpdateState.Failed(failed.message ?: "The update could not be downloaded. Try again.")
        }
    }

    override fun install(activity: Activity) {
        val ready = mutable.value as? UpdateState.Ready ?: return
        env.scope.launch {
            lock.withLock {
                try {
                    handOver(activity, ready)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: IOException) {
                    mutable.value = UpdateState.Failed("Android's installer could not take the update: ${failed.message}")
                } catch (refused: SecurityException) {
                    mutable.value = UpdateState.Failed("Android did not let PocketIDE install the update.")
                } catch (failed: RuntimeException) {
                    // Nothing here may end the app: the owner can try again from Settings.
                    mutable.value = UpdateState.Failed("Android's installer could not take the update. Try again.")
                }
            }
        }
    }

    private suspend fun handOver(activity: Activity, ready: UpdateState.Ready) {
        val file = apkFile(ready.release.version)
        // Checked again right before Android sees it: the file must still be the one that passed.
        val problem = withContext(Dispatchers.IO) { if (file.isFile) UpdateRules.problem(env.inspect(file), env.self(), env.pinnedSigner) else MISSING }
        if (problem != null) {
            withContext(Dispatchers.IO) { file.delete() }
            mutable.value = UpdateState.Failed(problem)
            return
        }
        if (!withContext(Dispatchers.Main) { env.mayInstall(activity) }) return
        env.install(activity, file) { result -> finished(ready, result) }
    }

    override fun schedule() = env.schedule()

    private suspend fun checkLocked() {
        val current = SemVer.parse(env.currentVersion)?.core() ?: return
        val release = try {
            withContext(Dispatchers.IO) { releases.newest(current) }
        } catch (moved: ReleasesMovedException) {
            mutable.value = UpdateState.Failed(moved.message.orEmpty(), retry = false)
            return
        } catch (failed: IOException) {
            mutable.value = UpdateState.Failed(failed.message ?: "PocketIDE could not check for updates. Try again later.")
            return
        }
        val offered = release?.takeUnless { it.tag == env.passedOver }
        pending = offered
        withContext(Dispatchers.IO) { tidy(keep = offered?.version) }
        mutable.value = when {
            offered == null -> UpdateState.UpToDate
            apkFile(offered.version).isFile -> withContext(Dispatchers.IO) { verified(offered, apkFile(offered.version)) }
            else -> UpdateState.Available(offered)
        }
    }

    /**
     * Ready when the file passes every rule; otherwise it is deleted and the state says why. A
     * release that is not a newer build is remembered and no longer offered: downloading it again
     * would only be refused again.
     */
    private fun verified(release: AppRelease, file: File): UpdateState {
        val candidate = env.inspect(file)
        val self = env.self()
        val problem = UpdateRules.problem(candidate, self, env.pinnedSigner) ?: return UpdateState.Ready(release)
        file.delete()
        return if (UpdateRules.notNewer(candidate, self)) {
            env.passedOver = release.tag
            pending = null
            UpdateState.UpToDate
        } else {
            UpdateState.Failed(problem)
        }
    }

    private fun finished(ready: UpdateState.Ready, result: InstallResult) {
        mutable.value = when (result) {
            InstallResult.Installed -> UpdateState.UpToDate
            InstallResult.Cancelled -> ready
            is InstallResult.Failed -> UpdateState.Failed(result.why)
        }
    }

    /** Whole percents only: the screen does not need a new state for every buffer. */
    private fun publishProgress(fraction: Float) {
        val rounded = (fraction * PERCENT).toInt() / PERCENT
        val shown = (mutable.value as? UpdateState.Downloading)?.fraction
        if (shown == null || rounded > shown) mutable.value = UpdateState.Downloading(rounded)
    }

    /** Deletes APKs of any other version, and anything a stopped download left. */
    private fun tidy(keep: String?) {
        val keepName = keep?.let { apkFile(it).name }
        env.apkFolder.listFiles()?.filter { it.name != keepName }?.forEach { it.delete() }
    }

    private fun apkFile(version: String): File {
        require(SAFE_VERSION.matches(version)) { "Not a version: $version" }
        return File(env.apkFolder, "pocketide-$version.apk")
    }

    private companion object {
        const val PERCENT = 100f
        const val MISSING = "The downloaded update is gone. Download it again."
        val SAFE_VERSION = Regex("[0-9][0-9A-Za-z.-]{0,63}")
    }
}
