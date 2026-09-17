package app.cloudsaver.engine

import android.content.Context
import android.content.pm.PackageManager
import app.cloudsaver.R
import app.cloudsaver.core.logic.CloudCapability
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.CloudCapabilityRow
import app.cloudsaver.util.AppLog

/**
 * Watches the cloud app the user chose, and stops deleting anything the
 * moment that app stops behaving like a working backup.
 *
 * The whole product rests on one assumption - that some other app is quietly
 * uploading the folder. When that stops being true, the dangerous thing is
 * not that copies pile up; it is that the app keeps reclaiming space against
 * evidence that is no longer arriving. So every failing check pauses
 * deletion, and only deletion: compressing and releasing continue, because
 * they cost the user nothing and leave the queue ready.
 */
class CloudWatchdog(private val context: Context) {

    private val db = AppDb.get(context)

    companion object {
        /** No traffic for this long, with copies waiting, is a stopped backup. */
        const val SILENCE_MS = 72 * 3_600_000L

        /** Below this, the cloud app has effectively sent nothing. */
        const val SILENCE_BYTES = 5L * 1024 * 1024
    }

    enum class Problem { NOT_INSTALLED, APP_UPDATED, NO_TRAFFIC, CLOUD_FULL }

    data class Verdict(
        val problem: Problem?,
        /** Deletions - of copies and of originals - are held while true. */
        val pauseDeletions: Boolean,
        val message: String?
    ) {
        val healthy: Boolean get() = problem == null
    }

    /**
     * @param waitingCopies released copies with no evidence yet
     * @param waitingBytes  their total size
     * @param txLastWindow  the cloud app's transmitted bytes over [SILENCE_MS]
     * @param folderShrank  whether the upload folder got smaller recently
     */
    suspend fun check(
        cloudId: String,
        waitingCopies: Int,
        waitingBytes: Long,
        txLastWindow: Long?,
        folderShrank: Boolean,
        now: Long = System.currentTimeMillis()
    ): Verdict {
        val app = CloudApps.byId(cloudId)
        val pkg = CloudApps.installedPackage(context, app)

        // "Other app" has no package to inspect; nothing here can be checked,
        // and holding deletions forever on that basis would jam the pipeline.
        if (app.packages.isEmpty()) return Verdict(null, false, null)

        if (pkg == null) {
            return Verdict(
                Problem.NOT_INSTALLED, true,
                context.getString(R.string.cloud_problem_missing)
            )
        }

        val version = versionCodeOf(pkg)
        val stored = db.capabilities().byId(cloudId)
        val caps = CloudCapability.defaultsFor(cloudId)
        val updated = stored != null && stored.lastSeenVersionCode != 0L &&
            version != 0L && stored.lastSeenVersionCode != version

        db.capabilities().put(
            CloudCapabilityRow(
                cloudId = cloudId,
                hasFreeUpSpace = stored?.hasFreeUpSpace ?: caps.hasFreeUpSpace,
                hasHashDedupe = stored?.hasHashDedupe ?: caps.hasHashDedupe,
                packageName = pkg,
                lastSeenVersionCode = version,
                learnedFreeUp = stored?.learnedFreeUp ?: false,
                updatedAt = now
            )
        )

        if (updated) {
            return Verdict(
                Problem.APP_UPDATED, true,
                context.getString(R.string.cloud_problem_updated)
            )
        }

        // Nothing waiting means there is nothing to be silent about.
        if (waitingCopies <= 0) return Verdict(null, false, null)

        if (txLastWindow != null && txLastWindow < SILENCE_BYTES) {
            return Verdict(
                Problem.NO_TRAFFIC, true,
                context.getString(R.string.cloud_problem_silent)
            )
        }

        // Transmitting, but the folder never shrinks and barely anything of
        // what is waiting has moved: the account is most likely out of room.
        val movedShare = if (waitingBytes > 0 && txLastWindow != null) {
            txLastWindow.toDouble() / waitingBytes
        } else {
            1.0
        }
        if (!folderShrank && movedShare < 0.01) {
            return Verdict(
                Problem.CLOUD_FULL, true,
                context.getString(R.string.cloud_problem_full)
            )
        }

        return Verdict(null, false, null)
    }

    /**
     * Records that this cloud removes its own uploads.
     *
     * Called when a released copy disappeared while the app was transmitting
     * its bytes - behaviour only a cloud with a free-up feature has. Learning
     * it here means the app adapts to a cloud the registry never knew about.
     */
    suspend fun learnFreeUp(cloudId: String, now: Long = System.currentTimeMillis()) {
        val stored = db.capabilities().byId(cloudId)
        if (stored?.learnedFreeUp == true) return
        val caps = CloudCapability.defaultsFor(cloudId)
        db.capabilities().put(
            CloudCapabilityRow(
                cloudId = cloudId,
                hasFreeUpSpace = true,
                hasHashDedupe = stored?.hasHashDedupe ?: caps.hasHashDedupe,
                packageName = stored?.packageName,
                lastSeenVersionCode = stored?.lastSeenVersionCode ?: 0L,
                learnedFreeUp = true,
                updatedAt = now
            )
        )
        AppLog.log(context, "cloud", "learned that $cloudId frees up space")
    }

    /** Stored capabilities if we have them, registry defaults otherwise. */
    suspend fun capsFor(cloudId: String): CloudCapability.Caps {
        val stored = db.capabilities().byId(cloudId)
        val defaults = CloudCapability.defaultsFor(cloudId)
        return CloudCapability.Caps(
            hasFreeUpSpace = stored?.hasFreeUpSpace ?: defaults.hasFreeUpSpace,
            hasHashDedupe = stored?.hasHashDedupe ?: defaults.hasHashDedupe
        )
    }

    private fun versionCodeOf(pkg: String): Long = try {
        context.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    } catch (e: Exception) {
        0L
    }
}
