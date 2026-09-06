package app.cloudsaver.engine

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.os.Process

/**
 * Data-count verification: reads the selected cloud app's TX bytes by UID via
 * NetworkStatsManager (needs the user-granted Usage Access). Read-only and
 * fully on-device.
 */
object UsageVerifier {

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // checkOpNoThrow, not unsafeCheckOpNoThrow. Android 10 deprecated the
        // first in favour of the second; Android 16 reversed that, and the
        // platform jar this builds against marks unsafeCheckOpNoThrow
        // deprecated and checkOpNoThrow current. Both exist on every version
        // this app runs on and both answer the same question - is usage
        // access granted - without recording an access the way noteOp would.
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /**
     * Total TX bytes for [uid] over [fromMs, toMs] across mobile + wifi.
     * Returns null when Usage Access is missing (cannot measure).
     *
     * queryDetailsForUid(int, String, ...) is deprecated, but its replacement
     * (NetworkTemplate-based queries) is still @SystemApi - there is no current
     * public API for per-UID, time-ranged stats.
     */
    @Suppress("DEPRECATION")
    fun txBytesForUid(context: Context, uid: Int, fromMs: Long, toMs: Long): Long? {
        if (!hasUsageAccess(context)) return null
        val nsm = try {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        } catch (e: Exception) {
            return null
        }
        var total = 0L
        var anySuccess = false
        val networkTypes = intArrayOf(
            android.net.ConnectivityManager.TYPE_WIFI,
            android.net.ConnectivityManager.TYPE_MOBILE
        )
        for (type in networkTypes) {
            try {
                val stats = nsm.queryDetailsForUid(type, null, fromMs, toMs, uid)
                try {
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        total += bucket.txBytes
                    }
                } finally {
                    // Binder-backed and finite: closing it in a finally keeps a
                    // mid-iteration failure from leaking the handle.
                    stats.close()
                }
                anySuccess = true
            } catch (se: SecurityException) {
                return null
            } catch (e: Exception) {
                // This network type may not exist on the device; try the next.
            }
        }
        return if (anySuccess) total else null
    }
}
