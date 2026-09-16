package app.cloudsaver.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.cloudsaver.R

/**
 * Why the phone last ended this app, in words rather than silence.
 *
 * "It just stopped" has an answer on Android 11 and later: the system keeps
 * a record of each time it ended a process and why - a force stop, a
 * memory shortage, an app that was using too much, a crash. Almost nothing
 * reads it. For an app whose whole job happens while nobody is looking, it
 * is the one fact that says whether the phone or the app is the problem.
 *
 * Nothing here is a permission: the record only ever holds this app's own
 * exits. The sister project in this repository reads the same record.
 */
object Exits {

    data class Exit(val at: Long, val reason: Int, val description: String)

    /** Older than this and it says nothing about the phone as it is now. */
    const val WORTH_SHOWING_MS = 14 * 86_400_000L

    /** The newest exit worth telling about, or null. */
    fun last(context: Context, now: Long = System.currentTimeMillis()): Exit? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val records = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        } catch (e: Exception) {
            return null
        }
        for (info in records) {
            if (now - info.timestamp > WORTH_SHOWING_MS) continue
            val description = info.description.orEmpty()
            if (worthShowing(info.reason, description)) {
                return Exit(info.timestamp, info.reason, description)
            }
        }
        return null
    }

    /**
     * The ordinary exits are left out: an app swiped away or closed by
     * itself needs no notice, and a screen that explains things nobody asked
     * about is a screen people stop reading.
     */
    fun worthShowing(reason: Int, description: String): Boolean = when (reason) {
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_FREEZER -> true
        ApplicationExitInfo.REASON_OTHER -> description.isNotBlank()
        else -> false
    }

    /** The reason as a sentence. */
    fun words(context: Context, exit: Exit): String = when (exit.reason) {
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> context.getString(R.string.exit_resources)
        ApplicationExitInfo.REASON_USER_REQUESTED -> context.getString(R.string.exit_user)
        ApplicationExitInfo.REASON_LOW_MEMORY -> context.getString(R.string.exit_memory)
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE -> context.getString(R.string.exit_crash)
        ApplicationExitInfo.REASON_ANR -> context.getString(R.string.exit_anr)
        ApplicationExitInfo.REASON_FREEZER -> context.getString(R.string.exit_freezer)
        else -> context.getString(R.string.exit_other, exit.description.take(60))
    }
}
