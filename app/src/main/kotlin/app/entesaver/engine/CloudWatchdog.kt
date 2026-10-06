package app.entesaver.engine

import android.content.Context
import android.content.pm.PackageManager
import app.entesaver.R
import app.entesaver.core.logic.CloudCapability
import app.entesaver.data.EnteApp
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.CloudCapabilityRow

/**
 * Watches Ente Photos, and stops deleting anything the moment it stops
 * behaving like a working backup.
 *
 * The whole product rests on one assumption - that Ente is quietly uploading
 * the folder. When that stops being true, the dangerous thing is
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
        waitingCopies: Int,
        waitingBytes: Long,
        txLastWindow: Long?,
        folderShrank: Boolean,
        now: Long = System.currentTimeMillis()
    ): Verdict {
        val pkg = EnteApp.installedPackage(context)

        // Installed but with nothing to inspect - only ever the debug builds'
        // test stand-in - is treated as nothing to report rather than as
        // missing, so the pipeline is testable on a phone without Ente.
        if (pkg == null && EnteApp.isInstalled(context)) return Verdict(null, null)

        if (pkg == null) {
            return Verdict(
                Problem.NOT_INSTALLED,
                context.getString(R.string.cloud_problem_missing)
            )
        }

        val version = versionCodeOf(pkg)
        val stored = db.capabilities().byId(EnteApp.ID)
        val caps = CloudCapability.ENTE
        val updated = stored != null && stored.lastSeenVersionCode != 0L &&
            version != 0L && stored.lastSeenVersionCode != version

        db.capabilities().put(
            CloudCapabilityRow(
                cloudId = EnteApp.ID,
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
                Problem.APP_UPDATED,
                context.getString(R.string.cloud_problem_updated)
            )
        }

        // Nothing waiting means there is nothing to be silent about.
        if (waitingCopies <= 0) return Verdict(null, null)

        if (txLastWindow != null && txLastWindow < SILENCE_BYTES) {
            return Verdict(
                Problem.NO_TRAFFIC,
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
                Problem.CLOUD_FULL,
                context.getString(R.string.cloud_problem_full)
            )
        }

        return Verdict(null, null)
    }

    /**
     * Records that Ente was seen removing its own uploads.
     *
     * Called when a released copy disappeared while Ente was transmitting its
     * bytes. Ente is known to do this; the record says it has also been seen
     * doing it on this phone.
     */
    suspend fun learnFreeUp(now: Long = System.currentTimeMillis()) {
        val stored = db.capabilities().byId(EnteApp.ID)
        if (stored?.learnedFreeUp == true) return
        val caps = CloudCapability.ENTE
        db.capabilities().put(
            CloudCapabilityRow(
                cloudId = EnteApp.ID,
                hasFreeUpSpace = true,
                hasHashDedupe = stored?.hasHashDedupe ?: caps.hasHashDedupe,
                packageName = stored?.packageName,
                lastSeenVersionCode = stored?.lastSeenVersionCode ?: 0L,
                learnedFreeUp = true,
                updatedAt = now
            )
        )
    }

    /** Stored capabilities if we have them, Ente's own otherwise. */
    suspend fun caps(): CloudCapability.Caps {
        val stored = db.capabilities().byId(EnteApp.ID)
        val defaults = CloudCapability.ENTE
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
